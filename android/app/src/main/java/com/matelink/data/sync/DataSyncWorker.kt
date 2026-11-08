package com.matelink.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import com.matelink.R
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background worker for syncing stats data from TeslamateApi.
 *
 * Runs as a foreground service to prevent being killed when app is in background.
 * - Phase 1: Sync summaries (fast, for Quick Stats)
 * - Phase 2: Sync details (slow, for Deep Stats)
 */
@HiltWorker
class DataSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val teslamateRepository: TeslamateRepository,
    private val syncRepository: SyncRepository,
    private val syncManager: SyncManager,
    private val logCollector: SyncLogCollector
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "DataSyncWorker"
        const val WORK_NAME = "data_sync_work"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "sync_channel"
    }

    private fun log(message: String) = logCollector.log(TAG, message)
    private fun logError(message: String, error: Throwable? = null) = logCollector.logError(TAG, message, error)

    /**
     * Required for expedited work - provides foreground info for older API levels.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo("Syncing data...")
    }

    // Track if foreground service is available (may fail on Android 14+ from background)
    private var foregroundAvailable = true

    override suspend fun doWork(): Result {
        log("Starting data sync worker (attempt ${runAttemptCount})")
        val run = syncManager.newRun()
        fun failure(reason: String): Result {
            syncManager.failRun(run, reason)
            return Result.failure(terminalData("failed", reason))
        }
        fun apiFailure(error: ApiResult.Error): Result {
            syncManager.failRun(run, "history_sync_failed")
            return if (isTerminalDriveDetailFailure(error)) {
                Result.failure(terminalData("failed", error.kind.name.lowercase(java.util.Locale.ROOT)))
            } else {
                Result.retry()
            }
        }

        // Run as foreground service to prevent being killed when screen is off
        // This shows a persistent notification during sync
        // On Android 14+, this may fail if started from background
        foregroundAvailable = trySetForeground("Starting sync...")

        var continuationQueued = false
        try {
            val intent = inputData.getString("history_sync_intent") ?: "FULL_SYNC"
            if (intent !in setOf("FULL_SYNC", "DETAIL_CONTINUATION")) return failure("invalid_intent")
            val continuation = intent == "DETAIL_CONTINUATION"
            val fence = if (continuation) inputData.getString("history_scope_epoch") ?: return failure("missing_scope")
                else syncRepository.captureContinuationFence()
            if (syncRepository.captureContinuationFence() != fence) return failure("scope_changed")
            val carIds = if (continuation) inputData.getIntArray("history_car_ids")?.toList() ?: return failure("missing_cars")
                else when (val cars = teslamateRepository.getCars()) {
                    is ApiResult.Error -> return apiFailure(cars)
                    is ApiResult.Success -> cars.data.map { it.carId }
                }
            if (carIds.isEmpty()) return if (continuation) failure("empty_cars") else Result.success(terminalData("complete", "no_cars"))
            if (carIds.distinct().size != carIds.size) return failure("duplicate_cars")
            val proof = if (continuation) inputData.getString("history_car_proof") ?: return failure("missing_car_proof")
                else {
                    for (id in carIds) {
                        if (syncRepository.captureContinuationFence() != fence) return failure("scope_changed")
                        when (val prepared = syncRepository.syncCar(id, summariesOnly = true, run = run)) {
                            is CarSyncResult.Failed -> return apiFailure(prepared.error)
                            else -> Unit
                        }
                    }
                    syncRepository.continuationCarProof(carIds)
                }
            suspend fun current() = syncRepository.captureContinuationFence() == fence &&
                syncRepository.continuationCarProof(carIds) == proof && syncRepository.captureContinuationFence() == fence
            if (!current()) return failure("identity_changed")
            syncRepository.claimRunCars(run, carIds)
            val offset = Math.floorMod(inputData.getInt("history_car_cursor", 0), carIds.size)
            val ordered = carIds.drop(offset) + carIds.take(offset)
            val outcome = runHistoryDetailBatch(ordered) { car, budget ->
                if (!current()) throw CancellationException("History continuation fence changed")
                syncRepository.syncDetailsOnly(car, budget, run)
            }
            if (!current()) return failure("identity_changed")
            return when (outcome) {
                is CarSyncResult.Failed -> apiFailure(outcome.error)
                is CarSyncResult.Deferred -> finishDataSyncBatch(outcome.pendingDrives) {
                    scheduleDetailContinuation(fence, carIds, proof, (offset + 10) % carIds.size)
                }.also { continuationQueued = true }
                CarSyncResult.Complete -> { scheduleGeocoding(); Result.success(terminalData("complete", "complete")) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncManager.failRun(run, "history_sync_failed")
            logError("Unexpected error in sync worker", e)
            return if (isNetworkException(e)) {
                log("Network error, will retry...")
                Result.retry()
            } else {
                failure("unexpected_failure")
            }
        } finally {
            // Completed/error phases are retained. Only a durably queued
            // continuation keeps this run's unfinished phases active.
            if (!continuationQueued) syncManager.cancelRun(run)
        }
    }

    /** WorkManager persists only bounded codes/times, never exception text, identities or URLs. */
    private fun terminalData(state: String, reason: String) = androidx.work.workDataOf(
        "history_sync_state" to state, "history_sync_reason" to reason,
        "history_sync_finished_at" to System.currentTimeMillis())

    /** A normal batch is continued without using network-error exponential retry attempts. */
    internal suspend fun scheduleDetailContinuation(fence: String, carIds: List<Int>, proof: String, cursor: Int) {
        val request = detailContinuationRequest(fence, carIds, proof, cursor)
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get()
        }
    }

    internal fun detailContinuationRequest(fence: String, carIds: List<Int>, proof: String, cursor: Int) =
        OneTimeWorkRequestBuilder<DataSyncWorker>()
            .setInputData(androidx.work.workDataOf("history_sync_intent" to "DETAIL_CONTINUATION",
                "history_scope_epoch" to fence, "history_car_ids" to carIds.toIntArray(),
                "history_car_proof" to proof, "history_car_cursor" to cursor))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(5, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

    /**
     * Schedule background geocoding worker as a safety net.
     * Uses KEEP policy to avoid interrupting running geocoding (which may have
     * been started earlier by SyncRepository during drive/charge processing).
     */
    private fun scheduleGeocoding() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<GeocodeWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .addTag(GeocodeWorker.TAG)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                GeocodeWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,  // Don't replace if already running
                request
            )

        log("Ensured geocoding worker is scheduled")
    }

    private fun isNetworkError(message: String?): Boolean {
        if (message == null) return false
        val networkKeywords = listOf("dns", "network", "connect", "timeout", "unreachable", "refused", "reset")
        return networkKeywords.any { message.lowercase().contains(it) }
    }

    private fun isNetworkException(e: Throwable): Boolean {
        return e is IOException ||
               e is UnknownHostException ||
               e.cause is IOException ||
               e.cause is UnknownHostException ||
               isNetworkError(e.message)
    }

    /**
     * Try to set foreground service. Returns true if successful, false otherwise.
     * On Android 14+, this may fail if the app is in the background.
     */
    private suspend fun trySetForeground(progress: String): Boolean {
        if (!foregroundAvailable) return false
        return try {
            setForeground(createForegroundInfo(progress))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Could not set foreground service: ${e.message}")
            foregroundAvailable = false
            false
        }
    }

    /**
     * Create foreground info for the notification.
     */
    private fun createForegroundInfo(progress: String): ForegroundInfo {
        val context = applicationContext

        // Create notification channel (required for Android 8.0+)
        createNotificationChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("MateLink Sync")
            .setContentText(progress)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Data Sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background sync for stats data"
        }
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

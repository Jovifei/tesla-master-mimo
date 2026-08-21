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
import com.matelink.R
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

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
    private fun logError(message: String, error: Throwable? = null) =
        logCollector.logError(TAG, message, error)

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo("Syncing data...")

    private var foregroundAvailable = true

    override suspend fun doWork(): Result {
        log("Starting data sync worker (attempt ${runAttemptCount})")
        foregroundAvailable = trySetForeground("Starting sync...")

        try {
            val carsResult = teslamateRepository.getCars()
            val cars = when (carsResult) {
                is ApiResult.Success -> carsResult.data
                is ApiResult.Error -> {
                    logError("Failed to fetch cars: ${carsResult.message}")
                    return when {
                        carsResult.message.contains("not configured", ignoreCase = true) -> {
                            log("Server not configured, skipping sync")
                            Result.success()
                        }
                        isNetworkError(carsResult.message) -> Result.retry()
                        else -> Result.failure()
                    }
                }
            }

            if (cars.isEmpty()) return Result.success()

            var hasNetworkError = false
            for ((index, car) in cars.withIndex()) {
                try {
                    trySetForeground("Syncing car ${index + 1}/${cars.size}...")
                    if (!syncRepository.syncCar(car.carId)) hasNetworkError = true
                } catch (e: Exception) {
                    logError("Error syncing car ${car.carId}", e)
                    if (isNetworkException(e)) {
                        hasNetworkError = true
                    } else {
                        syncManager.markSyncError(car.carId, e.message ?: "Unknown error")
                    }
                }
            }

            return if (hasNetworkError) {
                Result.retry()
            } else {
                scheduleGeocoding()
                DriveReportMonitorWorker.runNow(applicationContext)
                Result.success()
            }
        } catch (e: Exception) {
            logError("Unexpected error in sync worker", e)
            return if (isNetworkException(e)) Result.retry() else Result.failure()
        }
    }

    private fun scheduleGeocoding() {
        val request = OneTimeWorkRequestBuilder<GeocodeWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .addTag(GeocodeWorker.TAG)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            GeocodeWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun isNetworkError(message: String?): Boolean {
        if (message == null) return false
        val keywords = listOf("dns", "network", "connect", "timeout", "unreachable", "refused", "reset")
        return keywords.any { message.lowercase().contains(it) }
    }

    private fun isNetworkException(e: Throwable): Boolean =
        e is IOException ||
            e is UnknownHostException ||
            e.cause is IOException ||
            e.cause is UnknownHostException ||
            isNetworkError(e.message)

    private suspend fun trySetForeground(progress: String): Boolean {
        if (!foregroundAvailable) return false
        return try {
            setForeground(createForegroundInfo(progress))
            true
        } catch (e: Exception) {
            log("Could not set foreground service: ${e.message}")
            foregroundAvailable = false
            false
        }
    }

    private fun createForegroundInfo(progress: String): ForegroundInfo {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Data Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Background sync for stats data" }
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}

package com.matelink.data.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.matelink.data.local.ChargeSessionStateDataStore
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.SentryEvent
import com.matelink.data.repository.SentryStateRepository
import com.matelink.data.local.HistoryIdentityUnavailableException
import kotlinx.coroutines.CancellationException
import com.matelink.notification.ChargingNotificationManager
import com.matelink.notification.SentryNotificationManager
import com.matelink.service.ChargingMonitorService
import com.matelink.widget.CarWidgetUpdateWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodic background worker for monitoring charging sessions and sentry events.
 *
 * Runs every 30 seconds to check charging and sentry state for all cars,
 * and shows/updates/cancels notifications accordingly.
 */
@HiltWorker
class ChargingNotificationWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val settingsDataStore: SettingsDataStore,
    private val chargingNotificationManager: ChargingNotificationManager,
    private val sentryStateRepository: SentryStateRepository,
    private val sentryNotificationManager: SentryNotificationManager,
    private val chargeSessionStateDataStore: ChargeSessionStateDataStore,
    private val vehicleContextRepository: VehicleContextRepository,
    private val connectionModes: com.matelink.data.local.ConnectionModeStore,
    private val vehicleStatusStore: com.matelink.data.local.VehicleStatusStore,
    private val selection: com.matelink.data.repository.SettingsRepository,
    private val boundSource: BoundNotificationSource
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "ChargingNotificationWorker"
        const val WORK_NAME = "charging_notification_work"
        const val PERIODIC_WORK_NAME = "charging_notification_periodic"

        private const val INTERVAL_SECONDS = 30L

        /**
         * Schedule charging/sentry notification monitoring.
         *
         * Uses two strategies:
         * 1. Self-rescheduling OneTimeWorkRequest for frequent checks (30s)
         * 2. PeriodicWorkRequest (15min) as reliable fallback when app is killed
         */
        fun ensureScheduled(context: Context) = schedulePeriodicWork(context)

        fun schedulePeriodicWork(context: Context, appendAfterCurrent: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Strategy 1: OneTimeWorkRequest with delay for frequent checks
            val oneTimeRequest = OneTimeWorkRequestBuilder<ChargingNotificationWorker>()
                .setConstraints(constraints)
                .setInitialDelay(INTERVAL_SECONDS, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                if (appendAfterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                oneTimeRequest
            )

            // Strategy 2: PeriodicWorkRequest as reliable backup (survives app death)
            // This ensures notification is cancelled within 15 minutes even if app is killed
            val periodicRequest = PeriodicWorkRequestBuilder<ChargingNotificationWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag("$TAG-periodic")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,  // Don't reset if already scheduled
                periodicRequest
            )

            Log.d(TAG, "Scheduled notification check (${INTERVAL_SECONDS}s + 15min backup)")
        }

        /**
         * Cancel all charging notification monitoring.
         */
        fun cancelPeriodicWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
            Log.d(TAG, "Cancelled charging notification work")
        }

        /**
         * Run charging check immediately (for app startup or debugging).
         */
        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<ChargingNotificationWorker>()
                .setConstraints(constraints)
                .addTag("$TAG-immediate")
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "Triggered immediate charging check")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting notification check")

        // Check if server is configured
        val settings = settingsDataStore.settings.first()
        val plan = notificationReadPlan(connectionModes.mode.first(), settings.isConfigured)
        if (plan == NotificationReadPlan.SAVED_CLOUD) {
            try { consumeSavedCloudObservation() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* No qualified observation; retain state and wait for a real update. */ }
            scheduleNextCheck()
            return Result.success()
        }
        if (plan == NotificationReadPlan.UNCONFIGURED) {
            Log.d(TAG, "Server not configured, skipping check")
            scheduleNextCheck()
            return Result.success()
        }

        try {
            val batch = boundSource.openSelfHosted()
            for (vehicle in batch.vehicles()) {
                batch.ensureCurrent(vehicle)
                try { checkCarStatus(batch, vehicle) }
                catch (e: CancellationException) { throw e }
                catch (e: HistoryIdentityUnavailableException) { throw e }
                catch (_: Exception) { Log.w(TAG, "event=notification_vehicle_read_failed") }
            }
            scheduleNextCheck()
            return Result.success()
        } catch (e: CancellationException) { throw e }
          catch (_: HistoryIdentityUnavailableException) {
            // A route change invalidates this batch; the next scheduled run
            // chooses the current route without waking Fleet from an old task.
            scheduleNextCheck()
            return Result.success()
        } catch (_: Exception) {
            Log.w(TAG, "event=notification_batch_unavailable")
            scheduleNextCheck()
            return Result.retry()
        }
    }

    private suspend fun checkCarStatus(batch: BoundNotificationSession, vehicle: BoundNotificationVehicle) {
        val status = batch.status(vehicle)
        val local = vehicle.context.localHistoryCarId
        val car = vehicle.car
        suspend fun guard() = batch.ensureCurrent(vehicle)
        guard()
        val phases = status.chargingDetails?.chargerPhases
        if (status.isCharging && phases != null && phases in 0..3) {
            chargeSessionStateDataStore.setLastSessionDc(local, phases == 0)
            guard()
        } else if (status.pluggedIn == false) {
            chargeSessionStateDataStore.clear(local)
            guard()
        }
        val wasDc = chargeSessionStateDataStore.wasLastSessionDc(local)
        guard()
        val finishedDc = status.isChargeCompletePluggedIn && wasDc
        if (status.isCharging || finishedDc) {
            guard()
            try { ChargingMonitorService.start(appContext) }
            catch (_: Exception) {
                guard()
                val available = if (status.isCharging) batch.currentChargeAvailable(vehicle) else false
                guard()
                chargingNotificationManager.showChargingNotification(car, local, status, available,
                    dcFinishedPluggedIn = finishedDc, chronometerBaseMs = status.stateSinceEpochMs)
            }
        } else if (status.pluggedIn == false || status.chargingState != null) {
            guard()
            ChargingMonitorService.stop(appContext)
            guard()
            chargingNotificationManager.cancelNotification(local)
        }
        guard()
        sentryStateRepository.consumeObservation(local, status, System.currentTimeMillis(),
            ensureCurrent = ::guard) { event ->
            guard()
            when (event) {
                is SentryEvent.AlertDetected -> {
                    sentryNotificationManager.showSentryAlert(car.displayName, local, event.count, event.shouldNotify,
                        remoteApiCarId = car.carId)
                    guard()
                    CarWidgetUpdateWorker.scheduleImmediateUpdate(appContext)
                }
                SentryEvent.SessionEnded -> sentryNotificationManager.cancelNotification(local)
                null -> Unit
            }
        }
    }

    /** Cloud consumes only already saved, identity-qualified observations. */
    private suspend fun consumeSavedCloudObservation() {
        val scope = vehicleContextRepository.captureReadScope()
        if (scope.source != com.matelink.data.local.HistoryConnectionSource.CLOUD) return
        val remote = selection.currentCarId.first()
        val vehicle = vehicleContextRepository.cachedContextForRemote(remote, scope) ?: return
        val digest = vehicleContextRepository.cachedVerifiedStatusIdentityDigest(remote, scope, vehicle)
        val receipt = vehicleStatusStore.getCachedReceipt(scope, vehicle)
            ?: digest?.let { vehicleStatusStore.getCachedReceiptForVerifiedIdentity(scope, vehicle, it) } ?: return
        val observed = com.matelink.util.parseIsoInstant(receipt.observedAt) ?: return
        val now = java.time.Instant.now()
        if (observed.isAfter(now.plusSeconds(5)) || observed.isBefore(now.minusSeconds(120)) ||
            receipt.source !in setOf("fleet_api", "telemetry_mqtt", "live_mqtt")) return
        suspend fun guard() {
            if (!vehicleContextRepository.isHistoryContextCurrent(scope, vehicle))
                throw HistoryIdentityUnavailableException()
        }
        val legacy = appContext.getSharedPreferences("cloud_notification_observations", Context.MODE_PRIVATE)
        val key = "observed_${vehicle.localHistoryCarId}"
        val status = receipt.status
        val local = vehicle.localHistoryCarId
        val car = com.matelink.data.api.models.CarData(remote, name = status.displayName)
        sentryStateRepository.consumeObservation(local, status, observed.toEpochMilli(),
            legacyWatermark = legacy.getLong(key, Long.MIN_VALUE), ensureCurrent = ::guard) { event ->
            guard()
            if (status.chargingState != null) {
                if (status.isCharging) chargingNotificationManager.showChargingNotification(car, local,
                    status, false, chronometerBaseMs = status.stateSinceEpochMs)
                else if (status.pluggedIn == false) chargingNotificationManager.cancelNotification(local)
            }
            guard()
            when (event) {
                is SentryEvent.AlertDetected -> sentryNotificationManager.showSentryAlert(
                    car.displayName, local, event.count, event.shouldNotify, remoteApiCarId = remote)
                SentryEvent.SessionEnded -> sentryNotificationManager.cancelNotification(local)
                null -> Unit
            }
            guard()
            // Preserve the legacy watermark for upgrades/rollback. The atomic
            // DataStore observation remains authoritative for current consumers.
            check(legacy.edit().putLong(key, observed.toEpochMilli()).commit())
        }
    }

    /**
     * Schedule the next check using self-rescheduling pattern.
     */
    private fun scheduleNextCheck() {
        // Only the unique one-time pass appends a successor. Bootstrap/boot,
        // immediate checks and the periodic safety net retain an existing chain.
        schedulePeriodicWork(appContext, appendAfterCurrent = tags.contains(TAG))
    }
}

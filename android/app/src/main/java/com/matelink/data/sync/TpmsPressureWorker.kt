package com.matelink.data.sync

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
import com.matelink.BuildConfig
import com.matelink.R
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.TirePosition
import com.matelink.data.local.TpmsAlertProfile
import com.matelink.data.local.TpmsCustomAlertStateStore
import com.matelink.data.local.TpmsCustomAlertClaim
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.repository.TpmsHistoryRepository
import com.matelink.data.repository.TpmsStateChange
import com.matelink.data.repository.TpmsStateChangeClaim
import com.matelink.data.repository.TpmsStateChangeClaimResult
import com.matelink.data.repository.TpmsStateRepository
import com.matelink.domain.analytics.TpmsCustomAlert
import com.matelink.domain.analytics.TpmsCustomAlertEvaluator
import com.matelink.domain.analytics.tpmsCustomAlertFingerprint
import com.matelink.notification.TpmsTrendNotificationManager
import com.matelink.notification.NotificationDeliveryUnavailableException
import com.matelink.notification.ensureTpmsNotificationChannel
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodic background worker for monitoring tire pressure warnings.
 *
 * Runs every 15 minutes to check TPMS status for all cars and sends notifications
 * when tires enter or exit a warning state.
 */
@HiltWorker
class TpmsPressureWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val teslamateRepository: TeslamateRepository,
    private val tpmsStateRepository: TpmsStateRepository,
    private val settingsDataStore: SettingsDataStore,
    private val tpmsHistoryRepository: TpmsHistoryRepository,
    private val tpmsCustomAlertStateStore: TpmsCustomAlertStateStore,
    private val tpmsTrendNotificationManager: TpmsTrendNotificationManager,
    private val vehicleContextRepository: VehicleContextRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "TpmsPressureWorker"
        const val WORK_NAME = "tpms_pressure_work"
        const val CHANNEL_ID = "tire_pressure_channel"
        private const val NOTIFICATION_ID_BASE = 2000

        // Debug: 3 minutes, Release: 15 minutes
        private val INTERVAL_MINUTES = if (BuildConfig.DEBUG) 3L else 15L

        /**
         * Schedule periodic TPMS monitoring work.
         * Uses 3-minute interval in debug builds, 15-minute in release.
         *
         * Note: WorkManager enforces a 15-minute minimum for PeriodicWorkRequest,
         * so in debug mode we use a self-rescheduling OneTimeWorkRequest pattern
         * to achieve shorter intervals.
         */
        fun schedulePeriodicWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            if (BuildConfig.DEBUG) {
                // Debug: Use OneTimeWorkRequest with delay for shorter intervals
                // Append the successor to the currently running work. KEEP would discard
                // this request while the current worker is still running.
                val request = OneTimeWorkRequestBuilder<TpmsPressureWorker>()
                    .setConstraints(constraints)
                    .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .addTag(TAG)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                )

                Log.d(TAG, "Scheduled TPMS monitoring (debug mode, ${INTERVAL_MINUTES}min interval)")
            } else {
                // Release: Use PeriodicWorkRequest (15-minute minimum)
                val request = PeriodicWorkRequestBuilder<TpmsPressureWorker>(
                    INTERVAL_MINUTES, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .addTag(TAG)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

                Log.d(TAG, "Scheduled periodic TPMS monitoring (${INTERVAL_MINUTES}min interval)")
            }
        }

        /**
         * Cancel periodic TPMS monitoring work.
         */
        fun cancelPeriodicWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic TPMS monitoring work")
        }

        /**
         * Run TPMS check immediately (for debugging).
         */
        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<TpmsPressureWorker>()
                .setConstraints(constraints)
                .addTag("$TAG-immediate")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
            Log.d(TAG, "Triggered immediate TPMS check")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting TPMS pressure check")

        // Check if server is configured
        val settings = settingsDataStore.settings.first()
        if (!settings.isConfigured) {
            Log.d(TAG, "Server not configured, skipping TPMS check")
            return Result.success()
        }

        // Create notification channel
        ensureTpmsNotificationChannel(appContext)

        try {
            // Get list of cars
            val carsResult = teslamateRepository.getCars()
            val cars = when (carsResult) {
                is ApiResult.Success -> carsResult.data
                is ApiResult.Error -> {
                    Log.e(TAG, "event=fetch_cars_failed category=api_error")
                    return Result.retry()
                }
            }

            if (cars.isEmpty()) {
                Log.d(TAG, "No cars found")
                return Result.success()
            }

            Log.d(TAG, "Checking TPMS for ${cars.size} cars")

            // Check each car
            for (car in cars) {
                try {
                    val vehicleContext = vehicleContextRepository.resolve(car)
                    checkCarTpms(car.carId, vehicleContext.localHistoryCarId, car.displayName)
                } catch (e: Exception) {
                    Log.e(TAG, "event=check_tpms_failed carId=${car.carId} category=unexpected")
                }
            }

            Log.d(TAG, "TPMS check complete")

            // In debug mode, reschedule the next check (self-rescheduling pattern)
            if (BuildConfig.DEBUG) {
                schedulePeriodicWork(appContext)
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "event=tpms_worker_failed category=unexpected")

            // In debug mode, reschedule even on failure
            if (BuildConfig.DEBUG) {
                schedulePeriodicWork(appContext)
            }

            return Result.retry()
        }
    }

    private suspend fun checkCarTpms(remoteApiCarId: Int, historyCarId: Int, carName: String) {
        // Get car status
        val statusResult = teslamateRepository.getCarStatus(remoteApiCarId)
        val status = when (statusResult) {
            is ApiResult.Success -> statusResult.data.status
            is ApiResult.Error -> {
                Log.e(TAG, "event=fetch_status_failed carId=$historyCarId category=api_error")
                return
            }
        }

        val tpmsDetails = status.tpmsDetails
        val outsideTempC = status.outsideTemp

        val profile = settingsDataStore.getTpmsAlertProfile(historyCarId)

        processSuccessfulTpmsStatus(
            carId = historyCarId,
            carName = carName,
            tpmsDetails = tpmsDetails,
            outsideTempC = outsideTempC,
            observedAt = System.currentTimeMillis(),
            profile = profile,
            saveObservation = { sample ->
                tpmsHistoryRepository.saveObservationForHistoryCarId(historyCarId, sample)
            },
            pruneOlderThan90Days = { _, now ->
                tpmsHistoryRepository.pruneOlderThan90DaysForHistoryCarId(historyCarId, now)
            },
            detectTeslaStateChange = { _, details ->
                tpmsStateRepository.detectStateChangeForHistoryCarId(historyCarId, details)
            },
            updateTeslaState = { _, details ->
                tpmsStateRepository.updateStateForHistoryCarId(historyCarId, details)
            },
            resetCustomState = tpmsCustomAlertStateStore::resetForProfile,
            claimCustomAlerts = tpmsCustomAlertStateStore::claimAlerts,
            commitCustomAlert = tpmsCustomAlertStateStore::commitClaim,
            releaseCustomAlert = tpmsCustomAlertStateStore::releaseClaim,
            claimTeslaStateChange = { _, details, now ->
                tpmsStateRepository.claimStateChangeForHistoryCarId(historyCarId, details, now)
            },
            commitTeslaStateChange = { _, claim ->
                tpmsStateRepository.commitStateChangeForHistoryCarId(historyCarId, claim)
            },
            releaseTeslaStateChange = { _, claim ->
                tpmsStateRepository.releaseStateChangeForHistoryCarId(historyCarId, claim)
            },
            notifyTesla = ::showNotification,
            notifyCustom = { id, _, alert ->
                tpmsTrendNotificationManager.showCustomAlert(id, alert)
            }
        )
    }

    private fun showNotification(carId: Int, carName: String, stateChange: TpmsStateChange) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) throw NotificationDeliveryUnavailableException()
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            throw NotificationDeliveryUnavailableException()
        }
        ensureTpmsNotificationChannel(appContext)
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        if (notificationManager.getNotificationChannel(CHANNEL_ID)?.importance ==
            NotificationManager.IMPORTANCE_NONE
        ) throw NotificationDeliveryUnavailableException()

        val (title, body) = when (stateChange) {
            is TpmsStateChange.WarningStarted -> {
                val tireNames = stateChange.tires.map { tire ->
                    getTireFullName(tire)
                }.joinToString(", ")

                Pair(
                    appContext.getString(R.string.tpms_notification_title),
                    appContext.getString(R.string.tpms_notification_body, carName, tireNames)
                )
            }
            is TpmsStateChange.WarningCleared -> {
                Pair(
                    appContext.getString(R.string.tpms_notification_title),
                    appContext.getString(R.string.tpms_notification_cleared, carName)
                )
            }
        }

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        // Use different notification ID per car
        val notificationId = NOTIFICATION_ID_BASE + carId
        runCatching { notificationManager.notify(notificationId, notification) }
            .getOrElse { throw NotificationDeliveryUnavailableException(it) }

    }

    private fun getTireFullName(tire: TirePosition): String {
        return when (tire) {
            TirePosition.FL -> appContext.getString(R.string.tire_fl_full)
            TirePosition.FR -> appContext.getString(R.string.tire_fr_full)
            TirePosition.RL -> appContext.getString(R.string.tire_rl_full)
            TirePosition.RR -> appContext.getString(R.string.tire_rr_full)
        }
    }

}

private fun TpmsAlertProfile?.isEnabledAndValid(): Boolean =
    this != null && enabled && isValid

private fun com.matelink.data.api.models.TpmsDetails?.hasCompleteSoftWarningFields(): Boolean =
    this != null && warningFl != null && warningFr != null && warningRl != null && warningRr != null

internal suspend fun processSuccessfulTpmsStatus(
    carId: Int,
    carName: String,
    tpmsDetails: com.matelink.data.api.models.TpmsDetails?,
    outsideTempC: Double? = null,
    observedAt: Long,
    profile: TpmsAlertProfile?,
    saveObservation: suspend (TpmsPressureSample) -> Unit,
    pruneOlderThan90Days: suspend (Int, Long) -> Unit,
    detectTeslaStateChange: suspend (Int, com.matelink.data.api.models.TpmsDetails?) -> TpmsStateChange?,
    updateTeslaState: suspend (Int, com.matelink.data.api.models.TpmsDetails?) -> Unit,
    resetCustomState: suspend (Int, String) -> Unit,
    claimCustomAlerts: suspend (
        Int,
        String,
        Map<TirePosition, com.matelink.data.local.TpmsCustomWheelObservation>,
        Boolean,
        Long
    ) -> List<TpmsCustomAlertClaim>,
    commitCustomAlert: suspend (Int, TpmsCustomAlertClaim) -> Unit,
    releaseCustomAlert: suspend (Int, TpmsCustomAlertClaim) -> Unit,
    notifyTesla: (Int, String, TpmsStateChange) -> Unit,
    notifyCustom: (Int, String, TpmsCustomAlert) -> Unit,
    claimTeslaStateChange: (suspend (Int, com.matelink.data.api.models.TpmsDetails?, Long) -> TpmsStateChangeClaimResult)? = null,
    commitTeslaStateChange: (suspend (Int, TpmsStateChangeClaim) -> Unit)? = null,
    releaseTeslaStateChange: (suspend (Int, TpmsStateChangeClaim) -> Unit)? = null
) {
    val sample = TpmsPressureSample(
        carId = carId,
        observedAt = observedAt,
        pressureFl = tpmsDetails?.pressureFl?.takeIf { it.isFinite() },
        pressureFr = tpmsDetails?.pressureFr?.takeIf { it.isFinite() },
        pressureRl = tpmsDetails?.pressureRl?.takeIf { it.isFinite() },
        pressureRr = tpmsDetails?.pressureRr?.takeIf { it.isFinite() },
        outsideTempC = outsideTempC?.takeIf { it.isFinite() }
    )
    saveObservation(sample)
    pruneOlderThan90Days(carId, observedAt)

    val hasCompleteTeslaObservation = tpmsDetails.hasCompleteSoftWarningFields()
    val teslaClaimResult = if (hasCompleteTeslaObservation) {
        claimTeslaStateChange?.invoke(carId, tpmsDetails, observedAt)
            ?: detectTeslaStateChange(carId, tpmsDetails)?.let { change ->
                TpmsStateChangeClaimResult.Claimed(
                    TpmsStateChangeClaim(change, requireNotNull(tpmsDetails).toLegacyTpmsState())
                )
            }
            ?: TpmsStateChangeClaimResult.NoTransition
    } else {
        TpmsStateChangeClaimResult.NoTransition
    }
    val teslaClaim = (teslaClaimResult as? TpmsStateChangeClaimResult.Claimed)?.claim

    // Keep the legacy test seam's snapshot bookkeeping. The real worker supplies
    // durable claim callbacks, so a null claim there means no transition or a
    // live claim and must not bypass the transaction.
    if (hasCompleteTeslaObservation && claimTeslaStateChange == null &&
        teslaClaimResult is TpmsStateChangeClaimResult.NoTransition
    ) {
        updateTeslaState(carId, tpmsDetails)
    }

    val profileFingerprint = profile?.tpmsCustomAlertFingerprint() ?: DISABLED_PROFILE_FINGERPRINT
    resetCustomState(carId, profileFingerprint)
    val customObservations = profile
        ?.takeIf { it.isEnabledAndValid() }
        ?.let { TpmsCustomAlertEvaluator().observe(it, tpmsDetails) }
        ?: emptyMap()
    val customClaims = claimCustomAlerts(
        carId,
        profileFingerprint,
        customObservations,
        teslaClaimResult !is TpmsStateChangeClaimResult.NoTransition,
        observedAt
    )

    if (teslaClaimResult is TpmsStateChangeClaimResult.Claimed && teslaClaim != null) {
        try {
            notifyTesla(carId, carName, teslaClaim.change)
            commitTeslaStateChange?.invoke(carId, teslaClaim)
                ?: updateTeslaState(carId, tpmsDetails)
        } catch (_: Exception) {
            releaseTeslaStateChange?.invoke(carId, teslaClaim)
        }
    } else {
        customClaims.forEach { claim ->
            val alert = TpmsCustomAlert(
                wheel = claim.wheel,
                state = claim.state,
                observedPressureBar = claim.observedPressureBar,
                thresholdBar = claim.thresholdBar
            )
            try {
                notifyCustom(carId, carName, alert)
                commitCustomAlert(carId, claim)
            } catch (_: Exception) {
                releaseCustomAlert(carId, claim)
            }
        }
    }
}

private fun com.matelink.data.api.models.TpmsDetails.toLegacyTpmsState() =
    com.matelink.data.local.TpmsState(
        warningFl = warningFl == true,
        warningFr = warningFr == true,
        warningRl = warningRl == true,
        warningRr = warningRr == true
    )

private const val DISABLED_PROFILE_FINGERPRINT = "disabled"

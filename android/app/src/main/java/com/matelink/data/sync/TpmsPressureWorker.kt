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
import com.matelink.data.local.HistoryIdentityUnavailableException
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val vehicleContextRepository: VehicleContextRepository,
    private val changeRuleStore: com.matelink.data.local.TpmsChangeRuleStore,
    private val settingsRepository: com.matelink.data.repository.SettingsRepository
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
        fun schedulePeriodicWork(context: Context, appendAfterCurrent: Boolean = false) {
            val constraints = Constraints.Builder()
                // Cloud recovery consumes durable local receipts, including offline.
                // Self-hosted reads return WorkManager retry when their server is unavailable.
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            if (BuildConfig.DEBUG) {
                // Debug: Use OneTimeWorkRequest with delay for shorter intervals
                // Application/boot registration is idempotent. Only a completed
                // worker pass appends its delayed successor to the live chain.
                val request = OneTimeWorkRequestBuilder<TpmsPressureWorker>()
                    .setConstraints(constraints)
                    .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .addTag(TAG)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    if (appendAfterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                    request
                )

                Log.d(TAG, "Scheduled TPMS monitoring (debug mode, ${INTERVAL_MINUTES}min interval)")
            } else {
                // Release: first read after 15 minutes, then a 15-minute periodic cadence.
                val request = PeriodicWorkRequestBuilder<TpmsPressureWorker>(
                    INTERVAL_MINUTES, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .addTag(TAG)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    // Replace an older network-constrained spec after an app update;
                    // the local-only cloud recovery must also run while offline.
                    ExistingPeriodicWorkPolicy.UPDATE,
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
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
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

        // The scoped identity validates the active cloud session or self-hosted
        // origin. A cloud account does not need a self-hosted server URL.
        val readScope = try {
            vehicleContextRepository.captureReadScope()
        } catch (e: CancellationException) {
            throw e
        } catch (_: HistoryIdentityUnavailableException) {
            Log.d(TAG, "History identity unavailable, skipping TPMS check")
            return Result.success()
        } catch (_: Exception) {
            Log.e(TAG, "event=history_identity_read_failed category=local_error")
            return Result.retry()
        }

        // Create notification channel
        ensureTpmsNotificationChannel(appContext)

        try {
            if (readScope.source == com.matelink.data.local.HistoryConnectionSource.CLOUD) {
                // Background cloud work consumes only trusted local receipts. A
                // selected numeric ID alone is not identity proof and never causes
                // discovery, namespace allocation or a live provider request.
                val selectedCarId = settingsRepository.currentCarId.first()
                val cached = vehicleContextRepository.cachedContextForRemote(selectedCarId, readScope)
                if (cached != null && vehicleContextRepository.isHistoryContextCurrent(readScope, cached)) {
                    checkCarTpms(cached, appContext.getString(R.string.app_name), readScope)
                }
                if (BuildConfig.DEBUG) schedulePeriodicWork(appContext, appendAfterCurrent = true)
                return Result.success()
            }
            // Get list of cars
            val carsResult = teslamateRepository.getCars()
            if (vehicleContextRepository.captureReadScope() != readScope) return Result.retry()
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
            if (cars.groupingBy { it.carId }.eachCount().any { it.value > 1 }) return Result.retry()

            Log.d(TAG, "Checking TPMS for ${cars.size} cars")

            // Check each car
            for (car in cars) {
                try {
                    if (vehicleContextRepository.captureReadScope() != readScope) return Result.retry()
                    val verifiedCar = vehicleContextRepository.discoverHistoryCar(car.carId,readScope) ?: continue
                    if (readScope.source == com.matelink.data.local.HistoryConnectionSource.CLOUD &&
                        verifiedCar.vehicleUid != car.vehicleUid) return Result.retry()
                    val vehicleContext = vehicleContextRepository.resolveVerifiedHistoryCar(verifiedCar, readScope)
                    if (!vehicleContextRepository.isHistoryContextCurrent(readScope,vehicleContext)) return Result.retry()
                    checkCarTpms(vehicleContext, car.displayName, readScope)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "event=check_tpms_failed category=unexpected")
                }
            }

            Log.d(TAG, "TPMS check complete")

            // In debug mode, reschedule the next check (self-rescheduling pattern)
            if (BuildConfig.DEBUG) {
                schedulePeriodicWork(appContext, appendAfterCurrent = true)
            }

            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "event=tpms_worker_failed category=unexpected")

            // WorkManager retries this same request; do not append another delayed
            // successor for every failed attempt while the current request is live.
            return Result.retry()
        }
    }

    private suspend fun checkCarTpms(
        vehicle: com.matelink.data.local.VehicleContext,
        carName: String,
        readScope: com.matelink.data.local.HistoryReadScope
    ) {
        fun ensureScopeNow() {
            if (!vehicleContextRepository.isHistoryContextCurrentNow(readScope,vehicle)) {
                throw CancellationException("TPMS source context superseded")
            }
        }
        suspend fun ensureScope() {
            currentCoroutineContext().ensureActive()
            if (!vehicleContextRepository.isHistoryContextCurrent(readScope,vehicle)) {
                throw CancellationException("TPMS source context superseded")
            }
        }
        ensureScope()
        if (BuildConfig.JOURVOLT_MOCK_LOGIN && settingsRepository.mockMode.first() == true) return
        ensureScope()
        val historyCarId=vehicle.localHistoryCarId
        // Durable intent recovery does not depend on a new status response.
        recoverTpmsAdmissionIntents(historyCarId,tpmsHistoryRepository,changeRuleStore,::ensureScope)
        ensureScope()
        // Cloud acquisition belongs to already requested foreground responses;
        // this guard also protects future callers of the local recovery path.
        val details = if (readScope.source == com.matelink.data.local.HistoryConnectionSource.CLOUD) {
            null
        } else {
            val statusResult=teslamateRepository.getCarStatus(vehicle.remoteApiCarId)
            ensureScope()
            when(statusResult) {
                is ApiResult.Success -> statusResult.data.status.tpmsDetails
                is ApiResult.Error -> null
            }
        }
        val profile=settingsDataStore.getTpmsAlertProfile(historyCarId)
        ensureScope()
        val executionNow=System.currentTimeMillis()
        val rule=changeRuleStore.observe(historyCarId).first()
        ensureScope()
        for(candidate in verifiedTpmsSamples(historyCarId,details,executionNow)) {
            ensureScope()
            admitTpmsObservation(historyCarId,candidate,rule,tpmsHistoryRepository,changeRuleStore,::ensureScope)
        }
        for(observation in verifiedTpmsWarnings(details,executionNow)) {
            ensureScope()
            changeRuleStore.rememberVerifiedWarning(historyCarId,observation.details,observation.observedAt)
        }

        suspend fun process(evidence:com.matelink.data.api.models.TpmsDetails?,at:Long,defer:Boolean):TpmsStateChangeClaimResult =
            processSuccessfulTpmsStatus(
                carId=historyCarId,carName=carName,tpmsDetails=evidence,observedAt=at,profile=profile,
                saveObservation={ _ -> },
                // The 7/30-day trend is a query window, not permission to
                // delete the owner's only verified pressure history.
                pruneOlderThan90Days={ _, _ -> ensureScope() },
                detectTeslaStateChange={ _, d -> tpmsStateRepository.detectStateChangeForHistoryCarId(historyCarId,d) },
                updateTeslaState={ _, d -> tpmsStateRepository.updateStateForHistoryCarId(historyCarId,d) },
                resetCustomState=tpmsCustomAlertStateStore::resetForProfile,
                claimCustomAlerts=tpmsCustomAlertStateStore::claimAlerts,
                commitCustomAlert=tpmsCustomAlertStateStore::commitClaim,
                releaseCustomAlert=tpmsCustomAlertStateStore::releaseClaim,
                claimTeslaStateChange={ _, d, now -> tpmsStateRepository.claimStateChangeForHistoryCarId(historyCarId,d,now) },
                commitTeslaStateChange={ _, c -> tpmsStateRepository.commitStateChangeForHistoryCarId(historyCarId,c) },
                releaseTeslaStateChange={ _, c -> tpmsStateRepository.releaseStateChangeForHistoryCarId(historyCarId,c) },
                notifyTesla=::showNotification,
                notifyCustom={ id, _, alert -> tpmsTrendNotificationManager.showCustomAlert(id,alert) },
                executionNow=executionNow,deferCustomAlerts=defer,ensureCurrent=::ensureScope,
                validateCustomClaim=tpmsCustomAlertStateStore::isClaimCurrent,
                validateTeslaClaim=tpmsStateRepository::isClaimCurrentForHistoryCarId,
                ensureSourceCurrent=::ensureScopeNow
            )

        // Merge the latest independent warning receipts before notification. Each
        // nullable wheel retains its own metadata, including explicit false.
        val warning=changeRuleStore.verifiedWarningSnapshot(historyCarId,executionNow)
        ensureScope()
        val warningTimes=verifiedTpmsWarnings(warning,executionNow)
        val teslaResult=if(warningTimes.isNotEmpty()) process(warning,warningTimes.last().observedAt,true)
            else TpmsStateChangeClaimResult.NoTransition
        val defer=teslaResult !is TpmsStateChangeClaimResult.NoTransition
        val receipts=changeRuleStore.verifiedPressureReceipts(historyCarId,executionNow)
        ensureScope()
        for(sample in receipts) {
            ensureScope()
            if (!tpmsHistoryRepository.hasMatchingVerifiedCollectorReceipt(historyCarId,sample)) continue
            ensureScope()
            val time=java.time.Instant.ofEpochMilli(sample.observedAt).toString()
            process(com.matelink.data.api.models.TpmsDetails(
                pressureFl=sample.pressureFl,pressureFr=sample.pressureFr,pressureRl=sample.pressureRl,pressureRr=sample.pressureRr,
                pressureUnit="bar",pressureSource="telemetry_mqtt",pressureTimeBasis="collector_received_at",
                pressureObservedAtFl=time.takeIf { sample.pressureFl!=null },pressureObservedAtFr=time.takeIf { sample.pressureFr!=null },
                pressureObservedAtRl=time.takeIf { sample.pressureRl!=null },pressureObservedAtRr=time.takeIf { sample.pressureRr!=null }
            ),sample.observedAt,defer)
        }
        // The pending store carries its own qualified original receipt. No new
        // Room observation is created by this permission/restart recovery pass.
        process(null,executionNow,defer)
        ensureScope()
        if(changeRuleStore.observe(historyCarId).first().enabled) {
            drainTpmsPressureChanges(historyCarId,tpmsHistoryRepository,changeRuleStore,executionNow,
                ::ensureScope,::ensureScopeNow) { change -> tpmsTrendNotificationManager.showPressureChange(historyCarId,change) }
        }
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
    releaseTeslaStateChange: (suspend (Int, TpmsStateChangeClaim) -> Unit)? = null,
    executionNow: Long = System.currentTimeMillis(),
    deferCustomAlerts: Boolean = false,
    ensureCurrent: suspend () -> Unit = { currentCoroutineContext().ensureActive() },
    validateCustomClaim:suspend (Int,TpmsCustomAlertClaim)->Boolean={ _, _ -> true },
    validateTeslaClaim:suspend (Int,TpmsStateChangeClaim)->Boolean={ _, _ -> true },
    ensureSourceCurrent: () -> Unit = {}
): TpmsStateChangeClaimResult {
    ensureCurrent()
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

    val hasCompleteTeslaObservation = tpmsDetails.hasCompleteSoftWarningFields() ||
        (claimTeslaStateChange != null && verifiedTpmsWarnings(tpmsDetails,executionNow).isNotEmpty())
    ensureCurrent()
    val teslaClaimResult = if (hasCompleteTeslaObservation) {
        claimTeslaStateChange?.invoke(carId, tpmsDetails, executionNow)
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
    val qualified = verifiedTpmsSamples(carId,tpmsDetails,executionNow).filter { it.observedAt == observedAt }
    val customObservations = profile
        ?.takeIf { it.isEnabledAndValid() }
        ?.let { TpmsCustomAlertEvaluator().observe(it, tpmsDetails).mapValues { (wheel,observation) ->
            val proven = qualified.any { sample -> when(wheel) {
                TirePosition.FL -> sample.pressureFl; TirePosition.FR -> sample.pressureFr
                TirePosition.RL -> sample.pressureRl; TirePosition.RR -> sample.pressureRr
            } != null }
            observation.copy(observedAt=observedAt,qualifiedReceipt=proven)
        } }
        ?: emptyMap()
    val customClaims = claimCustomAlerts(
        carId,
        profileFingerprint,
        customObservations,
        deferCustomAlerts || teslaClaimResult !is TpmsStateChangeClaimResult.NoTransition,
        executionNow
    )

    if (teslaClaimResult is TpmsStateChangeClaimResult.Claimed && teslaClaim != null) {
        try {
            ensureCurrent()
            if(!validateTeslaClaim(carId,teslaClaim)) return teslaClaimResult
            ensureSourceCurrent()
            currentCoroutineContext().ensureActive()
            notifyTesla(carId, carName, teslaClaim.change)
            ensureCurrent()
            commitTeslaStateChange?.invoke(carId, teslaClaim)
                ?: updateTeslaState(carId, tpmsDetails)
        } catch (cancelled: CancellationException) {
            // A superseded car/account job is not a failed notification delivery.
            // Always relinquish the in-flight claim and propagate cancellation.
            try {
                withContext(NonCancellable) { releaseTeslaStateChange?.invoke(carId, teslaClaim) }
            } catch (_: Exception) { /* lease expiry remains an independent recovery path */ }
            throw cancelled
        } catch (_: Exception) {
            releaseTeslaStateChange?.invoke(carId, teslaClaim)
        }
    } else {
        for ((index,claim) in customClaims.withIndex()) {
            val alert = TpmsCustomAlert(
                wheel = claim.wheel,
                state = claim.state,
                observedPressureBar = claim.observedPressureBar,
                thresholdBar = claim.thresholdBar
            )
            try {
                ensureCurrent()
                if(!validateCustomClaim(carId,claim)) continue
                ensureSourceCurrent()
                currentCoroutineContext().ensureActive()
                notifyCustom(carId, carName, alert)
                ensureCurrent()
                commitCustomAlert(carId, claim)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    customClaims.drop(index).forEach { unconsumed ->
                        try { releaseCustomAlert(carId, unconsumed) }
                        catch (_:Exception) { /* token lease expiry remains a recovery path */ }
                    }
                }
                throw cancelled
            } catch (_: Exception) {
                releaseCustomAlert(carId, claim)
            }
        }
    }
    return teslaClaimResult
}

private fun com.matelink.data.api.models.TpmsDetails.toLegacyTpmsState() =
    com.matelink.data.local.TpmsState(
        warningFl = warningFl == true,
        warningFr = warningFr == true,
        warningRl = warningRl == true,
        warningRr = warningRr == true
    )

private const val DISABLED_PROFILE_FINGERPRINT = "disabled"
private val tpmsAdmissionMutex = Mutex()

/** Admission seam shared by the worker and fault-injection tests. */
internal suspend fun admitTpmsObservation(
    historyCarId: Int,
    candidate: TpmsPressureSample,
    rule: com.matelink.domain.analytics.TpmsChangeRule,
    history: TpmsHistoryRepository,
    changeRules: com.matelink.data.local.TpmsChangeRuleStore,
    ensureCurrent: suspend () -> Unit = { currentCoroutineContext().ensureActive() },
    executionNow: Long = System.currentTimeMillis()
) {
    require(candidate.carId==historyCarId)
    if(!com.matelink.data.local.validReceiptTime(candidate.observedAt,executionNow)) return
    tpmsAdmissionMutex.withLock {
    ensureCurrent(); changeRules.pruneExpiredRecovery(historyCarId,executionNow)
    recoverTpmsAdmissionIntentsLocked(historyCarId,history,changeRules,ensureCurrent,executionNow)
    ensureCurrent()
    val novel=history.novelVerifiedCandidateForHistoryCarId(historyCarId,candidate) ?: return
    ensureCurrent()
    val previous=history.latestVerifiedPerWheel(historyCarId,novel.observedAt,rule.windowHours)
    ensureCurrent()
    val intent=changeRules.beginIntent(com.matelink.data.local.TpmsAdmissionIntent(novel,
        com.matelink.domain.analytics.pressureChangesAcrossWheels(previous,novel,rule),rule))
    if(intent==null) {
        // A late older reading may populate history but cannot rearm an alert
        // behind the fixed wheel watermark. No active intent is discarded.
        ensureCurrent(); history.insertNovelVerifiedForHistoryCarId(historyCarId,novel)
        return
    }
    ensureCurrent()
    history.insertNovelVerifiedForHistoryCarId(historyCarId,intent.sample)
    ensureCurrent()
    if(history.hasMatchingVerifiedCollectorReceipt(historyCarId,intent.sample)) {
        ensureCurrent(); changeRules.promoteMatchedIntent(intent)
    }
    }
}

internal suspend fun recoverTpmsAdmissionIntents(
    historyCarId:Int,history:TpmsHistoryRepository,changeRules:com.matelink.data.local.TpmsChangeRuleStore,
    ensureCurrent:suspend ()->Unit={ currentCoroutineContext().ensureActive() },
    executionNow:Long=System.currentTimeMillis()
) {
    tpmsAdmissionMutex.withLock {
        ensureCurrent(); changeRules.pruneExpiredRecovery(historyCarId,executionNow)
        recoverTpmsAdmissionIntentsLocked(historyCarId,history,changeRules,ensureCurrent,executionNow)
    }
}

private suspend fun recoverTpmsAdmissionIntentsLocked(
    historyCarId:Int,history:TpmsHistoryRepository,changeRules:com.matelink.data.local.TpmsChangeRuleStore,
    ensureCurrent:suspend ()->Unit,executionNow:Long
) {
    ensureCurrent()
    val intents=changeRules.recoveryIntents(historyCarId)
    for(intent in intents) {
        if(!com.matelink.data.local.validReceiptTime(intent.sample.observedAt,executionNow)) continue
        ensureCurrent(); history.insertNovelVerifiedForHistoryCarId(historyCarId,intent.sample)
        ensureCurrent()
        if(history.hasMatchingVerifiedCollectorReceipt(historyCarId,intent.sample)) {
            ensureCurrent(); changeRules.promoteMatchedIntent(intent)
        }
    }
}

/** Token-claimed delivery prevents overlapping periodic/immediate workers double-sending. */
internal suspend fun drainTpmsPressureChanges(
    historyCarId:Int,history:TpmsHistoryRepository,changeRules:com.matelink.data.local.TpmsChangeRuleStore,
    executionNow:Long,ensureCurrent:suspend ()->Unit={ currentCoroutineContext().ensureActive() },
    ensureSourceCurrent: () -> Unit = {},
    notify:suspend (com.matelink.domain.analytics.TpmsPressureChange)->Unit
) {
    ensureCurrent()
    for(change in changeRules.pending(historyCarId)) {
        ensureCurrent()
        if(!history.verifiesChange(historyCarId,change)) continue
        ensureCurrent()
        val claim=changeRules.claimPending(historyCarId,change,executionNow) ?: continue
        try {
            ensureCurrent()
            if(!changeRules.isClaimCurrent(historyCarId,claim)) continue
            ensureSourceCurrent()
            currentCoroutineContext().ensureActive()
            notify(change)
            ensureCurrent(); changeRules.acknowledgeClaim(historyCarId,claim)
        } catch(cancelled:CancellationException) {
            try {
                withContext(NonCancellable) { changeRules.releaseClaim(historyCarId,claim) }
            } catch(_:Exception) { /* Preserve cancellation; token lease expiry recovers a failed release. */ }
            throw cancelled
        } catch(_:NotificationDeliveryUnavailableException) {
            changeRules.releaseClaim(historyCarId,claim)
            return
        } catch(failed:Exception) {
            changeRules.releaseClaim(historyCarId,claim)
            throw failed
        }
    }
}

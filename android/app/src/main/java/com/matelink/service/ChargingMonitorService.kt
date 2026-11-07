package com.matelink.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.matelink.R
import com.matelink.data.local.ChargeSessionStateDataStore
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.repository.ApiResult
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.sync.BoundNotificationSource
import com.matelink.data.sync.BoundNotificationSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import com.matelink.notification.ChargingNotificationManager
import com.matelink.notification.chargingNotificationId
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service for monitoring charging sessions.
 *
 * Runs while the car is charging to provide real-time notification updates.
 * Uses a foreground notification (the charging notification itself) to stay alive.
 */
@AndroidEntryPoint
class ChargingMonitorService : Service() {

    companion object {
        private const val TAG = "ChargingMonitorService"
        private const val UPDATE_INTERVAL_MS = 30_000L  // 30 seconds
        private const val INITIAL_NOTIFICATION_ID = 3999  // Temporary ID for initial foreground

        fun start(context: Context) {
            val intent = Intent(context, ChargingMonitorService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ChargingMonitorService::class.java)
            context.stopService(intent)
        }
    }

    @Inject lateinit var boundSource: BoundNotificationSource
    @Inject lateinit var settingsDataStore: SettingsDataStore
    @Inject lateinit var chargingNotificationManager: ChargingNotificationManager
    @Inject lateinit var chargeSessionStateDataStore: ChargeSessionStateDataStore
    @Inject lateinit var vehicleContextRepository: VehicleContextRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var boundSession: BoundNotificationSession? = null
    private var consecutiveFailures = 0
    private val maxConsecutiveFailures = 3
    private var isMonitoring = false
    private val activeNotificationCarIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        serviceScope.launch {
            vehicleContextRepository.historyScopeChanges.collect { scope ->
                val bound = boundSession
                if (bound != null && scope != bound.scope) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isMonitoring) {
            Log.d(TAG, "Service already monitoring, ignoring start command")
            return START_STICKY
        }

        Log.d(TAG, "Service started")

        // Must call startForeground immediately (within 5 seconds)
        startForegroundImmediately()

        // Then start the monitoring loop
        startMonitoring()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed, cancelling ${activeNotificationCarIds.size} notifications")
        isMonitoring = false
        monitorJob?.cancel()

        // Explicitly cancel all charging notifications so they don't linger
        // when the service stops (e.g., after consecutive API failures due to VPN loss)
        for (carId in activeNotificationCarIds) {
            chargingNotificationManager.cancelNotification(carId)
        }
        activeNotificationCarIds.clear()

        // Also cancel the placeholder notification in case it was never replaced
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(INITIAL_NOTIFICATION_ID)

        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Start foreground immediately with a placeholder notification.
     * This must be called within 5 seconds of startForegroundService().
     */
    private fun startForegroundImmediately() {
        // Ensure channel exists
        chargingNotificationManager.ensureChannelExists()

        val notification = NotificationCompat.Builder(this, ChargingNotificationManager.CHANNEL_ID)
            .setContentTitle(getString(R.string.charging_notification_title))
            .setContentText(getString(R.string.charging_notification_loading))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    INITIAL_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(INITIAL_NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Started foreground with placeholder notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
            stopSelf()
        }
    }

    private fun startMonitoring() {
        monitorJob?.cancel()
        isMonitoring = true
        monitorJob = serviceScope.launch {
            // Small delay to let things settle after service start
            delay(500)

            // Initial check
            val initialResult = performChargingCheck()
            if (!initialResult) {
                consecutiveFailures++
                Log.d(TAG, "Initial check failed, failure count: $consecutiveFailures")
            } else {
                consecutiveFailures = 0
            }

            // Continue monitoring
            while (isActive) {
                delay(UPDATE_INTERVAL_MS)

                val stillCharging = performChargingCheck()
                if (stillCharging) {
                    consecutiveFailures = 0
                    Log.d(TAG, "Updated charging notification")
                } else {
                    consecutiveFailures++
                    Log.d(TAG, "Check returned no charging, failure count: $consecutiveFailures")

                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        Log.d(TAG, "Too many failures, stopping service")
                        stopSelf()
                        break
                    }
                }
            }
        }
    }

    /**
     * Update the foreground notification, falling back to direct notification on failure.
     */
    private fun updateForegroundNotification(
        notificationId: Int,
        localHistoryCarId: Int,
        notification: Notification,
        car: com.matelink.data.api.models.CarData,
        status: com.matelink.data.api.models.CarStatus,
        liveChargeAvailable: Boolean,
        dcFinishedPluggedIn: Boolean = false
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update foreground notification", e)
            chargingNotificationManager.showChargingNotification(
                car, localHistoryCarId, status, liveChargeAvailable,
                dcFinishedPluggedIn = dcFinishedPluggedIn,
                chronometerBaseMs = status.stateSinceEpochMs
            )
        }
    }

    /**
     * Check charging status and update notification.
     * Returns true if any car is charging, false otherwise.
     */
    private suspend fun performChargingCheck(): Boolean {
        try {
            val bound = boundSession ?: boundSource.openSelfHosted().also { boundSession = it }
            bound.ensureCurrent()
            val vehicles = bound.vehicles()
            var anyCharging = false
            val seen = mutableSetOf<Int>()
            for (vehicle in vehicles) {
                val status = bound.status(vehicle)
                val local = vehicle.context.localHistoryCarId
                val car = vehicle.car
                suspend fun guard() = bound.ensureCurrent(vehicle)
                guard()
                seen += local
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
                when {
                    status.isCharging || finishedDc -> {
                        anyCharging = true
                        val available = if (status.isCharging) bound.currentChargeAvailable(vehicle) else false
                        guard()
                        val notification = chargingNotificationManager.buildNotification(car, status, available,
                            dcFinishedPluggedIn = finishedDc, chronometerBaseMs = status.stateSinceEpochMs,
                            localHistoryCarId = local)
                        guard()
                        activeNotificationCarIds.add(local)
                        updateForegroundNotification(chargingNotificationId(local), local, notification,
                            car, status, available, dcFinishedPluggedIn = finishedDc)
                        guard()
                    }
                    status.pluggedIn == false || status.chargingState != null -> {
                        guard()
                        activeNotificationCarIds.remove(local)
                        chargingNotificationManager.cancelNotification(local)
                    }
                    else -> Unit // Unknown is not a stop/unplug observation.
                }
            }
            bound.ensureCurrent()
            for (local in activeNotificationCarIds.toList().filter { it !in seen }) {
                chargingNotificationManager.cancelNotification(local)
                activeNotificationCarIds.remove(local)
            }
            return anyCharging
        } catch (e: CancellationException) { throw e }
          catch (_: HistoryIdentityUnavailableException) {
            // In particular, never restart this loop against Fleet after a
            // self-hosted → Cloud switch or ABA of the selected source.
            stopSelf()
            return false
        } catch (_: Exception) {
            Log.w(TAG, "event=charging_monitor_read_unavailable")
            return false
        }
    }
}

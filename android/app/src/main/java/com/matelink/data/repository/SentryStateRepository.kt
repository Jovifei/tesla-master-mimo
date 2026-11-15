package com.matelink.data.repository

import com.matelink.data.local.SentryState
import com.matelink.data.api.models.CarStatus
import com.matelink.data.sync.isNewSentryObservation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.matelink.data.local.SentryStateDataStore
import com.matelink.data.local.HistoryCarIdResolver
import com.matelink.data.local.LegacyHistoryCarIdResolver
import com.matelink.data.local.dao.SentryAlertLogDao
import com.matelink.data.local.entity.SentryAlertLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of processing a sentry status update.
 */
sealed class SentryEvent {
    /**
     * A sentry alert was detected. [count] is the running total for this session.
     * [shouldNotify] is true for the first event in a debounce window (triggers sound/heads-up);
     * false for subsequent events within the window (counter still increments, notification
     * updates silently).
     */
    data class AlertDetected(val count: Int, val shouldNotify: Boolean) : SentryEvent()

    /** Sentry mode turned off; session counters have been reset. */
    data object SessionEnded : SentryEvent()
}

/**
 * Business logic layer over [SentryStateDataStore].
 *
 * A 65-second debounce window (just over the 1-minute screen-on duration per
 * sentry event) gates all side effects: counter increment, history logging,
 * and notification alerting. Duplicate polls within the window are ignored.
 */
@Singleton
class SentryStateRepository @Inject constructor(
    private val dataStore: SentryStateDataStore,
    private val alertLogDao: SentryAlertLogDao,
    private val vehicleContextRepository: HistoryCarIdResolver
) {
    constructor(dataStore: SentryStateDataStore, alertLogDao: SentryAlertLogDao) :
        this(dataStore, alertLogDao, LegacyHistoryCarIdResolver)
    private suspend fun historyId(remoteApiCarId: Int) =
        vehicleContextRepository.requireLocalHistoryCarId(remoteApiCarId)
    companion object {
        /** Debounce window for all event processing (counting, logging, alerting).
         *  Slightly over 60s because the car screen stays on for exactly 1 minute per event. */
        private const val NOTIFY_DEBOUNCE_MS = 65_000L
        private val observationMutex = Mutex()
    }

    /**
     * Process a status update for a car.
     *
     * @param carId The car ID
     * @param sentryMode Whether sentry mode is currently active
     * @param isSentryAlerted Whether the center display is showing the sentry warning (state "7")
     * @return A [SentryEvent] if something noteworthy happened, null otherwise
     */
    suspend fun processStatus(
        carId: Int, sentryMode: Boolean?, isSentryAlerted: Boolean,
        latitude: Double? = null, longitude: Double? = null, geofence: String? = null
    ): SentryEvent? {
        if (sentryMode == null) return null
        return processLocalStatus(historyId(carId), sentryMode, isSentryAlerted, latitude, longitude, geofence)
    }

    /** Caller already holds the verified local account/source/vehicle namespace. */
    internal suspend fun processLocalStatus(
        historyCarId: Int, sentryMode: Boolean?, isSentryAlerted: Boolean,
        latitude: Double? = null, longitude: Double? = null, geofence: String? = null,
        ensureCurrent: suspend () -> Unit = {}
    ): SentryEvent? = observationMutex.withLock {
        if (sentryMode == null) return@withLock null
        ensureCurrent()
        val state = dataStore.getState(historyCarId)
        ensureCurrent()
        val now = System.currentTimeMillis()
        val (next, event) = transition(state, sentryMode, isSentryAlerted, now)
        dataStore.saveState(historyCarId, next, ensureCurrent)
        logEvent(historyCarId, next, event, now, latitude, longitude, geofence, ensureCurrent)
        event
    }

    /** Claim and persist one already-qualified local observation, never a Fleet request.
     * The counter, display latch and watermark share one atomic DataStore edit.
     * Dispatch stays under the same mutex so older observations cannot notify last.
     */
    internal suspend fun consumeObservation(
        historyCarId: Int, status: CarStatus, observedAt: Long,
        legacyWatermark: Long = 0L, ensureCurrent: suspend () -> Unit = {},
        onAdmitted: suspend (SentryEvent?) -> Unit = {}
    ): Boolean = observationMutex.withLock {
        ensureCurrent()
        val state = dataStore.getState(historyCarId)
        ensureCurrent()
        if (observedAt <= maxOf(state.lastObservationAt, legacyWatermark)) return@withLock false
        // A pre-latch active session with recorded events already observed 7.
        // Do not invent another first event merely because the app was upgraded.
        val previous = state.lastObservedDisplay ?:
            "7".takeIf { state.sentryActive && state.eventCount > 0 }
        val now = System.currentTimeMillis()
        val (changed, event) = transition(state, status.sentryMode,
            isNewSentryObservation(previous, status), now)
        val display = status.centerDisplayState?.toIntOrNull()?.takeIf { it >= 0 }?.toString()
        val nextDisplay = when {
            status.sentryMode == false -> null
            status.sentryMode == true && display != null -> display
            else -> previous
        }
        val next = changed.copy(lastObservedDisplay = nextDisplay, lastObservationAt = observedAt)
        dataStore.saveState(historyCarId, next, ensureCurrent)
        logEvent(historyCarId, next, event, now, status.latitude, status.longitude, status.geofence, ensureCurrent)
        ensureCurrent()
        onAdmitted(event)
        ensureCurrent()
        true
    }

    private fun transition(state: SentryState, mode: Boolean?, alert: Boolean,
        now: Long): Pair<SentryState, SentryEvent?> {
        if (mode == null) return state to null
        if (!mode) return state.copy(sentryActive = false, eventCount = 0,
            lastEventAt = 0, sessionStartedAt = 0, lastObservedDisplay = null) to
            SentryEvent.SessionEnded.takeIf { state.sentryActive }
        val active = if (state.sentryActive) state else state.copy(sentryActive = true, sessionStartedAt = now)
        if (!alert || now - active.lastEventAt < NOTIFY_DEBOUNCE_MS) return active to null
        val next = active.copy(eventCount = Math.addExact(active.eventCount, 1), lastEventAt = now)
        return next to SentryEvent.AlertDetected(next.eventCount, true)
    }

    private suspend fun logEvent(local: Int, state: SentryState, event: SentryEvent?, now: Long,
        latitude: Double?, longitude: Double?, geofence: String?, ensureCurrent: suspend () -> Unit) {
        if (event !is SentryEvent.AlertDetected) return
        ensureCurrent()
        val coordinates = com.matelink.domain.telemetry.usableVehicleCoordinates(latitude, longitude)
        alertLogDao.insert(SentryAlertLog(carId = local, detectedAt = now,
            sessionStartedAt = state.sessionStartedAt, latitude = coordinates?.first, longitude = coordinates?.second,
            address = geofence?.ifBlank { null }))
        ensureCurrent()
    }

    /**
     * Get the current event count for a car's sentry session.
     */
    suspend fun getEventCount(carId: Int): Int {
        return dataStore.getState(historyId(carId)).eventCount
    }

    /**
     * Force-increment the event count, bypassing debounce.
     * Used for debug simulation only.
     */
    suspend fun forceIncrementEventCount(carId: Int, latitude: Double? = null, longitude: Double? = null, geofence: String? = null): Int {
        // Ensure sentry is marked active with a session start time
        val historyCarId = historyId(carId)
        val state = dataStore.getState(historyCarId)
        val sessionStartedAt: Long
        if (!state.sentryActive) {
            val now = System.currentTimeMillis()
            sessionStartedAt = now
            dataStore.saveState(historyCarId, state.copy(sentryActive = true, sessionStartedAt = now))
        } else {
            sessionStartedAt = state.sessionStartedAt
        }
        val updated = dataStore.incrementEventCount(historyCarId)
        // Always log for debug simulation (bypasses debounce like the notification does)
        alertLogDao.insert(
            SentryAlertLog(
                carId = historyCarId,
                detectedAt = System.currentTimeMillis(),
                sessionStartedAt = sessionStartedAt,
                latitude = latitude,
                longitude = longitude,
                address = geofence
            )
        )
        return updated.eventCount
    }
}

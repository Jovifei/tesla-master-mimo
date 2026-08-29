package com.matelink.data.sync

import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.local.TirePosition
import com.matelink.data.local.TpmsAlertProfile
import com.matelink.data.local.TpmsCustomAlertClaim
import com.matelink.data.local.TpmsCustomPendingAlert
import com.matelink.data.local.TpmsCustomPressureState
import com.matelink.data.local.TpmsCustomWheelObservation
import com.matelink.data.local.TpmsCustomWheelState
import com.matelink.data.local.dao.TpmsPressureSampleDao
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.data.repository.TpmsHistoryRepository
import com.matelink.data.repository.TpmsStateChange
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TpmsPressureWorkerTest {
    private val profile = TpmsAlertProfile(2.9, 2.6, 3.4, enabled = true)

    @Test
    fun successfulStatusPersistsFiniteSnapshotTemperatureAndPrunesNinetyDayHistory() = runBlocking {
        val saved = mutableListOf<TpmsPressureSample>()
        val pruned = mutableListOf<Pair<Int, Long>>()

        processSuccessfulTpmsStatus(
            carId = 4,
            carName = "Elysa",
            tpmsDetails = TpmsDetails(pressureFl = 2.7, pressureRr = 3.0),
            outsideTempC = 17.5,
            observedAt = 1234L,
            profile = null,
            saveObservation = { saved += it },
            pruneOlderThan90Days = { carId, now -> pruned += carId to now },
            detectTeslaStateChange = { _, _ -> null },
            updateTeslaState = { _, _ -> },
            resetCustomState = { _, _ -> },
            claimCustomAlerts = { _, _, _, _, _ -> emptyList() },
            commitCustomAlert = { _, _ -> },
            releaseCustomAlert = { _, _ -> },
            notifyTesla = { _, _, _ -> },
            notifyCustom = { _, _, _ -> }
        )

        assertEquals(listOf(TpmsPressureSample(4, 1234L, 2.7, null, null, 3.0, 17.5)), saved)
        assertEquals(listOf(4 to 1234L), pruned)
    }

    @Test
    fun missingTpmsSavesNullableSampleButDoesNotTouchTeslaWarningState() = runBlocking {
        val saved = mutableListOf<TpmsPressureSample>()
        var detected = 0
        var updated = 0
        var teslaNotifications = 0

        processSuccessfulTpmsStatus(
            carId = 4,
            carName = "Elysa",
            tpmsDetails = null,
            outsideTempC = 18.0,
            observedAt = 1234L,
            profile = null,
            saveObservation = { saved += it },
            pruneOlderThan90Days = { _, _ -> },
            detectTeslaStateChange = { _, _ -> detected++; null },
            updateTeslaState = { _, _ -> updated++ },
            resetCustomState = { _, _ -> },
            claimCustomAlerts = { _, _, _, _, _ -> emptyList() },
            commitCustomAlert = { _, _ -> },
            releaseCustomAlert = { _, _ -> },
            notifyTesla = { _, _, _ -> teslaNotifications++ },
            notifyCustom = { _, _, _ -> }
        )

        assertEquals(listOf(TpmsPressureSample(4, 1234L, outsideTempC = 18.0)), saved)
        assertEquals(0, detected)
        assertEquals(0, updated)
        assertEquals(0, teslaNotifications)
    }

    @Test
    fun unobservedSoftWarningFieldsDoNotTouchTeslaStateButCustomPressureStillRuns() = runBlocking {
        var detected = 0
        var updated = 0
        var customNotifications = 0

        processSuccessfulTpmsStatus(
            carId = 4,
            carName = "Elysa",
            tpmsDetails = TpmsDetails(pressureFl = 2.5),
            observedAt = 1234L,
            profile = profile,
            saveObservation = {},
            pruneOlderThan90Days = { _, _ -> },
            detectTeslaStateChange = { _, _ -> detected++; null },
            updateTeslaState = { _, _ -> updated++ },
            resetCustomState = { _, _ -> },
            claimCustomAlerts = { _, _, _, _, _ ->
                listOf(TpmsCustomAlertClaim(TirePosition.FL, TpmsCustomPressureState.LOW, 2.5, 2.6))
            },
            commitCustomAlert = { _, _ -> },
            releaseCustomAlert = { _, _ -> },
            notifyTesla = { _, _, _ -> },
            notifyCustom = { _, _, _ -> customNotifications++ }
        )

        assertEquals(0, detected)
        assertEquals(0, updated)
        assertEquals(1, customNotifications)
    }

    @Test
    fun explicitAllFalseSoftWarningFieldsStillUseTeslaStatePath() = runBlocking {
        var detected = 0
        var updated = 0

        processSuccessfulTpmsStatus(
            carId = 4,
            carName = "Elysa",
            tpmsDetails = TpmsDetails(
                warningFl = false,
                warningFr = false,
                warningRl = false,
                warningRr = false
            ),
            observedAt = 1234L,
            profile = null,
            saveObservation = {},
            pruneOlderThan90Days = { _, _ -> },
            detectTeslaStateChange = { _, _ -> detected++; null },
            updateTeslaState = { _, _ -> updated++ },
            resetCustomState = { _, _ -> },
            claimCustomAlerts = { _, _, _, _, _ -> emptyList() },
            commitCustomAlert = { _, _ -> },
            releaseCustomAlert = { _, _ -> },
            notifyTesla = { _, _, _ -> },
            notifyCustom = { _, _, _ -> }
        )

        assertEquals(1, detected)
        assertEquals(1, updated)
    }

    @Test
    fun partialKnownSoftWarningFieldsDoNotClearPriorTeslaWarning() = runBlocking {
        var detected = 0
        var updated = 0
        var saved = 0

        processSuccessfulTpmsStatus(
            carId = 4,
            carName = "Elysa",
            tpmsDetails = TpmsDetails(warningFl = false),
            observedAt = 1234L,
            profile = null,
            saveObservation = { saved++ },
            pruneOlderThan90Days = { _, _ -> },
            detectTeslaStateChange = { _, _ -> detected++; TpmsStateChange.WarningCleared },
            updateTeslaState = { _, _ -> updated++ },
            resetCustomState = { _, _ -> },
            claimCustomAlerts = { _, _, _, _, _ -> emptyList() },
            commitCustomAlert = { _, _ -> },
            releaseCustomAlert = { _, _ -> },
            notifyTesla = { _, _, _ -> },
            notifyCustom = { _, _, _ -> }
        )

        assertEquals(1, saved)
        assertEquals(0, detected)
        assertEquals(0, updated)
    }

    @Test
    fun failedTeslaNotificationDoesNotCommitStateBeforeRetry() = runBlocking {
        var attempts = 0
        var updates = 0
        val transition = TpmsStateChange.WarningStarted(listOf(TirePosition.FL))

        fun runOnce() {
            runBlocking {
                processSuccessfulTpmsStatus(
                    carId = 4,
                    carName = "Elysa",
                    tpmsDetails = TpmsDetails(
                        warningFl = true,
                        warningFr = false,
                        warningRl = false,
                        warningRr = false
                    ),
                    observedAt = 1234L,
                    profile = null,
                    saveObservation = {},
                    pruneOlderThan90Days = { _, _ -> },
                    detectTeslaStateChange = { _, _ -> transition },
                    updateTeslaState = { _, _ -> updates++ },
                    resetCustomState = { _, _ -> },
                    claimCustomAlerts = { _, _, _, _, _ -> emptyList() },
                    commitCustomAlert = { _, _ -> },
                    releaseCustomAlert = { _, _ -> },
                    notifyTesla = { _, _, _ ->
                        attempts++
                        if (attempts == 1) error("synthetic Tesla notification failure")
                    },
                    notifyCustom = { _, _, _ -> }
                )
            }
        }

        runCatching { runOnce() }
        runOnce()

        assertEquals(2, attempts)
        assertEquals(1, updates)
    }

    @Test
    fun debugSchedulingAppendsNextRunAfterCurrentWork() {
        val source = File("src/main/java/com/matelink/data/sync/TpmsPressureWorker.kt").readText()
        assertTrue(source.contains("ExistingWorkPolicy.APPEND_OR_REPLACE"))
    }

    @Test
    fun TeslaTransitionDefersCustomBreachUntilNextRunAndThenDeliversOnce() = runBlocking {
        val store = AtomicCustomClaimStore()
        val teslaState = TeslaWarningState()
        val events = mutableListOf<String>()
        val low = TpmsDetails(
            pressureFl = 2.5,
            warningFl = true,
            warningFr = false,
            warningRl = false,
            warningRr = false
        )

        processWithStore(
            store,
            low,
            teslaState,
            onTesla = { events += "tesla" },
            onCustom = { events += "custom" }
        )
        assertEquals(listOf("tesla"), events)
        assertTrue(store.state(TirePosition.FL).pending != null)

        processWithStore(
            store,
            low,
            teslaState,
            onTesla = { events += "tesla" },
            onCustom = { events += "custom" }
        )
        assertEquals(listOf("tesla", "custom"), events)
        processWithStore(
            store,
            low,
            teslaState,
            onTesla = { events += "tesla" },
            onCustom = { events += "custom" }
        )

        assertEquals(listOf("tesla", "custom"), events)
        assertEquals(null, store.state(TirePosition.FL).pending)
    }

    @Test
    fun liveTeslaClaimDefersCustomBreachWithoutDeliveringEitherNotification() = runBlocking {
        var customDefer = false
        var teslaNotifications = 0
        var customNotifications = 0
        val low = TpmsDetails(
            pressureFl = 2.5,
            warningFl = true,
            warningFr = false,
            warningRl = false,
            warningRr = false
        )

        processSuccessfulTpmsStatus(
            carId = 4,
            carName = "Elysa",
            tpmsDetails = low,
            observedAt = 1234L,
            profile = profile,
            saveObservation = {},
            pruneOlderThan90Days = { _, _ -> },
            detectTeslaStateChange = { _, _ -> error("legacy Tesla detector must not run") },
            updateTeslaState = { _, _ -> error("live Tesla claim must not update state") },
            resetCustomState = { _, _ -> },
            claimCustomAlerts = { _, _, _, defer, _ ->
                customDefer = defer
                emptyList()
            },
            commitCustomAlert = { _, _ -> },
            releaseCustomAlert = { _, _ -> },
            notifyTesla = { _, _, _ -> teslaNotifications++ },
            notifyCustom = { _, _, _ -> customNotifications++ },
            claimTeslaStateChange = { _, _, _ ->
                com.matelink.data.repository.TpmsStateChangeClaimResult.InFlight
            }
        )

        assertTrue(customDefer)
        assertEquals(0, teslaNotifications)
        assertEquals(0, customNotifications)
    }

    @Test
    fun failedCustomNotificationLeavesPendingClaimForNextRun() = runBlocking {
        val store = AtomicCustomClaimStore()
        var attempts = 0

        processSuccessfulWithStore(store, TpmsDetails(pressureFl = 2.5), onNotify = {
            attempts++
            error("synthetic notification failure")
        })
        processSuccessfulWithStore(store, TpmsDetails(pressureFl = 2.5), onNotify = {
            attempts++
        })

        assertEquals(2, attempts)
        assertEquals(null, store.state(TirePosition.FL).pending)
    }

    @Test
    fun concurrentRunsClaimOneCustomNotificationPerCarAndWheel() = runBlocking {
        val store = AtomicCustomClaimStore()
        val first = async { processSuccessfulWithStore(store, TpmsDetails(pressureFl = 2.5), onNotify = {}) }
        val second = async { processSuccessfulWithStore(store, TpmsDetails(pressureFl = 2.5), onNotify = {}) }
        awaitAll(first, second)

        assertEquals(1, store.claimsCommitted)
    }

    @Test
    fun profileFingerprintChangeResetsStateAndReenablesEntryNotification() = runBlocking {
        val store = AtomicCustomClaimStore()
        processSuccessfulWithStore(store, TpmsDetails(pressureFl = 2.5), profile = profile, onNotify = {})
        processSuccessfulWithStore(
            store,
            TpmsDetails(pressureFl = 2.5),
            profile = profile.copy(lowBar = 2.55),
            onNotify = {}
        )

        assertEquals(2, store.claimsCommitted)
    }

    @Test
    fun allEmptyNormalizedSnapshotIsStillUpsertedForCoverage() = runBlocking {
        val dao = RecordingTpmsPressureSampleDao()
        TpmsHistoryRepository(dao).saveObservation(
            TpmsPressureSample(
                carId = 4,
                observedAt = 1234L,
                pressureFl = Double.NaN,
                pressureFr = Double.POSITIVE_INFINITY,
                pressureRl = Double.NEGATIVE_INFINITY,
                outsideTempC = Double.NaN
            )
        )

        assertEquals(listOf(TpmsPressureSample(4, 1234L)), dao.saved)
    }

    @Test
    fun workerLogsDoNotIncludeNamesBodiesExceptionsOrServerMessages() {
        val source = File("src/main/java/com/matelink/data/sync/TpmsPressureWorker.kt").readText()

        assertFalse(source.contains("carsResult.message"))
        assertFalse(source.contains("statusResult.message"))
        assertFalse(source.contains("Log.e(TAG, \"Error checking TPMS"))
        assertFalse(source.contains("Log.d(TAG, \"Showed TPMS notification for car \u0024carId: \u0024body\""))
    }

    private suspend fun processWithStore(
        store: AtomicCustomClaimStore,
        tpms: TpmsDetails,
        teslaState: TeslaWarningState,
        onTesla: () -> Unit,
        onCustom: () -> Unit
    ) = processSuccessfulWithStore(
        store,
        tpms,
        teslaState = teslaState,
        onNotify = {},
        onTesla = onTesla,
        onCustom = onCustom
    )

    private suspend fun processSuccessfulWithStore(
        store: AtomicCustomClaimStore,
        tpms: TpmsDetails,
        onNotify: () -> Unit,
        profile: TpmsAlertProfile = this.profile,
        teslaState: TeslaWarningState = TeslaWarningState(),
        onTesla: () -> Unit = onNotify,
        onCustom: () -> Unit = onNotify
    ) {
        processSuccessfulTpmsStatus(
            carId = 4,
            carName = "Elysa",
            tpmsDetails = tpms,
            observedAt = 1234L,
            profile = profile,
            saveObservation = {},
            pruneOlderThan90Days = { _, _ -> },
            detectTeslaStateChange = { _, details -> teslaState.detect(details) },
            updateTeslaState = { _, details -> teslaState.update(details) },
            resetCustomState = store::reset,
            claimCustomAlerts = store::claim,
            commitCustomAlert = store::commit,
            releaseCustomAlert = store::release,
            notifyTesla = { _, _, _ -> onTesla() },
            notifyCustom = { _, _, _ -> onCustom() }
        )
    }

    private class TeslaWarningState {
        private var warnings = emptySet<TirePosition>()

        fun detect(details: TpmsDetails?): TpmsStateChange? {
            val current = warningTires(details)
            val change = when {
                warnings.isEmpty() && current.isNotEmpty() ->
                    TpmsStateChange.WarningStarted(current.toList())
                warnings.isNotEmpty() && current.isEmpty() ->
                    TpmsStateChange.WarningCleared
                warnings.isNotEmpty() && current.isNotEmpty() && warnings != current ->
                    TpmsStateChange.WarningStarted(current.toList())
                else -> null
            }
            return change
        }

        fun update(details: TpmsDetails?) {
            warnings = warningTires(details)
        }

        private fun warningTires(details: TpmsDetails?): Set<TirePosition> = buildSet {
            if (details?.warningFl == true) add(TirePosition.FL)
            if (details?.warningFr == true) add(TirePosition.FR)
            if (details?.warningRl == true) add(TirePosition.RL)
            if (details?.warningRr == true) add(TirePosition.RR)
        }
    }

    private class AtomicCustomClaimStore {
        private val lock = Any()
        private var profileFingerprint: String? = null
        private val active = mutableMapOf<TirePosition, TpmsCustomPressureState>()
        private val pending = mutableMapOf<TirePosition, TpmsCustomAlertClaim>()
        private val claimed = mutableSetOf<TirePosition>()
        var claimsCommitted = 0
            private set

        fun state(wheel: TirePosition) = synchronized(lock) {
            TpmsCustomWheelState(
                state = active.getValue(wheel),
                pending = pending[wheel]?.let { TpmsCustomPendingAlert(it.observedPressureBar, it.thresholdBar) }
            )
        }

        suspend fun reset(carId: Int, fingerprint: String) = synchronized(lock) {
            if (profileFingerprint != fingerprint) {
                profileFingerprint = fingerprint
                active.clear()
                pending.clear()
                claimed.clear()
            }
        }

        suspend fun claim(
            carId: Int,
            fingerprint: String,
            observations: Map<TirePosition, TpmsCustomWheelObservation>,
            defer: Boolean,
            now: Long
        ): List<TpmsCustomAlertClaim> = synchronized(lock) {
            if (profileFingerprint != fingerprint) {
                profileFingerprint = fingerprint
                active.clear()
                pending.clear()
                claimed.clear()
            }
            observations.forEach { (wheel, observation) ->
                if (!observation.observed) return@forEach
                if (observation.state == null) {
                    active.remove(wheel)
                    pending.remove(wheel)
                    claimed.remove(wheel)
                    return@forEach
                }
                if (active[wheel] != observation.state) {
                    active[wheel] = observation.state
                    pending[wheel] = TpmsCustomAlertClaim(
                        wheel,
                        observation.state,
                        observation.observedPressureBar!!,
                        observation.thresholdBar!!
                    )
                    claimed.remove(wheel)
                }
            }
            if (defer) return@synchronized emptyList()
            pending.filterKeys { it !in claimed }.values.toList().onEach { claimed += it.wheel }
        }

        suspend fun commit(carId: Int, claim: TpmsCustomAlertClaim) = synchronized(lock) {
            if (claimed.remove(claim.wheel) && pending[claim.wheel] == claim) {
                pending.remove(claim.wheel)
                claimsCommitted++
            }
        }

        suspend fun release(carId: Int, claim: TpmsCustomAlertClaim) = synchronized(lock) {
            claimed.remove(claim.wheel)
        }
    }

    private class RecordingTpmsPressureSampleDao : TpmsPressureSampleDao {
        val saved = mutableListOf<TpmsPressureSample>()

        override suspend fun upsert(sample: TpmsPressureSample) { saved += sample }
        override suspend fun upsertAll(samples: List<TpmsPressureSample>) = Unit
        override suspend fun getInRange(carId: Int, from: Long, to: Long): List<TpmsPressureSample> = emptyList()
        override suspend fun deleteOlderThan(carId: Int, cutoff: Long): Int = 0
    }
}

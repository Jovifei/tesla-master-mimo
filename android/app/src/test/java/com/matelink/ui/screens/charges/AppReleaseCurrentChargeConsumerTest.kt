package com.matelink.ui.screens.charges

import androidx.lifecycle.ViewModelStore
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.repository.*
import com.matelink.domain.analytics.AnalysisPageIdentity
import com.matelink.domain.telemetry.snapshotEvidence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppReleaseCurrentChargeConsumerTest {
    @Test fun anAlreadyFetchedFreshFleetCounterCanSupportOlderCurrentChargePayloads() {
        val now = java.time.Instant.parse("2026-10-27T00:10:00Z")
        val detail = ChargeDetail(1, source = "fleet_api", chargeEnergyAdded = 999.0)
        val status = CarStatus(chargingDetails = ChargingDetails(chargingState = "Charging", chargeEnergyAdded = 12.0))
        val snapshot = VehicleStatusEvidence(status, Units(), snapshotEvidence("fleet_api", now.toString(), emptyMap(), now))
        assertEquals(12.0, (currentChargeAddedEnergy(detail, snapshot, now) ?: Double.NaN), 0.0)
        assertNull(detail.batteryInputKwh)
        assertNull(currentChargeAddedEnergy(detail, snapshot.copy(evidence = snapshotEvidence("fleet_api", null, emptyMap(), now)), now))
        assertEquals(0.0, currentChargeAddedEnergy(detail, snapshot.copy(status = status.copy(
            chargingDetails = ChargingDetails(chargingState = "Charging", chargeEnergyAdded = 0.0))), now)!!, 0.0)
    }
    private class Source : CurrentChargePageSource {
        val epochs = MutableStateFlow(0)
        override val scopeChanges = epochs.map { Unit }
        var sameVehicleAcrossEpochs = false
        var lastDc = false
        var statusBody: suspend (Int) -> VehicleStatusEvidence = { VehicleStatusEvidence(null, null, snapshotEvidence(null, null, emptyMap())) }
        var body: suspend (Int) -> ApiResult<CurrentChargeOutcome> = { id ->
            ApiResult.Success(CurrentChargeOutcome.Active(ChargeDetail(id, isCharging = true))) }
        override suspend fun identity(carId: Int): AnalysisPageIdentity {
            val epoch = epochs.value
            val stable = if (sameVehicleAcrossEpochs) "A:$carId" else "A:$epoch:$carId"
            return AnalysisPageIdentity(VehicleContext(carId, stable, -carId,
                HistoryConnectionSource.CLOUD, "cloud")) { epochs.value == epoch }
        }
        override suspend fun current(carId: Int) = body(carId)
        override suspend fun status(carId: Int) = statusBody(carId)
        override suspend fun setLastDc(localId: Int, dc: Boolean) { lastDc = dc }
        override suspend fun clearSession(localId: Int) { lastDc = false }
        override suspend fun wasLastDc(localId: Int) = lastDc
    }
    @Test fun failedCurrentEndpointKeepsHistoryWithoutApplyingANewLiveSnapshotToIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val now = java.time.Instant.now()
        fun live(power: Double) = VehicleStatusEvidence(CarStatus(chargingDetails = ChargingDetails(
            chargingState = "Charging", chargerPower = power, chargeEnergyAdded = 12.0)), Units(),
            snapshotEvidence("fleet_api", now.toString(), emptyMap(), now))
        val source = Source().apply { statusBody = { live(11.0) } }
        val vm = CurrentChargeViewModel(source); val store = ViewModelStore().apply { put("current", vm) }
        try {
            vm.loadCurrentCharge(1); runCurrent()
            assertEquals(1, vm.uiState.value.chargeDetail?.chargeId)
            assertEquals(11.0, vm.uiState.value.instantPowerKw!!, 0.0)
            source.body = { ApiResult.Error("synthetic offline", 503) }
            source.statusBody = { live(2.0) }
            advanceTimeBy(30_000); runCurrent()
            assertEquals(1, vm.uiState.value.chargeDetail?.chargeId)
            assertNull("Old session must not receive the new instantaneous power", vm.uiState.value.instantPowerKw)
            assertNull(vm.uiState.value.currentAddedEnergyKwh)
            assertEquals(com.matelink.domain.telemetry.SnapshotFreshness.HISTORY, vm.uiState.value.snapshotFreshness)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun aKnownActiveAcSessionRevokesThePreviousDcCompletionHint() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val now = java.time.Instant.now()
        val source = Source().apply {
            lastDc = true
            statusBody = { VehicleStatusEvidence(CarStatus(chargingDetails = ChargingDetails(
                chargingState = "Charging", pluggedIn = true, chargerPhases = 1)), Units(),
                snapshotEvidence("fleet_api", now.toString(), emptyMap(), now)) }
        }
        val vm = CurrentChargeViewModel(source); val store = ViewModelStore().apply { put("current", vm) }
        try {
            vm.loadCurrentCharge(1); runCurrent()
            assertFalse("AC proof must replace an older DC flag even if unplug was unobserved", source.lastDc)
            source.body = { ApiResult.Success(CurrentChargeOutcome.NoActiveCharge) }
            source.statusBody = { VehicleStatusEvidence(CarStatus(chargingDetails = ChargingDetails(
                chargingState = "Complete", pluggedIn = true)), Units(), snapshotEvidence("fleet_api", now.toString(), emptyMap(), now)) }
            advanceTimeBy(30_000); runCurrent()
            assertFalse(vm.uiState.value.isDcFinishedPluggedIn)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun sameLoginCredentialRefreshReloadsTheSameVerifiedVehicle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source().apply {
            sameVehicleAcrossEpochs = true
            body = { ApiResult.Success(CurrentChargeOutcome.Active(ChargeDetail(7, isCharging = true))) }
        }
        val vm = CurrentChargeViewModel(source); val store = ViewModelStore().apply { put("current", vm) }
        try {
            vm.loadCurrentCharge(1); runCurrent()
            assertEquals(7, vm.uiState.value.chargeDetail?.chargeId)
            source.body = { ApiResult.Success(CurrentChargeOutcome.Active(ChargeDetail(8, isCharging = true))) }
            source.epochs.value++; runCurrent()
            assertEquals("A same-vehicle credential refresh must not leave the page blank", 8,
                vm.uiState.value.chargeDetail?.chargeId)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun changedAccountImmediatelyClearsPublishedCurrentSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source(); val vm = CurrentChargeViewModel(source)
        val store = ViewModelStore().apply { put("current", vm) }
        try {
            vm.loadCurrentCharge(1); runCurrent()
            assertEquals(1, vm.uiState.value.chargeDetail?.chargeId)
            source.epochs.value++; runCurrent()
            assertNull(vm.uiState.value.chargeDetail)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun lateRequestFromPriorSameCarAccountCannotPublish() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val source = Source().apply { body = { gate.await(); ApiResult.Success(CurrentChargeOutcome.Active(ChargeDetail(9))) } }
        val vm = CurrentChargeViewModel(source); val store = ViewModelStore().apply { put("current", vm) }
        try {
            vm.loadCurrentCharge(1); runCurrent()
            source.epochs.value++; gate.complete(Unit); runCurrent()
            assertNull(vm.uiState.value.chargeDetail)
        } finally { gate.complete(Unit); store.clear(); Dispatchers.resetMain() }
    }
    @Test fun chronologicalProjectionUsesDatesRatherThanAlwaysReversingTheResponse() {
        val points = listOf(ChargePoint(date = "2026-10-27T00:00:00Z"), ChargePoint(date = "2026-10-27T00:00:10Z"))
        assertEquals(points, currentChargeChronologicalPoints(ChargeDetail(1, chargePoints = points)))
        assertEquals(points, currentChargeChronologicalPoints(ChargeDetail(1, chargePoints = points.reversed())))
    }
    @Test fun explicitUnknownContractCannotResurrectRawCurrentEnergy() {
        assertNull(currentChargeEnergyKwh(ChargeDetail(1, source = "telemetry_mqtt", chargeEnergyAdded = 12.0,
            energyContract = EnergyContract(version = 1, batteryInput = EnergyMetric(quality = "unknown")))))
    }
    @Test fun legacyObservedZeroStillHasAValue() {
        assertEquals(0.0, currentChargeEnergyKwh(ChargeDetail(1, source = "teslamate_archive", chargeEnergyAdded = 0.0))!!, 0.0)
    }
}

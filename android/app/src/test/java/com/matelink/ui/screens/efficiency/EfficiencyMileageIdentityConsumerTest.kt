package com.matelink.ui.screens.efficiency

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.repository.*
import com.matelink.domain.analytics.*
import com.matelink.ui.screens.mileage.MileagePageSource
import com.matelink.ui.screens.mileage.MileageViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/** Real VM publication/cache/selection paths. Network completion is synthetic and deliberately non-cooperative. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class EfficiencyMileageIdentityConsumerTest {
    private class Identity {
        var epoch = 0L
        var account = "A"
        var origin = "https://synthetic-a.test"
        var kind = HistoryConnectionSource.CLOUD
        var vehicle = "vehicle-a"
        val events = MutableStateFlow(0L)
        val scope get() = HistoryReadScope(kind, origin, account, origin, epoch, epoch, epoch)
        fun context(id: Int) = VehicleContext(id, "$kind/$origin/$account/$vehicle/$id",
            -(100 + id + if (account == "A") 0 else 100), kind, origin)
        fun change(account: String = this.account, origin: String = this.origin,
            kind: HistoryConnectionSource = this.kind, vehicle: String = this.vehicle, emit: Boolean = false) {
            epoch++
            this.account = account; this.origin = origin; this.kind = kind; this.vehicle = vehicle
            if (emit) events.value = epoch
        }
        fun capture(id: Int): AnalysisPageIdentity {
            val scope = scope; val context = context(id)
            return AnalysisPageIdentity(context) { this.scope == scope && this.context(id) == context }
        }
        fun proof(id: Int) = VerifiedHistoryReadContext(scope, context(id), true, null, false)
        fun current(proof: VerifiedHistoryReadContext) = proof.scope == scope && proof.context == context(proof.context.remoteApiCarId)
    }
    private class EfficiencySource(val identity: Identity = Identity()) : EfficiencyPageSource {
        override val scopeChanges = identity.events.map { Unit }
        var calls = 0
        var body: suspend (Int) -> ApiResult<AnalysisHistorySnapshot> = { id -> snapshot(identity.context(id), listOf(drive(22))) }
        override suspend fun identity(carId: Int) = identity.capture(carId)
        override suspend fun load(carId: Int): ApiResult<AnalysisHistorySnapshot> { calls++; return body(carId) }
    }
    private class MileageSource(val identity: Identity = Identity()) : MileagePageSource {
        override val scopeChanges = identity.events.map { Unit }
        val statuses = mutableListOf<Int>()
        val histories = mutableListOf<Int>()
        var statusBody: suspend (Int) -> ApiResult<CarStatusWithUnits> = { ApiResult.Success(CarStatusWithUnits(CarStatus(), Units(unitOfLength = "km"))) }
        override suspend fun identity(carId: Int) = identity.capture(carId)
        override suspend fun status(carId: Int): ApiResult<CarStatusWithUnits> { statuses += carId; return statusBody(carId) }
        override suspend fun history(carId: Int): ApiResult<UnifiedHistory> {
            histories += carId
            return ApiResult.Success(UnifiedHistory(identity.context(carId), listOf(drive(carId * 10)), emptyList(), true, true)
                .also { it.verifiedReadContext = identity.proof(carId) })
        }
        override suspend fun isContextCurrent(proof: VerifiedHistoryReadContext) = identity.current(proof)
        override fun isContextCurrentNow(proof: VerifiedHistoryReadContext) = identity.current(proof)
        override suspend fun currencyCode() = "CNY"
        override suspend fun manualRecords(): List<com.matelink.data.local.entity.ChargeCostOverride> = emptyList()
    }
    companion object {
        private fun drive(id: Int, daysAgo: Long = 1, distance: Double = 10.0) = DriveData(id,
            startDate = LocalDate.now().minusDays(daysAgo).toString(), endDate = LocalDate.now().minusDays(daysAgo).toString(),
            odometerDetails = DriveOdometerDetails(distance = distance), energyConsumedNet = 2.0,
            source = "teslamate_archive", qualityState = "observed", lengthUnit = "km")
        private fun snapshot(context: VehicleContext, drives: List<DriveData>) = ApiResult.Success(
            AnalysisHistorySnapshot(drives, emptyList(), Instant.now(), HistoryCoverage(drives.size, 0), context = context))
    }

    @Test fun efficiencySameRemoteIdAccountAtoBtoALateSuccessCannotReplaceNewA() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = EfficiencySource(); val oldGate = CompletableDeferred<Unit>(); val bGate = CompletableDeferred<Unit>()
        val old = source.identity.context(7)
        source.body = { id -> when (source.calls) {
            1 -> withContext(NonCancellable) { oldGate.await(); snapshot(old, listOf(drive(11))) }
            2 -> { val b = source.identity.context(id); withContext(NonCancellable) { bGate.await(); snapshot(b, listOf(drive(22))) } }
            else -> snapshot(source.identity.context(id), listOf(drive(33)))
        } }
        val vm = EfficiencyViewModel(source)
        try {
            vm.load(7); advanceUntilIdle()
            source.identity.change(account = "B"); vm.load(7); advanceUntilIdle()
            source.identity.change(account = "A"); vm.load(7); advanceUntilIdle()
            assertEquals(listOf(33), vm.uiState.value.tripPositions.map { it.driveId })
            bGate.complete(Unit); oldGate.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(33), vm.uiState.value.tripPositions.map { it.driveId })
            vm.selectCustomRange(LocalDate.now().minusDays(6), LocalDate.now())
            assertEquals(listOf(33), vm.uiState.value.tripPositions.map { it.driveId })
            assertEquals(3, source.calls)
        } finally { bGate.complete(Unit); oldGate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun efficiencyOldAccountErrorCannotResetNewDataOrSelectedWindowAfterAba() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = EfficiencySource(); val oldGate = CompletableDeferred<Unit>(); val bGate = CompletableDeferred<Unit>()
        source.body = { id -> when (source.calls) {
            1 -> withContext(NonCancellable) { oldGate.await(); ApiResult.Error("old account error") }
            2 -> { val b = source.identity.context(id); withContext(NonCancellable) { bGate.await(); snapshot(b, listOf(drive(22))) } }
            else -> snapshot(source.identity.context(id), listOf(drive(33, 2), drive(34, 20)))
        } }
        val vm = EfficiencyViewModel(source)
        try {
            vm.load(7); advanceUntilIdle()
            source.identity.change(account = "B"); vm.load(7); advanceUntilIdle()
            source.identity.change(account = "A"); vm.load(7); advanceUntilIdle()
            vm.selectCustomRange(LocalDate.now().minusDays(6), LocalDate.now())
            vm.selectCustomRange(LocalDate.now().minusDays(29), LocalDate.now())
            bGate.complete(Unit); oldGate.complete(Unit); advanceUntilIdle()
            assertEquals(AnalysisWindow.CUSTOM, vm.uiState.value.selectedWindow)
            assertEquals(setOf(33, 34), vm.uiState.value.tripPositions.map { it.driveId }.toSet())
            assertNull(vm.uiState.value.error)
        } finally { bGate.complete(Unit); oldGate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun efficiencyLoadedSameRemoteIdCannotReuseAnotherOriginsPrivateCacheOnWindowSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = EfficiencySource(); val vm = EfficiencyViewModel(source)
        try {
            vm.load(7); advanceUntilIdle()
            assertEquals(listOf(22), vm.uiState.value.tripPositions.map { it.driveId })
            source.identity.change(origin = "https://synthetic-b.test", vehicle = "replacement")
            vm.selectCustomRange(LocalDate.now().minusDays(29), LocalDate.now())
            assertTrue(vm.uiState.value.tripPositions.isEmpty())
            assertNull(vm.uiState.value.avgEfficiencyWhKm)
            vm.load(7); advanceUntilIdle()
            assertEquals(2, source.calls)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun efficiencySourceFlowChangeClearsPublishedProjectionAndReloadsCurrentIdentity() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = EfficiencySource(); val gate = CompletableDeferred<Unit>()
        source.body = { id -> if (source.identity.epoch == 0L) snapshot(source.identity.context(id), listOf(drive(11)))
            else { val context = source.identity.context(id); withContext(NonCancellable) { gate.await(); snapshot(context, listOf(drive(22))) } } }
        val vm = EfficiencyViewModel(source)
        try {
            vm.load(7); advanceUntilIdle()
            source.identity.change(kind = HistoryConnectionSource.SELF_HOSTED, emit = true); advanceUntilIdle()
            assertTrue(vm.uiState.value.tripPositions.isEmpty())
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(22), vm.uiState.value.tripPositions.map { it.driveId })
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun efficiencyCurrentFailurePreservesLatestWindowAndDoesNotInventStatistics() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = EfficiencySource(); val gate = CompletableDeferred<Unit>()
        source.body = { withContext(NonCancellable) { gate.await(); ApiResult.Error("current synthetic failure") } }
        val vm = EfficiencyViewModel(source)
        try {
            vm.load(7); advanceUntilIdle()
            vm.selectCustomRange(LocalDate.now().minusDays(6), LocalDate.now())
            vm.selectCustomRange(LocalDate.now().minusDays(29), LocalDate.now())
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(AnalysisWindow.CUSTOM, vm.uiState.value.selectedWindow)
            assertNull(vm.uiState.value.avgEfficiencyWhKm)
            assertEquals(0, vm.uiState.value.driveCount)
            assertEquals("current synthetic failure", vm.uiState.value.error)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun mileageLateUnitsForPreviousCarCannotReplaceNewCarAndStatusIsNotDuplicated() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MileageSource(); val gate = CompletableDeferred<Unit>()
        source.statusBody = { id -> if (id == 7) withContext(NonCancellable) { gate.await()
            ApiResult.Success(CarStatusWithUnits(CarStatus(), Units(unitOfLength = "mi"))) }
            else ApiResult.Success(CarStatusWithUnits(CarStatus(), Units(unitOfLength = "km"))) }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertTrue(vm.uiState.value.allDrives.isNotEmpty())
            vm.setCarId(8)
            assertTrue(vm.uiState.value.allDrives.isEmpty())
            assertNull(vm.uiState.value.units)
            advanceUntilIdle(); gate.complete(Unit); advanceUntilIdle()
            assertEquals("km", vm.uiState.value.units?.unitOfLength)
            assertEquals(listOf(80), vm.uiState.value.allDrives.map { it.driveId })
            assertEquals(listOf(7, 8), source.statuses)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun mileageSameRemoteIdRebindRejectsOldUnitsAndClearsOldProjectionSynchronously() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MileageSource(); val gate = CompletableDeferred<Unit>()
        source.statusBody = { if (source.statuses.size == 1) withContext(NonCancellable) { gate.await()
            ApiResult.Success(CarStatusWithUnits(CarStatus(), Units(unitOfLength = "mi"))) }
            else ApiResult.Success(CarStatusWithUnits(CarStatus(), Units(unitOfLength = "km"))) }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            source.identity.change(origin = "https://replacement.test", vehicle = "replacement-vehicle")
            vm.setCarId(7)
            assertTrue(vm.uiState.value.allDrives.isEmpty())
            assertNull(vm.uiState.value.units)
            advanceUntilIdle(); gate.complete(Unit); advanceUntilIdle()
            assertEquals("km", vm.uiState.value.units?.unitOfLength)
            assertEquals(listOf(7, 7), source.statuses)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun mileageStatusAbaWithoutAnotherRequestCannotPublishOldUnits() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MileageSource(); val gate = CompletableDeferred<Unit>()
        source.statusBody = { withContext(NonCancellable) { gate.await()
            ApiResult.Success(CarStatusWithUnits(CarStatus(), Units(unitOfLength = "mi"))) } }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            source.identity.change(account = "B"); source.identity.change(account = "A")
            gate.complete(Unit); advanceUntilIdle()
            assertNull(vm.uiState.value.units)
            assertEquals(listOf(7), source.statuses)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun mileageScopeFlowChangeClearsUnitsAndProjectionBeforeNewStatusCompletes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MileageSource(); val gate = CompletableDeferred<Unit>()
        source.statusBody = { if (source.identity.epoch == 0L) ApiResult.Success(CarStatusWithUnits(CarStatus(), Units(unitOfLength = "mi")))
            else withContext(NonCancellable) { gate.await(); ApiResult.Success(CarStatusWithUnits(CarStatus(), Units(unitOfLength = "km"))) } }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertEquals("mi", vm.uiState.value.units?.unitOfLength)
            source.identity.change(account = "B", kind = HistoryConnectionSource.SELF_HOSTED, emit = true); advanceUntilIdle()
            assertNull(vm.uiState.value.units)
            gate.complete(Unit); advanceUntilIdle()
            assertEquals("km", vm.uiState.value.units?.unitOfLength)
            assertEquals(listOf(7, 7), source.statuses)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun mileageRefreshKeepsTheOnlyPendingStatusRequestForItsStillCurrentIdentity() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MileageSource(); val gate = CompletableDeferred<Unit>()
        source.statusBody = { withContext(NonCancellable) { gate.await()
            ApiResult.Success(CarStatusWithUnits(CarStatus(), Units(unitOfLength = "mi"))) } }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle(); vm.refresh(); advanceUntilIdle()
            assertEquals(listOf(7), source.statuses)
            gate.complete(Unit); advanceUntilIdle()
            assertEquals("mi", vm.uiState.value.units?.unitOfLength)
            assertEquals(listOf(7), source.statuses)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun mileageRefreshDoesNotAddAnotherStatusRequestForTheSameVerifiedIdentity() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MileageSource(); val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle(); vm.refresh(); advanceUntilIdle()
            assertEquals(listOf(7), source.statuses)
            assertEquals(listOf(7, 7), source.histories)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }
}

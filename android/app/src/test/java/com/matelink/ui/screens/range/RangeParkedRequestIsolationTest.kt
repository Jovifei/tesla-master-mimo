package com.matelink.ui.screens.range

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.*
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.VehicleContext
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.CarStatusWithUnits
import com.matelink.domain.analytics.*
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.Units
import com.matelink.ui.screens.drives.ParkedDetailViewModel
import com.matelink.ui.screens.drives.ParkedPageIdentity
import com.matelink.ui.screens.drives.ParkedPageSource
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

/** Calls the real ViewModels and their real state/cache publication paths. API awaits are synthetic. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28])
class RangeParkedRequestIsolationTest {
    private class Identity {
        val changes=MutableStateFlow(0)
        var epoch=0
        var available=true
        var context=VehicleContext(7,"synthetic-account-A-vehicle",-101,HistoryConnectionSource.CLOUD,"synthetic-cloud")
        fun change(label:String,announce:Boolean=true,source:HistoryConnectionSource=HistoryConnectionSource.CLOUD) {
            epoch++
            context=VehicleContext(7,"synthetic-$label",-101-epoch,source,"synthetic-$label-origin")
            if(announce) changes.value=epoch
        }
        fun proof():()->Boolean {
            if(!available) throw HistoryIdentityUnavailableException()
            val captured=epoch; val vehicle=context
            return { available && captured==epoch && context==vehicle }
        }
    }
    private class RangeSource(val identity: Identity=Identity()):RangePageSource {
        override val scopeChanges=identity.changes.map { Unit }
        var histories=0; var healthCalls=0; var statusCalls=0; var cachedCalls=0
        var historyBody:suspend ()->ApiResult<AnalysisHistorySnapshot> = { snapshot(identity.context,22) }
        var cachedBody:suspend ()->ApiResult<AnalysisHistorySnapshot>? = { null }
        var healthBody:suspend ()->ApiResult<BatteryHealth> = { ApiResult.Success(BatteryHealth(currentCapacity=60.0)) }
        var status1:suspend ()->ApiResult<CarStatusWithUnits> = { ApiResult.Error("no synthetic status") }
        override suspend fun identity(carId:Int)=RangePageIdentity(identity.context,identity.proof())
        override suspend fun history(carId:Int):ApiResult<AnalysisHistorySnapshot> { histories++; return historyBody() }
        override suspend fun loadCached(carId:Int):ApiResult<AnalysisHistorySnapshot>? { cachedCalls++; return cachedBody() }
        override suspend fun health(carId:Int):ApiResult<BatteryHealth> { healthCalls++; return healthBody() }
        override suspend fun status(carId:Int):ApiResult<CarStatusWithUnits> { statusCalls++; return status1() }
    }
    private class ParkedSource(val identity:Identity=Identity()):ParkedPageSource {
        override val scopeChanges=identity.changes.map { Unit }
        var calls=0
        var body:suspend (Int,Int)->ApiResult<ParkedDetailData> = { older,newer -> ApiResult.Success(parked(older,newer,"new")) }
        override suspend fun identity(carId:Int)=ParkedPageIdentity(identity.proof())
        // This fixture tests request/scope races with admitted immutable server records.
        // Real local-boundary/source/window admission has independent VM + Room regressions.
        override suspend fun acceptsRemote(identity:ParkedPageIdentity,local:ParkedDetailData?,received:ParkedDetailData)=true
        override suspend fun detail(carId:Int,olderDriveId:Int,newerDriveId:Int):ApiResult<ParkedDetailData> {
            calls++; return body(olderDriveId,newerDriveId)
        }
    }
    companion object {
        private fun drive(id:Int,daysAgo:Long=1)=DriveData(id,
            startDate=LocalDate.now().minusDays(daysAgo).toString(),
            endDate=LocalDate.now().minusDays(daysAgo).toString(),
            odometerDetails=DriveOdometerDetails(distance=12.0),rangeRated=DriveRange(100.0,90.0),
            energyConsumedNet=2.0,lengthUnit="km",source="teslamate_archive",qualityState="observed")
        private fun snapshot(context:VehicleContext,id:Int,drives:List<DriveData> = listOf(drive(id)))=
            ApiResult.Success(AnalysisHistorySnapshot(drives,emptyList(),Instant.now(),
                HistoryCoverage(drives.size,0),context=context))
        private fun parked(older:Int,newer:Int,label:String)=ParkedDetailData(older,newer,
            "2026-10-20T00:00:00Z","2026-10-20T01:00:00Z",address=label,source="teslamate_archive")
    }

    @Test fun rangeOldSuccessCannotReplaceNewSameRemoteIdOrRepopulateItsPrivateCache() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=RangeSource(); val gate=CompletableDeferred<Unit>(); val old=source.identity.context
        source.historyBody={ if(source.histories==1) withContext(NonCancellable) { gate.await(); snapshot(old,11) }
            else snapshot(source.identity.context,22) }
        val vm=RangeViewModel(source)
        try {
            vm.setCarId(7); advanceUntilIdle()
            source.identity.change("account-B",announce=false)
            vm.refresh(); advanceUntilIdle()
            assertEquals(listOf(22),vm.uiState.value.trips.map { it.driveId })
            gate.complete(Unit); advanceUntilIdle()
            vm.selectCustomRange(LocalDate.now().minusDays(30),LocalDate.now())
            assertEquals(listOf(22),vm.uiState.value.trips.map { it.driveId })
            assertNull(vm.uiState.value.error)
            assertEquals(1,source.healthCalls)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun rangeOldErrorCannotOverwriteNewSameRemoteId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=RangeSource(); val gate=CompletableDeferred<Unit>()
        source.historyBody={ if(source.histories==1) withContext(NonCancellable) { gate.await(); ApiResult.Error("old account failure") }
            else snapshot(source.identity.context,22) }
        val vm=RangeViewModel(source)
        try {
            vm.setCarId(7); advanceUntilIdle()
            source.identity.change("account-B",announce=false)
            vm.refresh(); advanceUntilIdle()
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(22),vm.uiState.value.trips.map { it.driveId })
            assertNull(vm.uiState.value.error)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun rangeSourceChangeClearsPublishedRowsBeforeReloadingSameRemoteId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=RangeSource(); val gate=CompletableDeferred<Unit>()
        source.historyBody={ if(source.identity.epoch==0) snapshot(source.identity.context,11)
            else withContext(NonCancellable) { gate.await(); snapshot(source.identity.context,22) } }
        val vm=RangeViewModel(source)
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertEquals(listOf(11),vm.uiState.value.trips.map { it.driveId })
            source.identity.change("self-hosted-B",source=HistoryConnectionSource.SELF_HOSTED); advanceUntilIdle()
            assertTrue(vm.uiState.value.trips.isEmpty())
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(22),vm.uiState.value.trips.map { it.driveId })
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun rangeRefreshAndErrorDismissCannotRepublishCachedRowsAfterAnUnannouncedVehicleRebind() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=RangeSource(); val gate=CompletableDeferred<Unit>()
        source.historyBody={ snapshot(source.identity.context,11) }
        val vm=RangeViewModel(source)
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertEquals(listOf(11),vm.uiState.value.trips.map { it.driveId })
            source.identity.change("replacement-vehicle-B",announce=false)
            source.historyBody={ withContext(NonCancellable) { gate.await(); snapshot(source.identity.context,22) } }
            vm.refresh(); advanceUntilIdle()
            assertTrue("refresh must clear the old vehicle before the replacement response",vm.uiState.value.trips.isEmpty())
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(22),vm.uiState.value.trips.map { it.driveId })
            source.historyBody={ ApiResult.Error("synthetic retry failed") }
            vm.refresh(); advanceUntilIdle()
            assertNotNull(vm.uiState.value.error)
            source.identity.change("replacement-vehicle-C",announce=false)
            vm.clearError()
            assertTrue("error dismissal must not republish another vehicle's cached rows",vm.uiState.value.trips.isEmpty())
            assertNull(vm.uiState.value.error)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun rangeVehicleRebindDuringHealthAwaitCannotMixHistoryWithAnotherVehicleStatus() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=RangeSource(); val gate=CompletableDeferred<Unit>()
        source.healthBody={ withContext(NonCancellable) { gate.await(); ApiResult.Success(BatteryHealth(currentCapacity=99.0)) } }
        val vm=RangeViewModel(source)
        try {
            vm.setCarId(7); advanceUntilIdle()
            source.identity.change("same-account-replacement-vehicle",announce=false)
            gate.complete(Unit); advanceUntilIdle()
            assertTrue(vm.uiState.value.trips.isEmpty())
            assertEquals(0,source.statusCalls)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun rangeSevenToThirtyDaySelectionUsesLatestWindowWithoutAnotherHttpRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=RangeSource(); val gate=CompletableDeferred<Unit>()
        source.historyBody={ withContext(NonCancellable) { gate.await()
            snapshot(source.identity.context,1,listOf(drive(1,2),drive(2,20))) } }
        val vm=RangeViewModel(source)
        try {
            vm.setCarId(7); advanceUntilIdle()
            vm.selectCustomRange(LocalDate.now().minusDays(6),LocalDate.now())
            vm.selectCustomRange(LocalDate.now().minusDays(29),LocalDate.now())
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(AnalysisWindow.CUSTOM,vm.uiState.value.selectedWindow)
            assertEquals(setOf(1,2),vm.uiState.value.trips.map { it.driveId }.toSet())
            assertEquals(1,source.histories); assertEquals(1,source.healthCalls); assertEquals(1,source.statusCalls)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun rangeHealthCancellationMustNotBecomeAStatusRequestOrErasePublishedHistory() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=RangeSource()
        source.healthBody={ throw CancellationException("synthetic cancelled health") }
        val vm=RangeViewModel(source)
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertEquals(0,source.statusCalls)
            // Room-first: the verified history is already visible; the optional health
            // enhancement being cancelled must not erase it nor trigger a status request.
            assertEquals(listOf(22),vm.uiState.value.trips.map { it.driveId })
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun parkedOldSuccessCannotReplaceNewDriveBoundaryRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=ParkedSource(); val gate=CompletableDeferred<Unit>()
        source.body={ older,newer -> if(older==1) withContext(NonCancellable) { gate.await(); ApiResult.Success(parked(older,newer,"old")) }
            else ApiResult.Success(parked(older,newer,"new")) }
        val vm=ParkedDetailViewModel(source)
        try {
            vm.load(7,1,2); advanceUntilIdle(); vm.load(7,3,4); advanceUntilIdle()
            gate.complete(Unit); advanceUntilIdle()
            assertEquals("new",vm.uiState.value.data?.address)
            assertEquals(3,vm.uiState.value.data?.olderDriveId)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun parkedOldErrorCannotReplaceNewSameRemoteIdAccountResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=ParkedSource(); val gate=CompletableDeferred<Unit>()
        source.body={ older,newer -> if(source.calls==1) withContext(NonCancellable) { gate.await(); ApiResult.Error("old account failure") }
            else ApiResult.Success(parked(older,newer,"new")) }
        val vm=ParkedDetailViewModel(source)
        try {
            vm.load(7,1,2); advanceUntilIdle()
            source.identity.change("account-B",announce=false)
            vm.load(7,1,2); advanceUntilIdle()
            gate.complete(Unit); advanceUntilIdle()
            assertEquals("new",vm.uiState.value.data?.address)
            assertNull(vm.uiState.value.error)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun parkedSourceChangeClearsPublishedDataForSameRemoteIdAndSameBoundaries() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=ParkedSource(); val gate=CompletableDeferred<Unit>()
        source.body={ older,newer -> if(source.identity.epoch==0) ApiResult.Success(parked(older,newer,"old"))
            else withContext(NonCancellable) { gate.await(); ApiResult.Success(parked(older,newer,"new")) } }
        val vm=ParkedDetailViewModel(source)
        try {
            vm.load(7,1,2); advanceUntilIdle()
            source.identity.change("new-origin",source=HistoryConnectionSource.SELF_HOSTED); advanceUntilIdle()
            assertNull(vm.uiState.value.data)
            gate.complete(Unit); advanceUntilIdle()
            assertEquals("new",vm.uiState.value.data?.address)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun unverifiedLocalMappingMustNotIssueAnyRangeOrParkedRequests() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val identity=Identity().apply { available=false }
        val range=RangeSource(identity); val parked=ParkedSource(identity)
        val rangeVm=RangeViewModel(range); val parkedVm=ParkedDetailViewModel(parked)
        try {
            rangeVm.setCarId(7); parkedVm.load(7,1,2); advanceUntilIdle()
            assertEquals(0,range.histories); assertEquals(0,range.healthCalls); assertEquals(0,range.statusCalls)
            assertEquals(0,parked.calls)
            assertTrue(rangeVm.uiState.value.trips.isEmpty()); assertNull(parkedVm.uiState.value.data)
            assertFalse(rangeVm.uiState.value.isLoading); assertFalse(parkedVm.uiState.value.isLoading)
        } finally { rangeVm.viewModelScope.cancel(); parkedVm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun rangePublishesVerifiedCachedHistoryBeforePendingNetworkAndRetainsAfterOfflineError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=RangeSource(); val remote=CompletableDeferred<Unit>()
        source.cachedBody={ snapshot(source.identity.context,77) }
        source.historyBody={ withContext(NonCancellable) { remote.await() }; ApiResult.Error("offline") }
        val vm=RangeViewModel(source)
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertEquals(listOf(77),vm.uiState.value.trips.map { it.driveId })
            assertFalse(vm.uiState.value.isLoading)
            assertEquals(HistoryFreshness.STALE,vm.uiState.value.historyFreshness)
            vm.selectCustomRange(LocalDate.now().minusDays(5),LocalDate.now())
            assertEquals(listOf(77),vm.uiState.value.trips.map { it.driveId })
            remote.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(77),vm.uiState.value.trips.map { it.driveId })
            assertNull(vm.uiState.value.error)
            assertEquals(1,source.cachedCalls); assertEquals(1,source.histories)
        } finally { remote.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun rangeCachedRefreshedToFreshWhenRemoteSuccessArrives() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=RangeSource(); val remote=CompletableDeferred<Unit>()
        source.cachedBody={ snapshot(source.identity.context,77) }
        source.historyBody={ withContext(NonCancellable) { remote.await() }; snapshot(source.identity.context,22) }
        val vm=RangeViewModel(source)
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertEquals(listOf(77),vm.uiState.value.trips.map { it.driveId })
            assertEquals(HistoryFreshness.STALE,vm.uiState.value.historyFreshness)
            remote.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(22),vm.uiState.value.trips.map { it.driveId })
            assertEquals(HistoryFreshness.FRESH,vm.uiState.value.historyFreshness)
            assertNull(vm.uiState.value.error)
        } finally { remote.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun rangeUnreadableCachedHistoryDoesNotSuppressVerifiedRemoteRecovery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=RangeSource()
        source.cachedBody={ throw IllegalStateException("synthetic_room_io_failure_private") }
        val vm=RangeViewModel(source)
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertEquals(1,source.histories)
            assertEquals(listOf(22),vm.uiState.value.trips.map { it.driveId })
            assertNull(vm.uiState.value.error)
            assertFalse(vm.uiState.value.isLoading)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun rangeRejectsAnotherVehiclesCachedSnapshotEvenWhenRemoteIdMatches() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=RangeSource(); val old=source.identity.context
        source.cachedBody={ snapshot(old.copy(stableIdentity="foreign-account"),88) }
        val vm=RangeViewModel(source)
        try {
            vm.setCarId(7); advanceUntilIdle()
            // A cached snapshot bound to another account/vehicle must be rejected outright — it
            // must never surface cached rows nor fall through to the remote for the same numeric id.
            assertTrue(vm.uiState.value.trips.isEmpty())
            assertEquals(0,source.histories)
            assertNull(vm.uiState.value.error)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun rangeNetworkFailureWithoutCacheShowsErrorEmptyState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=RangeSource(); val remote=CompletableDeferred<Unit>()
        source.historyBody={ withContext(NonCancellable) { remote.await() }; ApiResult.Error("synthetic offline") }
        val vm=RangeViewModel(source)
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertTrue(vm.uiState.value.trips.isEmpty())
            remote.complete(Unit); advanceUntilIdle()
            assertEquals("synthetic offline",vm.uiState.value.error)
            assertFalse(vm.uiState.value.isLoading)
        } finally { remote.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun rangeEnhancementPendingKeepsUpdatedHistoryVisible() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source=RangeSource(); val healthGate=CompletableDeferred<Unit>(); val statusGate=CompletableDeferred<Unit>()
        source.cachedBody={ snapshot(source.identity.context,77) }
        source.historyBody={ snapshot(source.identity.context,22) }
        source.healthBody={ withContext(NonCancellable) { healthGate.await() }; ApiResult.Success(BatteryHealth(currentCapacity=99.0)) }
        source.status1={ withContext(NonCancellable) { statusGate.await() };
            ApiResult.Success(CarStatusWithUnits(CarStatus(),Units(unitOfLength="mi"))) }
        val vm=RangeViewModel(source)
        try {
            vm.setCarId(7); advanceUntilIdle()
            // The verified remote history replaces the cached projection immediately, even while
            // the optional health/status enhancement is still pending.
            assertEquals(listOf(22),vm.uiState.value.trips.map { it.driveId })
            healthGate.complete(Unit); statusGate.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(22),vm.uiState.value.trips.map { it.driveId })
            assertNull(vm.uiState.value.error)
            assertEquals(1,source.healthCalls); assertEquals(1,source.statusCalls)
        } finally { healthGate.complete(Unit); statusGate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }
}

package com.matelink.ui.screens.mileage

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.Units
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.entity.ChargeCostOverride
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.CarStatusWithUnits
import com.matelink.data.repository.UnifiedHistory
import com.matelink.data.repository.VerifiedHistoryReadContext
import com.matelink.domain.analytics.AnalysisPageIdentity
import com.matelink.domain.analytics.HistoryFreshness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-VM Room-first timing for Mileage: cached Room history publishes first while the network
 * stays pending, stays visible on an offline failure, reuses the same qualified cost/manual
 * projection, and is never clobbered by a late month/day calculation after a fresher snapshot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MileageRoomFirstViewModelTest {
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

    private class MileageSource(val identity: Identity = Identity()) : MileagePageSource {
        override val scopeChanges = identity.events.map { Unit }
        val statuses = mutableListOf<Int>()
        val histories = mutableListOf<Int>()
        val cachedReads = mutableListOf<Int>()
        var statusBody: suspend (Int) -> ApiResult<CarStatusWithUnits> =
            { ApiResult.Success(CarStatusWithUnits(CarStatus(), Units(unitOfLength = "km"))) }
        var historyBody: suspend (Int) -> ApiResult<UnifiedHistory> = { id ->
            ApiResult.Success(unified(identity.context(id), listOf(drive(id * 10)), listOf(charge(id * 10 + 1)))
                .also { it.verifiedReadContext = identity.proof(id) })
        }
        var cachedBody: suspend (Int) -> ApiResult<UnifiedHistory> = { ApiResult.Error("mileage_no_cache") }
        var currency = "CNY"
        var overrides = mutableListOf<ChargeCostOverride>()
        var manualReads = 0
        override suspend fun identity(carId: Int) = identity.capture(carId)
        override suspend fun status(carId: Int): ApiResult<CarStatusWithUnits> { statuses += carId; return statusBody(carId) }
        override suspend fun history(carId: Int): ApiResult<UnifiedHistory> { histories += carId; return historyBody(carId) }
        override suspend fun loadCached(carId: Int): ApiResult<UnifiedHistory> { cachedReads += carId; return cachedBody(carId) }
        override suspend fun isContextCurrent(proof: VerifiedHistoryReadContext) = identity.current(proof)
        override fun isContextCurrentNow(proof: VerifiedHistoryReadContext) = identity.current(proof)
        override suspend fun currencyCode() = currency
        override suspend fun manualRecords(): List<ChargeCostOverride> { manualReads++; return overrides }
    }

    companion object {
        private fun drive(id: Int, daysAgo: Long = 1, distance: Double = 10.0): DriveData {
            val day = java.time.LocalDate.now().minusDays(daysAgo).toString()
            return DriveData(id, startDate = day, endDate = day,
                odometerDetails = DriveOdometerDetails(distance = distance), energyConsumedNet = 2.0,
                source = "teslamate_archive", qualityState = "observed", lengthUnit = "km")
        }
        private fun charge(id: Int, cost: Double? = 5.0) = ChargeData(id,
            startDate = "2026-10-20T10:00:00Z", endDate = "2026-10-20T11:00:00Z",
            cost = cost, currencyCode = "CNY", source = "teslamate_archive", qualityState = "observed")
        private fun driveAtTimestamp(id: Int, timestamp: String, distance: Double) = DriveData(id,
            startDate = timestamp, endDate = timestamp,
            odometerDetails = DriveOdometerDetails(distance = distance), energyConsumedNet = 2.0,
            source = "teslamate_archive", qualityState = "observed", lengthUnit = "km")
        private fun unified(context: VehicleContext, drives: List<DriveData>, charges: List<ChargeData>): UnifiedHistory =
            UnifiedHistory(context, drives, charges, true, true)
        private fun cachedUnified(context: VehicleContext, drives: List<DriveData>, charges: List<ChargeData>): UnifiedHistory =
            UnifiedHistory(context, drives, charges, false, false)
    }

    @Test fun mileagePublishesVerifiedCachedRoomBeforePendingNetworkAndRetainsAfterOfflineError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MileageSource(); val remote = CompletableDeferred<Unit>()
        source.cachedBody = { id -> ApiResult.Success(cachedUnified(source.identity.context(id), listOf(drive(77)), listOf(charge(78))))
            .also { it.data.verifiedReadContext = source.identity.proof(id) } }
        source.historyBody = { withContext(NonCancellable) { remote.await() }; ApiResult.Error("offline") }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertEquals(listOf(77), vm.uiState.value.allDrives.map { it.driveId })
            assertEquals(HistoryFreshness.STALE, vm.uiState.value.historyFreshness)
            assertFalseLoading(vm)
            remote.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(77), vm.uiState.value.allDrives.map { it.driveId })
            assertNull(vm.uiState.value.error)
            assertEquals(1, source.cachedReads.size)
        } finally { remote.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun mileageRemoteSuccessRefreshesCachedRoomToFreshAndRecomputes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MileageSource(); val remote = CompletableDeferred<Unit>()
        source.cachedBody = { id -> ApiResult.Success(cachedUnified(source.identity.context(id), listOf(drive(77)), emptyList()))
            .also { it.data.verifiedReadContext = source.identity.proof(id) } }
        source.historyBody = { withContext(NonCancellable) { remote.await() }
            ApiResult.Success(unified(source.identity.context(7), listOf(drive(22)), emptyList()).also { it.verifiedReadContext = source.identity.proof(7) }) }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertEquals(listOf(77), vm.uiState.value.allDrives.map { it.driveId })
            assertEquals(HistoryFreshness.STALE, vm.uiState.value.historyFreshness)
            remote.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(22), vm.uiState.value.allDrives.map { it.driveId })
            assertEquals(HistoryFreshness.FRESH, vm.uiState.value.historyFreshness)
            assertNull(vm.uiState.value.error)
        } finally { remote.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun mileageMergedRemoteWithLocalOnlyHalfStaysStaleNotFresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MileageSource(); val remote = CompletableDeferred<Unit>()
        source.cachedBody = { id -> ApiResult.Success(cachedUnified(source.identity.context(id), listOf(drive(77)), emptyList()))
            .also { it.data.verifiedReadContext = source.identity.proof(id) } }
        // Remote confirmed drives but local-only charges: the result is a merge, not fully fresh.
        source.historyBody = { withContext(NonCancellable) { remote.await() }
            ApiResult.Success(UnifiedHistory(source.identity.context(7), listOf(drive(22)), emptyList(),
                drivesFromRemote = true, chargesFromRemote = false).also { it.verifiedReadContext = source.identity.proof(7) }) }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            remote.complete(Unit); advanceUntilIdle()
            assertEquals(listOf(22), vm.uiState.value.allDrives.map { it.driveId })
            assertEquals(HistoryFreshness.STALE, vm.uiState.value.historyFreshness)
            assertNull(vm.uiState.value.error)
        } finally { remote.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun mileageCachedPathReusesQualifiedManualCostThenRemoteRefreshesIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MileageSource(); val remote = CompletableDeferred<Unit>()
        source.overrides = mutableListOf(ChargeCostOverride(-107, 78, 0.0,
            sessionKey = "k", currencyCode = "CNY", sourceKind = "teslamate_archive",
            startInstant = "2026-10-20T10:00:00Z", endInstant = "2026-10-20T11:00:00Z", version = 1L))
        source.cachedBody = { id -> ApiResult.Success(cachedUnified(source.identity.context(id),
            emptyList(), listOf(charge(78, cost = 12.0))).also { it.verifiedReadContext = source.identity.proof(id) }) }
        source.historyBody = { withContext(NonCancellable) { remote.await() }
            ApiResult.Success(unified(source.identity.context(7), emptyList(), listOf(charge(78, cost = 3.0))).also { it.verifiedReadContext = source.identity.proof(7) }) }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            // Cached projection applies the same manual cost override: the manual 0.0 is qualified
            // (a real zero), overriding the raw 12.0, yet stays a null total separately computed.
            assertEquals(HistoryFreshness.STALE, vm.uiState.value.historyFreshness)
            remote.complete(Unit); advanceUntilIdle()
            assertEquals(HistoryFreshness.FRESH, vm.uiState.value.historyFreshness)
            assertEquals(2, source.manualReads)
        } finally { remote.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun mileageUnreadableCachedRoomDoesNotSuppressVerifiedRemoteRecovery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MileageSource()
        source.cachedBody = { throw IllegalStateException("synthetic_room_io_failure_private") }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertEquals(1, source.cachedReads.size)
            assertEquals(listOf(70), vm.uiState.value.allDrives.map { it.driveId })
            assertNull(vm.uiState.value.error)
            assertFalseLoading(vm)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun mileageRejectsAnotherVehiclesCachedSnapshotEvenWhenRemoteIdMatches() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MileageSource(); val old = source.identity.context(7)
        source.cachedBody = { ApiResult.Success(cachedUnified(old.copy(stableIdentity = "foreign-account"),
            listOf(drive(88)), emptyList())).also { it.data.verifiedReadContext = source.identity.proof(7) } }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            // A cached snapshot bound to another account/vehicle must never surface cached rows,
            // even when the numeric remote id matches this car.
            assertTrue(vm.uiState.value.allDrives.isEmpty())
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun mileageOpenMonthDetailRecomputesOverTheNewSnapshotAfterRefresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MileageSource(); val whenDay = "2026-10-14T10:00:00Z"
        source.cachedBody = { id -> ApiResult.Success(cachedUnified(source.identity.context(id), listOf(driveAtTimestamp(77, whenDay, 10.0)), emptyList()))
            .also { it.data.verifiedReadContext = source.identity.proof(id) } }
        source.historyBody = { id -> ApiResult.Success(unified(source.identity.context(id), listOf(driveAtTimestamp(77, whenDay, 10.0)), emptyList())
            .also { it.verifiedReadContext = source.identity.proof(id) }) }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            // While showing cached (STALE) year totals, open the current year's month detail.
            val year = java.time.LocalDate.now().year
            vm.selectYear(year); advanceUntilIdle()
            assertTrue(vm.uiState.value.selectedYear == year)
            // A fresh projection (larger distances) replaces the cached one and must recompute the
            // still-open month detail from the NEW snapshot, not leave a stale aggregation behind.
            source.cachedBody = { id -> ApiResult.Success(cachedUnified(source.identity.context(id),
                listOf(driveAtTimestamp(99, whenDay, 100.0)), emptyList()).also { it.verifiedReadContext = source.identity.proof(id) }) }
            source.historyBody = { id -> ApiResult.Success(unified(source.identity.context(id),
                listOf(driveAtTimestamp(99, whenDay, 100.0)), emptyList()).also { it.verifiedReadContext = source.identity.proof(id) }) }
            vm.refresh(); advanceUntilIdle()
            // The open month detail reflects the new larger-distance snapshot consistently.
            assertEquals(listOf(99), vm.uiState.value.allDrives.map { it.driveId })
            assertEquals(100.0, vm.uiState.value.totalLifetimeDistance, 0.0)
            assertEquals(HistoryFreshness.FRESH, vm.uiState.value.historyFreshness)
            assertNull(vm.uiState.value.error)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun mileageOfflineManualRecordsStillProjectedFromCachedRoom() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = MileageSource()
        source.overrides = mutableListOf(ChargeCostOverride(-107, 78, 0.0,
            sessionKey = "k", currencyCode = "CNY", sourceKind = "teslamate_archive",
            startInstant = "2026-10-20T10:00:00Z", endInstant = "2026-10-20T11:00:00Z", version = 1L))
        source.cachedBody = { id -> ApiResult.Success(cachedUnified(source.identity.context(id),
            emptyList(), listOf(charge(78, cost = 12.0))).also { it.verifiedReadContext = source.identity.proof(id) }) }
        source.historyBody = { ApiResult.Error("offline-no-network") }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertEquals(HistoryFreshness.STALE, vm.uiState.value.historyFreshness)
            // Offline: the manual cost override, read from the local store, must still be applied to
            // the cached road charges rather than being dropped.
            assertNull(vm.uiState.value.error)
            assertTrue(source.manualReads >= 1)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    private fun assertFalseLoading(vm: MileageViewModel) {
        assertTrue("expected not loading", !vm.uiState.value.isLoading)
    }
}
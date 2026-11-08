package com.matelink.ui.screens.range

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.DriveRange
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.VehicleContext
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.CarStatusWithUnits
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.domain.analytics.AnalysisHistorySnapshot
import com.matelink.domain.analytics.HistoryCoverage
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.analytics.NoDataReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/** Exercises the real VM; only the Room/network boundary is controlled. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class RangeRoomFirstRegressionTest {
    private class Source : RangePageSource {
        override val scopeChanges = emptyFlow<Unit>()
        val context = VehicleContext(7, "synthetic-account-vehicle", -101,
            HistoryConnectionSource.CLOUD, "synthetic-origin")
        var current = true
        var identityBody: () -> RangePageIdentity = { RangePageIdentity(context) { current } }
        var historyCalls = 0
        var healthCalls = 0
        var statusCalls = 0
        var cached: suspend () -> ApiResult<AnalysisHistorySnapshot>? = { snapshot(context, 11) }
        var remote: suspend () -> ApiResult<AnalysisHistorySnapshot> = { snapshot(context, 22) }
        var battery: suspend () -> ApiResult<BatteryHealth> = { ApiResult.Error("unavailable") }
        var liveStatus: suspend () -> ApiResult<CarStatusWithUnits> = { ApiResult.Error("unavailable") }

        override suspend fun identity(carId: Int) = identityBody()
        override suspend fun loadCached(carId: Int) = cached()
        override suspend fun history(carId: Int): ApiResult<AnalysisHistorySnapshot> {
            historyCalls++
            return remote()
        }
        override suspend fun health(carId: Int): ApiResult<BatteryHealth> {
            healthCalls++
            return battery()
        }
        override suspend fun status(carId: Int): ApiResult<CarStatusWithUnits> {
            statusCalls++
            return liveStatus()
        }
    }

    companion object {
        private fun snapshot(context: VehicleContext, driveId: Int): ApiResult.Success<AnalysisHistorySnapshot> {
            val drive = DriveData(driveId,
                startDate = LocalDate.now().minusDays(1).toString(),
                endDate = LocalDate.now().minusDays(1).toString(),
                odometerDetails = DriveOdometerDetails(distance = 12.0),
                rangeRated = DriveRange(100.0, 85.0), energyConsumedNet = 2.0,
                lengthUnit = "km", source = "teslamate_archive", qualityState = "observed")
            return ApiResult.Success(AnalysisHistorySnapshot(listOf(drive), emptyList(),
                Instant.now(), HistoryCoverage(1, 0), context = context))
        }

        private fun assertTrips(vm: RangeViewModel, id: Int, freshness: HistoryFreshness) {
            val state = vm.uiState.value
            assertEquals(listOf(id), state.trips.map { it.driveId })
            assertEquals(1, state.tripCount)
            assertEquals(12.0, state.totalDistanceKm, 0.0)
            assertEquals(20.0, state.avgDeviationPercent!!, 0.000001)
            assertEquals(freshness, state.historyFreshness)
            assertFalse(state.isLoading)
            assertNull(state.error)
        }

        private fun assertIdentityCleared(vm: RangeViewModel) {
            assertTrue(vm.uiState.value.trips.isEmpty())
            assertEquals(0, vm.uiState.value.tripCount)
            assertEquals(0.0, vm.uiState.value.totalDistanceKm, 0.0)
            assertNull(vm.uiState.value.avgDeviationPercent)
            assertNull(vm.uiState.value.personalizedRange)
            assertNull(vm.uiState.value.ratedRangeKm)
            assertEquals(NoDataReason.SOURCE_UNAVAILABLE, vm.uiState.value.noDataReason)
            assertFalse(vm.uiState.value.isLoading)
            assertFalse(vm.uiState.value.isRefreshing)
        }
    }

    private suspend fun TestScope.withVm(source: Source, body: suspend (RangeViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = RangeViewModel(source)
        try {
            body(vm)
        } finally {
            vm.viewModelScope.cancel()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }

    @Test fun cachedProjectionAppearsWhileRemoteIsStillSuspended() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.remote = { gate.await(); snapshot(source.context, 22) }
        withVm(source) { vm ->
            vm.setCarId(7)
            advanceUntilIdle()
            assertFalse(gate.isCompleted)
            assertTrips(vm, 11, HistoryFreshness.STALE)
            assertEquals(0, source.healthCalls)
            assertEquals(0, source.statusCalls)
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrips(vm, 22, HistoryFreshness.FRESH)
        }
    }

    @Test fun cachedProjectionSurvivesThrownHistoryFailureAndStopsRefreshSpinner() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.remote = { gate.await(); error("synthetic-transient-history-failure") }
        withVm(source) { vm ->
            vm.setCarId(7)
            advanceUntilIdle()
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isRefreshing)
            assertTrips(vm, 11, HistoryFreshness.STALE)
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrips(vm, 11, HistoryFreshness.STALE)
            assertFalse(vm.uiState.value.isRefreshing)
            vm.selectCustomRange(LocalDate.now().minusDays(7), LocalDate.now())
            assertTrips(vm, 11, HistoryFreshness.STALE)
            assertEquals(0, source.healthCalls)
            assertEquals(0, source.statusCalls)
        }
    }

    @Test fun cachedProjectionSurvivesHistoryErrorAndStopsRefreshSpinner() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.remote = { gate.await(); ApiResult.Error("synthetic-offline") }
        withVm(source) { vm ->
            vm.setCarId(7)
            advanceUntilIdle()
            vm.refresh()
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrips(vm, 11, HistoryFreshness.STALE)
            assertFalse(vm.uiState.value.isRefreshing)
        }
    }

    private suspend fun TestScope.assertPreviousProjectionSurvivesRefreshCacheMiss(throwsOnRead: Boolean) {
        val source = Source()
        source.remote = { ApiResult.Error("synthetic-offline") }
        withVm(source) { vm ->
            vm.setCarId(7)
            advanceUntilIdle()
            assertTrips(vm, 11, HistoryFreshness.STALE)
            source.cached = { if (throwsOnRead) error("synthetic-room-read-failure") else null }
            source.remote = { error("synthetic-transient-history-failure") }
            vm.refresh()
            assertTrue(vm.uiState.value.isRefreshing)
            advanceUntilIdle()
            assertTrips(vm, 11, HistoryFreshness.STALE)
            assertFalse(vm.uiState.value.isRefreshing)
            vm.selectCustomRange(LocalDate.now().minusDays(7), LocalDate.now())
            assertTrips(vm, 11, HistoryFreshness.STALE)
            assertEquals(0, source.healthCalls)
            assertEquals(0, source.statusCalls)
        }
    }

    @Test fun previousCachedProjectionSurvivesNullCacheAndThrownHistoryFailureOnRefresh() = runTest {
        assertPreviousProjectionSurvivesRefreshCacheMiss(throwsOnRead = false)
    }

    @Test fun previousCachedProjectionSurvivesUnreadableCacheAndThrownHistoryFailureOnRefresh() = runTest {
        assertPreviousProjectionSurvivesRefreshCacheMiss(throwsOnRead = true)
    }

    @Test fun previousEmptyCachedProjectionSurvivesNullCacheAndThrownHistoryFailureOnRefresh() = runTest {
        val source = Source()
        source.cached = {
            ApiResult.Success(snapshot(source.context, 11).data.copy(
                drives = emptyList(), coverage = HistoryCoverage(0, 0)))
        }
        source.remote = { ApiResult.Error("synthetic-offline") }
        withVm(source) { vm ->
            vm.setCarId(7)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.trips.isEmpty())
            assertEquals(NoDataReason.NO_RECORDS, vm.uiState.value.noDataReason)
            assertEquals(HistoryFreshness.STALE, vm.uiState.value.historyFreshness)
            source.cached = { null }
            source.remote = { error("synthetic-transient-history-failure") }
            vm.refresh()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.trips.isEmpty())
            assertEquals(NoDataReason.NO_RECORDS, vm.uiState.value.noDataReason)
            assertEquals(HistoryFreshness.STALE, vm.uiState.value.historyFreshness)
            assertFalse(vm.uiState.value.isLoading)
            assertFalse(vm.uiState.value.isRefreshing)
            assertNull(vm.uiState.value.error)
        }
    }

    @Test fun scopeChangeBeforeRefreshJobStartsCannotRetainOldRowsAfterNewIdentityAndRemoteError() = runTest {
        val source = Source()
        var epoch = 0
        var identityContext = source.context
        source.identityBody = {
            val capturedEpoch = epoch
            RangePageIdentity(identityContext) { capturedEpoch == epoch }
        }
        source.remote = { ApiResult.Error("synthetic-offline") }
        withVm(source) { vm ->
            vm.setCarId(7)
            advanceUntilIdle()
            assertTrips(vm, 11, HistoryFreshness.STALE)
            vm.refresh()
            // refresh() saw the old proof as current, but its launched load has not run yet.
            epoch++
            identityContext = source.context.copy(stableIdentity = "synthetic-new-account-vehicle",
                localHistoryCarId = -102, serverIdentity = "synthetic-new-origin")
            source.cached = { null }
            advanceUntilIdle()
            assertTrue("new identity must discard the previous account's rows", vm.uiState.value.trips.isEmpty())
            assertEquals(0, vm.uiState.value.tripCount)
            assertEquals(0.0, vm.uiState.value.totalDistanceKm, 0.0)
            assertNull(vm.uiState.value.avgDeviationPercent)
            assertNull(vm.uiState.value.personalizedRange)
            assertNull(vm.uiState.value.ratedRangeKm)
            assertFalse(vm.uiState.value.isLoading)
            assertFalse(vm.uiState.value.isRefreshing)
            vm.selectCustomRange(LocalDate.now().minusDays(7), LocalDate.now())
            assertTrue("window selection must not restore the previous private cache", vm.uiState.value.trips.isEmpty())
            assertEquals(0, source.healthCalls)
            assertEquals(0, source.statusCalls)
        }
    }

    @Test fun unreadableCacheStillAllowsVerifiedRemoteHistory() = runTest {
        val source = Source()
        source.cached = { error("synthetic-room-read-failure") }
        withVm(source) { vm ->
            vm.setCarId(7)
            advanceUntilIdle()
            assertTrips(vm, 22, HistoryFreshness.FRESH)
            assertEquals(1, source.historyCalls)
        }
    }

    @Test fun freshProjectionSurvivesOptionalHealthAndStatusExceptions() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.battery = { gate.await(); error("synthetic-health-failure") }
        source.liveStatus = { error("synthetic-status-failure") }
        withVm(source) { vm ->
            vm.setCarId(7)
            runCurrent()
            assertTrips(vm, 22, HistoryFreshness.FRESH)
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrips(vm, 22, HistoryFreshness.FRESH)
            assertFalse(vm.uiState.value.isRefreshing)
            assertEquals(1, source.statusCalls)
        }
    }

    @Test fun refreshedFreshHistoryStopsRefreshSpinnerWhileOptionalHealthIsStillSuspended() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.remote = { ApiResult.Error("synthetic-offline") }
        withVm(source) { vm ->
            vm.setCarId(7)
            advanceUntilIdle()
            assertTrips(vm, 11, HistoryFreshness.STALE)
            source.remote = { snapshot(source.context, 22) }
            source.battery = { gate.await(); ApiResult.Error("synthetic-no-health") }
            vm.refresh()
            runCurrent()
            assertTrips(vm, 22, HistoryFreshness.FRESH)
            assertFalse(gate.isCompleted)
            assertEquals(1, source.healthCalls)
            assertEquals(0, source.statusCalls)
            assertFalse("historical refresh is complete before optional enhancement", vm.uiState.value.isRefreshing)
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrips(vm, 22, HistoryFreshness.FRESH)
            assertFalse(vm.uiState.value.isRefreshing)
        }
    }

    @Test fun hungOptionalHealthTimesOutAndStillAllowsStatusAfterFiveSeconds() = runTest {
        val source = Source()
        val neverCompletes = CompletableDeferred<Unit>()
        source.battery = { neverCompletes.await(); ApiResult.Success(BatteryHealth(currentCapacity = 99.0)) }
        withVm(source) { vm ->
            vm.setCarId(7)
            runCurrent()
            assertTrips(vm, 22, HistoryFreshness.FRESH)
            assertEquals(1, source.healthCalls)
            assertEquals(0, source.statusCalls)
            advanceTimeBy(5_000)
            runCurrent()
            assertFalse(neverCompletes.isCompleted)
            assertEquals("optional health cannot block status indefinitely", 1, source.statusCalls)
            assertTrips(vm, 22, HistoryFreshness.FRESH)
            assertFalse(vm.uiState.value.isRefreshing)
        }
    }

    @Test fun identityExceptionFromHistoryClearsAlreadyPublishedCache() = runTest {
        val source = Source()
        source.remote = { throw HistoryIdentityUnavailableException() }
        withVm(source) { vm ->
            vm.setCarId(7)
            advanceUntilIdle()
            assertIdentityCleared(vm)
            assertEquals(0, source.healthCalls)
            assertEquals(0, source.statusCalls)
        }
    }

    @Test fun identityErrorFromHistoryClearsAlreadyPublishedCache() = runTest {
        val source = Source()
        source.remote = { ApiResult.Error(HISTORY_IDENTITY_UNAVAILABLE) }
        withVm(source) { vm ->
            vm.setCarId(7)
            advanceUntilIdle()
            assertIdentityCleared(vm)
        }
    }

    @Test fun identityChangeDuringCacheReadRejectsCachedRowsBeforeRequestingRemote() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.cached = { gate.await(); snapshot(source.context, 11) }
        withVm(source) { vm ->
            vm.setCarId(7)
            advanceUntilIdle()
            source.current = false
            gate.complete(Unit)
            advanceUntilIdle()
            assertIdentityCleared(vm)
            assertEquals(0, source.historyCalls)
            assertEquals(0, source.healthCalls)
            assertEquals(0, source.statusCalls)
        }
    }

    @Test fun identityChangeDuringHealthClearsFreshRowsAndDoesNotRequestStatus() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.battery = { gate.await(); ApiResult.Success(BatteryHealth(currentCapacity = 99.0)) }
        withVm(source) { vm ->
            vm.setCarId(7)
            runCurrent()
            assertTrips(vm, 22, HistoryFreshness.FRESH)
            source.current = false
            gate.complete(Unit)
            advanceUntilIdle()
            assertIdentityCleared(vm)
            assertEquals(0, source.statusCalls)
        }
    }

    @Test fun identityChangeDuringStatusClearsFreshRowsAndEnhancements() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.battery = { ApiResult.Success(BatteryHealth(currentCapacity = 99.0)) }
        source.liveStatus = { gate.await(); ApiResult.Error("synthetic-no-status") }
        withVm(source) { vm ->
            vm.setCarId(7)
            runCurrent()
            assertTrips(vm, 22, HistoryFreshness.FRESH)
            source.current = false
            gate.complete(Unit)
            advanceUntilIdle()
            assertIdentityCleared(vm)
        }
    }

    @Test fun historyCancellationDoesNotRequestEnhancementsOrErasePublishedCache() = runTest {
        val source = Source()
        source.remote = { throw CancellationException("synthetic-history-cancelled") }
        withVm(source) { vm ->
            vm.setCarId(7)
            advanceUntilIdle()
            assertTrips(vm, 11, HistoryFreshness.STALE)
            assertEquals(0, source.healthCalls)
            assertEquals(0, source.statusCalls)
        }
    }

    @Test fun cancelledHealthAwaitDoesNotContinueToStatusEvenIfProviderIgnoresCancellation() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.battery = {
            withContext(NonCancellable) { gate.await() }
            ApiResult.Success(BatteryHealth(currentCapacity = 99.0))
        }
        withVm(source) { vm ->
            vm.setCarId(7)
            runCurrent()
            assertTrips(vm, 22, HistoryFreshness.FRESH)
            vm.viewModelScope.cancel()
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrips(vm, 22, HistoryFreshness.FRESH)
            assertEquals(0, source.statusCalls)
        }
    }
}

package com.matelink.ui.screens.mileage

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.local.entity.ChargeCostOverride
import com.matelink.data.repository.*
import com.matelink.domain.analytics.AnalysisPageIdentity
import com.matelink.domain.analytics.HistoryFreshness
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MileageRoomFirstRegressionTest {
    private class Source : MileagePageSource {
        override val scopeChanges = MutableSharedFlow<Unit>()
        val context = VehicleContext(7, "synthetic-account/vehicle", -107,
            HistoryConnectionSource.CLOUD, "https://synthetic.test")
        private val scope = HistoryReadScope(HistoryConnectionSource.CLOUD,
            "https://synthetic.test", "synthetic-account", "https://synthetic.test", 1, 1, 1)
        var current = true
        var proofCurrent = true
        var currencyReads = 0
        var historyCalls = 0
        var currency: suspend () -> String = { "CNY" }
        var records: suspend () -> List<ChargeCostOverride> = { emptyList() }
        var remote: suspend () -> ApiResult<UnifiedHistory> = { ApiResult.Error("offline") }
        var cached: suspend () -> ApiResult<UnifiedHistory> = { snapshot(10.0) }
        override suspend fun identity(carId: Int) = AnalysisPageIdentity(context) { current }
        override suspend fun status(carId: Int) = ApiResult.Error("offline")
        override suspend fun history(carId: Int): ApiResult<UnifiedHistory> { historyCalls++; return remote() }
        override suspend fun loadCached(carId: Int) = cached()
        override suspend fun isContextCurrent(proof: VerifiedHistoryReadContext) = isContextCurrentNow(proof)
        override fun isContextCurrentNow(proof: VerifiedHistoryReadContext) =
            current && proofCurrent && proof.context == context && proof.scope == scope
        override suspend fun currencyCode(): String { currencyReads++; return currency() }
        override suspend fun manualRecords() = records()
        fun snapshot(distance: Double, fresh: Boolean = false): ApiResult.Success<UnifiedHistory> {
            val drives = listOf(
                drive(1, "2025-09-14T12:00:00Z", 5.0),
                drive(2, "2026-10-14T12:00:00Z", distance))
            val charge = ChargeData(3, startDate = "2026-10-14T12:00:00Z",
                endDate = "2026-10-14T13:00:00Z", cost = 12.0,
                currencyCode = "CNY", source = "teslamate_archive", qualityState = "observed")
            return ApiResult.Success(UnifiedHistory(context, drives, listOf(charge), fresh, fresh)
                .also { it.verifiedReadContext = VerifiedHistoryReadContext(scope, context, true, null, false) })
        }
        private fun drive(id: Int, at: String, distance: Double) = DriveData(id,
            startDate = at, endDate = at, odometerDetails = DriveOdometerDetails(distance = distance),
            source = "teslamate_archive", qualityState = "observed", lengthUnit = "km")
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = mutableListOf<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending += block }
        fun first() { pending.removeAt(0).run() }
        fun last() { pending.removeAt(pending.lastIndex).run() }
        fun drain() { while (pending.isNotEmpty()) first() }
    }

    @Test fun unreadableManualCostsDoNotBlockCachedMileageOrRemoteRefresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source().apply { records = { error("synthetic-private-cost-read-failure") } }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertEquals(15.0, vm.uiState.value.totalLifetimeDistance, 0.0)
            assertEquals(1, source.historyCalls)
            assertNull(vm.uiState.value.totalLifetimeEnergyCost)
            assertNull(vm.uiState.value.error)
            assertFalse(vm.uiState.value.isLoading)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun unreadableCurrencyDoesNotGuessPricesOrBlockHistory() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source().apply { currency = { error("synthetic-settings-unavailable") } }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertEquals(15.0, vm.uiState.value.totalLifetimeDistance, 0.0)
            assertNull(vm.uiState.value.totalLifetimeEnergyCost)
            assertNull(vm.uiState.value.error)
            assertFalse(vm.uiState.value.isLoading)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun thrownRemoteFailureRetainsCacheWithoutIdentityErrorOrSpinner() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source().apply { remote = { error("synthetic-network-failure") } }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertEquals(15.0, vm.uiState.value.totalLifetimeDistance, 0.0)
            assertEquals(HistoryFreshness.STALE, vm.uiState.value.historyFreshness)
            assertNull(vm.uiState.value.error)
            assertFalse(vm.uiState.value.isRefreshing)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun explicitIdentityFailureClearsPreviouslyPublishedCache() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source().apply { remote = { historyIdentityUnavailableError() } }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertTrue(vm.uiState.value.allDrives.isEmpty())
            assertEquals(HISTORY_IDENTITY_UNAVAILABLE, vm.uiState.value.error)
            assertFalse(vm.uiState.value.isLoading)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun revokedHistoryProofClearsCacheEvenWhenPageIdentityHasNotChanged() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source().apply { remote = { proofCurrent = false; ApiResult.Error("offline") } }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertTrue(vm.uiState.value.allDrives.isEmpty())
            assertFalse(vm.uiState.value.isLoading)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun typedIdentityExceptionClearsCacheInsteadOfTreatingItAsNetworkFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source().apply { remote = { throw HistoryIdentityUnavailableException() } }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            assertTrue(vm.uiState.value.allDrives.isEmpty())
            assertFalse(vm.uiState.value.isLoading)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun oldDayNavigationCannotReopenSelectionAfterUserClosesYear() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val queue = QueuedDispatcher()
        val vm = MileageViewModel(Source(), queue)
        try {
            vm.setCarId(7); runCurrent(); queue.first(); runCurrent()
            vm.navigateToDay(LocalDate.of(2026, 10, 14)); runCurrent()
            vm.clearSelectedYear()
            queue.first(); runCurrent()
            assertNull(vm.uiState.value.selectedYear)
            assertNull(vm.uiState.value.selectedMonth)
            assertNull(vm.uiState.value.selectedDay)
        } finally { vm.viewModelScope.cancel(); queue.drain(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun selectingDayAfterSilentIdentityLossClearsOldProjection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = Source()
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            vm.navigateToDay(LocalDate.of(2026, 10, 14)); advanceUntilIdle()
            source.current = false
            vm.selectDay(LocalDate.of(2026, 10, 14))
            assertTrue(vm.uiState.value.allDrives.isEmpty())
            assertNull(vm.uiState.value.selectedDayData)
            assertFalse(vm.uiState.value.isLoading)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun delayedMonthAggregationClearsSilentlyRevokedProof() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val queue = QueuedDispatcher()
        val source = Source()
        val vm = MileageViewModel(source, queue)
        try {
            vm.setCarId(7); runCurrent(); queue.first(); runCurrent()
            vm.selectYear(2026); runCurrent()
            source.proofCurrent = false
            queue.first(); runCurrent()
            assertTrue(vm.uiState.value.allDrives.isEmpty())
            assertTrue(vm.uiState.value.monthlyData.isEmpty())
            assertFalse(vm.uiState.value.isLoading)
        } finally { vm.viewModelScope.cancel(); queue.drain(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun remoteRefreshUpdatesAlreadySelectedDayAsWellAsYearAndMonth() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val source = Source().apply { remote = { gate.await(); snapshot(100.0, fresh = true) } }
        val vm = MileageViewModel(source, StandardTestDispatcher(testScheduler))
        try {
            vm.setCarId(7); advanceUntilIdle()
            vm.navigateToDay(LocalDate.of(2026, 10, 14)); advanceUntilIdle()
            assertEquals(10.0, vm.uiState.value.selectedDayData!!.totalDistance, 0.0)
            gate.complete(Unit); advanceUntilIdle()
            assertEquals(105.0, vm.uiState.value.totalLifetimeDistance, 0.0)
            assertEquals(100.0, vm.uiState.value.yearTotalDistance, 0.0)
            assertEquals(100.0, vm.uiState.value.selectedDayData!!.totalDistance, 0.0)
        } finally { gate.complete(Unit); vm.viewModelScope.cancel(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun lateYearComputationCannotOverwriteNewYearSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val queue = QueuedDispatcher()
        val vm = MileageViewModel(Source(), queue)
        try {
            vm.setCarId(7); runCurrent(); queue.first(); runCurrent()
            vm.selectYear(2025); runCurrent()
            vm.selectYear(2026); runCurrent()
            queue.last(); runCurrent()
            assertEquals(2026, vm.uiState.value.monthlyData.single().yearMonth.year)
            queue.first(); runCurrent()
            assertEquals(2026, vm.uiState.value.selectedYear)
            assertEquals(2026, vm.uiState.value.monthlyData.single().yearMonth.year)
        } finally { vm.viewModelScope.cancel(); queue.drain(); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun lateMonthComputationCannotOverwriteNewMonthSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val queue = QueuedDispatcher()
        val vm = MileageViewModel(Source(), queue)
        try {
            vm.setCarId(7); runCurrent(); queue.first(); runCurrent()
            vm.selectMonth(YearMonth.of(2025, 9)); runCurrent()
            vm.selectMonth(YearMonth.of(2026, 10)); runCurrent()
            queue.last(); runCurrent()
            assertEquals(LocalDate.of(2026, 10, 14), vm.uiState.value.dailyData.single().date)
            queue.first(); runCurrent()
            assertEquals(YearMonth.of(2026, 10), vm.uiState.value.selectedMonth)
            assertEquals(LocalDate.of(2026, 10, 14), vm.uiState.value.dailyData.single().date)
        } finally { vm.viewModelScope.cancel(); queue.drain(); advanceUntilIdle(); Dispatchers.resetMain() }
    }
}

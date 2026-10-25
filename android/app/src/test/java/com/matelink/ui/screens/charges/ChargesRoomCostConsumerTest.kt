package com.matelink.ui.screens.charges

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.HistoryReadDependencies
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.ChargeCostSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Real Room receipt -> history resolver -> charge list VM, with provider discovery offline. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ChargesRoomCostConsumerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun TestScope.awaitCharges(vm: ChargesViewModel, ids: List<Int>) {
        repeat(100) {
            advanceUntilIdle()
            if (vm.uiState.value.charges.map { charge -> charge.chargeId }.sorted() == ids.sorted() &&
                !vm.uiState.value.isLoading && !vm.uiState.value.isFilterLoading) return
            Thread.sleep(20)
        }
        throw AssertionError("Expected charge IDs $ids; actual ${vm.uiState.value.charges.map { it.chargeId }}")
    }

    private fun viewModel(f: HistoryResourceFixture): ChargesViewModel {
        val history = UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { f.vehicleContexts.captureReadScope() },
            getCars = { ApiResult.Error("synthetic-offline", 503) },
            getHistoryContext = { ApiResult.Error("synthetic-offline", 503) },
            resolveCar = { car, scope -> f.vehicleContexts.resolveVerifiedHistoryCar(car, scope) },
            cachedContext = { id, scope -> f.vehicleContexts.cachedContextForRemote(id, scope) },
            localDrives = { f.db.driveSummaryDao().getAllChronological(it) },
            localCharges = { f.db.chargeSummaryDao().getAllForCar(it) },
            getDrives = { _, _, _, _ -> error("offline history must use Room") },
            getCharges = { _, _, _, _ -> error("offline history must use Room") },
            persistDrives = {}, persistCharges = {},
            scopeChanges = f.vehicleContexts.historyScopeChanges
        ))
        return ChargesViewModel(f.context, f.apiRepo, history, f.db.chargeSummaryDao(),
            f.settings, ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings),
            f.db.aggregateDao(), f.vehicleContexts,
            Clock.fixed(Instant.parse("2026-10-25T08:00:00Z"), ZoneId.of("Asia/Shanghai")),
            SavedStateHandle())
    }

    @Test fun reopenedOfflineRoomChargesKeepCardSummaryChartAndCurrencyBoundaryConsistent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = HistoryResourceFixture(File(temp.root, "charges-cost-reopen.db"))
        f.initialize(cloud = true)
        f.settings.saveCurrency("CNY")
        f.settings.saveDefaultChargePrice(1.14)
        assertEquals("CNY", f.settings.settings.first().currencyCode)
        val localId = f.localId(1)
        val start = "2026-10-20T10:00:00Z"
        val end = "2026-10-20T11:00:00Z"
        f.db.chargeSummaryDao().upsert(requireNotNull(ChargeData(64,
            startDate = start, endDate = end, chargeEnergyAdded = 2.0,
            source = "teslamate_archive", sourceInstanceId = "archive-A",
            qualityState = "observed", qualityReason = "api").toSyncSummary(localId)))
        f.db.chargeSummaryDao().upsert(requireNotNull(ChargeData(65,
            startDate = start, endDate = end, chargeEnergyAdded = 8.0,
            cost = 9.0, currencyCode = "USD",
            source = "teslamate_archive", sourceInstanceId = "archive-A",
            qualityState = "observed", qualityReason = "api").toSyncSummary(localId)))
        f.reopen()
        f.status = { 503 }

        val vm = viewModel(f)
        try {
            vm.setCarId(1)
            awaitCharges(vm, listOf(64, 65))
            val state = vm.uiState.value
            assertEquals("state=$state", 2, state.charges.size)
            assertEquals(2, state.summary.totalCharges)
            assertEquals(2.28, state.effectiveCosts[64]?.cost ?: Double.NaN, 0.0001)
            assertEquals(ChargeCostSource.ESTIMATE, state.effectiveCosts[64]?.source)
            assertNull(state.effectiveCosts[65]?.cost)
            assertEquals(2.28, state.summary.totalCost ?: Double.NaN, 0.0001)
            assertEquals(2.28, state.summary.avgCostPerCharge ?: Double.NaN, 0.0001)
            assertEquals(1, state.summary.costCoverage)
            assertEquals(1, state.summary.estimatedCostCount)
            assertEquals(2.28, state.chartData.sumOf { it.totalCost }, 0.0001)
            assertEquals(1, state.chartData.sumOf { it.estimatedCostCount })
        } finally {
            vm.viewModelScope.cancel()
            advanceUntilIdle()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun changingFromRecentToAnOldDayReadsScopedRoomAgainWithoutNetwork() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = HistoryResourceFixture(File(temp.root, "charges-old-day-reopen.db"))
        f.initialize(cloud = true)
        f.settings.saveCurrency("CNY")
        f.settings.saveDefaultChargePrice(1.14)
        val localId = f.localId(1)
        for ((id, date, energy) in listOf(
            Triple(71, "2026-10-24T10:00:00Z", 2.0),
            Triple(72, "2026-09-20T10:00:00Z", 3.0)
        )) {
            f.db.chargeSummaryDao().upsert(requireNotNull(ChargeData(id,
                startDate = date, endDate = date, chargeEnergyAdded = energy,
                source = "teslamate_archive", sourceInstanceId = "archive-A",
                qualityState = "observed", qualityReason = "api").toSyncSummary(localId)))
        }
        f.reopen()
        f.status = { 503 }
        val vm = viewModel(f)
        try {
            vm.setCarId(1)
            awaitCharges(vm, listOf(71))
            assertEquals(listOf(71), vm.uiState.value.charges.map { it.chargeId })
            assertEquals(1, vm.uiState.value.summary.totalCharges)

            val oldDay = LocalDate.of(2026, 9, 20)
            vm.setCustomDateRange(oldDay, oldDay)
            awaitCharges(vm, listOf(72))
            val old = vm.uiState.value
            assertEquals(DateFilter.CUSTOM, old.selectedFilter)
            assertEquals(listOf(72), old.charges.map { it.chargeId })
            assertEquals(1, old.summary.totalCharges)
            assertEquals(3.42, old.summary.totalCost ?: Double.NaN, 0.0001)
            assertEquals(1, old.chartData.size)
            assertEquals(1, old.chartData.single().count)

            vm.setDateFilter(DateFilter.LAST_7_DAYS)
            awaitCharges(vm, listOf(71))
            assertEquals(listOf(71), vm.uiState.value.charges.map { it.chargeId })
            val recentStart = vm.uiState.value.startDate
            vm.loadMore()
            awaitCharges(vm, listOf(71))
            assertEquals(DateFilter.CUSTOM, vm.uiState.value.selectedFilter)
            assertEquals(recentStart?.minusDays(30), vm.uiState.value.customStartDate)
            assertEquals(vm.uiState.value.endDate, vm.uiState.value.customEndDate)
        } finally {
            vm.viewModelScope.cancel()
            advanceUntilIdle()
            f.close()
            Dispatchers.resetMain()
        }
    }
}

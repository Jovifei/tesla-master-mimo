package com.matelink.ui.screens.cost

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.HistoryContextData
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.data.repository.HistoryReadDependencies
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.analytics.projectEffectiveChargeCost
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.toRawAnalysisChargeData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CostRoomFirstViewModelTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun aKnownForeignBillCannotBecomeAnUnpricedLocalCurrencyEstimate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture("cost-foreign-bill-estimate")
        f.db.chargeSummaryDao().upsert(ChargeData(64,
            startDate = "2026-10-20T10:00:00Z", endDate = "2026-10-20T11:00:00Z",
            chargeEnergyAdded = 8.0, cost = 12.0, currencyCode = "USD", source = "teslamate_archive",
            qualityState = "observed", qualityReason = "api").toSyncSummary(f.localId(1))!!)
        val vm = viewModel(f, { "synthetic-vehicle-1" })
        try {
            vm.load(1); advanceUntilIdle()
            val state = awaitState(vm) { !it.loading && it.totalCharges == 1 }
            assertNull(state.totalCost)
            assertEquals(0, state.estimatedCoverage)
            assertNull(state.estimatedCost)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); f.close(); Dispatchers.resetMain() }
    }

    private suspend fun fixture(name: String): HistoryResourceFixture {
        val f = HistoryResourceFixture(File(temp.root, "$name.db"))
        f.initialize(cloud = true)
        f.settings.saveCurrency("CNY")
        assertEquals("CNY", f.settings.settings.first().currencyCode)
        val localId = f.localId(1)
        val charge = ChargeData(64,
            startDate = "2026-10-20T10:00:00Z", endDate = "2026-10-20T11:00:00Z",
            cost = 12.0, currencyCode = "CNY", source = "teslamate_archive",
            qualityState = "observed", qualityReason = "api")
        f.db.chargeSummaryDao().upsert(requireNotNull(charge.toSyncSummary(localId)))
        val stored = requireNotNull(f.db.chargeSummaryDao().get(localId, 64)).toAnalysisChargeData()
        assertEquals(12.0, stored.cost!!, 0.0)
        assertEquals(12.0, projectEffectiveChargeCost(stored, localId, null, "CNY").cost!!, 0.0)
        return f
    }

    private fun viewModel(
        f: HistoryResourceFixture,
        uid: () -> String,
        persistCharges: suspend () -> Unit = {},
        onOnlineIdentity: () -> Unit = {},
        remoteCharges: (suspend () -> List<ChargeData>)? = null
    ): CostViewModel {
        val deps = HistoryReadDependencies(
            captureScope = { f.vehicleContexts.captureReadScope() },
            getCars = { ApiResult.Success(listOf(CarData(1, vehicleUid = uid()))) },
            getHistoryContext = { id ->
                onOnlineIdentity()
                ApiResult.Success(HistoryContextData(1, id, uid()))
            },
            resolveCar = { car, scope -> f.vehicleContexts.resolveVerifiedHistoryCar(car, scope) },
            cachedContext = { id, scope -> f.vehicleContexts.cachedContextForRemote(id, scope) },
            localDrives = { f.db.driveSummaryDao().getAllChronological(it) },
            localCharges = { f.db.chargeSummaryDao().getAllForCar(it) },
            getDrives = { _, _, _, _ -> ApiResult.Success(emptyList()) },
            getCharges = { _, _, _, _ -> ApiResult.Success(remoteCharges?.invoke() ?: emptyList()) },
            persistDrives = {},
            persistCharges = { persistCharges() },
            scopeChanges = f.vehicleContexts.historyScopeChanges
        )
        val history = AnalysisHistoryRepository(UnifiedHistoryRepository(deps), f.vehicleContexts)
        return CostViewModel(history, f.db.aggregateDao(),
            ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings),
            f.settings, f.vehicleContexts)
    }

    private suspend fun awaitState(vm: CostViewModel, predicate: (CostUiState) -> Boolean): CostUiState =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(15_000) { vm.uiState.first { predicate(it) } }
        }

    private suspend fun awaitGate(gate: CompletableDeferred<Unit>) =
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(15_000) { gate.await() }
        }

    @Test fun cachedTotalsSurviveTransientPersistenceFailureAfterTheyWerePublished() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture("cost-temporary-failure")
        lateinit var vm: CostViewModel
        var amountAtFailure: Double? = null
        val reachedFailure = CompletableDeferred<Unit>()
        vm = viewModel(f, { "synthetic-vehicle-1" }, persistCharges = {
            amountAtFailure = vm.uiState.value.totalCost
            reachedFailure.complete(Unit)
            error("synthetic-transient-dao-failure")
        })
        try {
            vm.load(1)
            advanceUntilIdle()
            awaitGate(reachedFailure)
            advanceUntilIdle()
            val state = vm.uiState.value
            assertEquals("state=$state", 12.0, amountAtFailure ?: Double.NaN, 0.0)
            assertEquals(12.0, state.totalCost!!, 0.0)
            assertEquals(1, state.totalCharges)
            assertEquals(HistoryFreshness.STALE, state.historyFreshness)
            assertNull(state.error)
        } finally {
            vm.viewModelScope.cancel()
            advanceUntilIdle()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun freshUidProofCanReplaceOpaqueCachedContextForTheSameLocalCar() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture("cost-same-car-fresh")
        lateinit var vm: CostViewModel
        var cachedAtOnline: Double? = null
        val reachedOnline = CompletableDeferred<Unit>()
        vm = viewModel(f, { "synthetic-vehicle-1" }, onOnlineIdentity = {
            cachedAtOnline = vm.uiState.value.totalCost
            reachedOnline.complete(Unit)
        }, remoteCharges = {
            f.db.chargeSummaryDao().getAllForCar(f.localId(1)).map { it.toRawAnalysisChargeData() }
        })
        try {
            vm.load(1)
            advanceUntilIdle()
            awaitGate(reachedOnline)
            assertEquals(12.0, cachedAtOnline!!, 0.0)
            val state = awaitState(vm) { !it.loading && it.historyFreshness == HistoryFreshness.FRESH }
            assertEquals("state=$state", 12.0, state.totalCost ?: Double.NaN, 0.0)
            assertEquals(HistoryFreshness.FRESH, state.historyFreshness)
            assertNull(state.error)
        } finally {
            vm.viewModelScope.cancel()
            advanceUntilIdle()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun sameNumericCarReboundToAnotherUidClearsCachedCost() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture("cost-uid-rebind")
        lateinit var vm: CostViewModel
        var cachedBeforeRebind: Double? = null
        val reachedOnline = CompletableDeferred<Unit>()
        vm = viewModel(f, { "replacement-vehicle-uid" },
            onOnlineIdentity = {
                cachedBeforeRebind = vm.uiState.value.totalCost
                reachedOnline.complete(Unit)
            })
        try {
            vm.load(1)
            advanceUntilIdle()
            awaitGate(reachedOnline)
            val state = awaitState(vm) { !it.loading && it.error == HISTORY_IDENTITY_UNAVAILABLE }
            assertEquals("state=$state", 12.0, cachedBeforeRebind ?: Double.NaN, 0.0)
            assertNull(state.totalCost)
            assertEquals(0, state.totalCharges)
            assertEquals(HISTORY_IDENTITY_UNAVAILABLE, state.error)
        } finally {
            vm.viewModelScope.cancel()
            advanceUntilIdle()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun partialCounterRoomReceiptPublishesSeparateCostWithoutInventingWholeEnergy() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture("cost-partial-observed")
        f.settings.saveDefaultChargePrice(1.5)
        f.settings.saveTariffConfig(false, 1.0, 0.7, 0.3, "[[10,14],[18,20]]", "[[7,9],[15,17],[21,22]]", "[[23,23],[0,6]]")
        val start = "2026-10-20T12:00:00Z"
        val end = "2026-10-20T13:00:00Z"
        val row = ChargeData(65, startDate = start, endDate = end, source = "telemetry_mqtt",
            qualityState = "incomplete", qualityReason = "partial_or_missing_charge_counter",
            energyContract = com.matelink.data.api.models.EnergyContract(batteryInput =
                com.matelink.data.api.models.EnergyMetric(method = "session_counter_delta",
                    measurementPoint = "battery_input", source = "telemetry_mqtt", sourceField = "DCChargingEnergyIn",
                    quality = "unknown", reason = "partial_counter_window", startDate = start, endDate = end,
                    observedStartAt = "2026-10-20T12:05:00Z", observedEndAt = "2026-10-20T12:55:00Z",
                    timeBasis = "collector_received_at", coverageKind = "endpoints",
                    coverageRatio = 5.0 / 6.0, coveredEnergyKwh = 10.0)))
        f.db.chargeSummaryDao().upsert(requireNotNull(row.toSyncSummary(f.localId(1))))
        val vm = viewModel(f, { "synthetic-vehicle-1" })
        try {
            vm.load(1); advanceUntilIdle()
            val state = awaitState(vm) { !it.loading && it.partialEstimatedCoverage == 1 }
            assertEquals(15.0, state.partialEstimatedCost!!, 0.0)
            assertEquals(12.0, state.totalCost!!, 0.0)
            assertEquals(2, state.totalCharges)
            assertNull(state.totalEnergy)
            assertNull(state.pairedUnitCost)
            assertEquals(0, state.estimatedCoverage)
            assertNull(state.estimatedCost)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun confirmedZeroEditReprojectsOpenCostPageWithoutReloginOrRemoteReload() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = fixture("cost-live-room-edit")
        var onlineCalls = 0
        val vm = viewModel(f, { "synthetic-vehicle-1" }, onOnlineIdentity = { onlineCalls++ })
        try {
            vm.load(1); advanceUntilIdle()
            awaitState(vm) { !it.loading && it.totalCost == 12.0 }
            val localId = f.localId(1)
            val row = f.db.chargeSummaryDao().get(localId, 64)!!.toAnalysisChargeData()
            val before = onlineCalls
            val store = ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings)
            org.junit.Assert.assertTrue(store.saveBound(localId, row, 0.0, "CNY", 0))
            advanceUntilIdle()
            val updated = awaitState(vm) { it.totalCost == 0.0 && it.manualCostCount == 1 }
            assertEquals(0.0, updated.totalCost!!, 0.0)
            assertEquals(before, onlineCalls)
            assertEquals(1, f.db.chargeSummaryDao().getAllForCar(localId).size)
        } finally { vm.viewModelScope.cancel(); advanceUntilIdle(); f.close(); Dispatchers.resetMain() }
    }
}

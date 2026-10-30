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
        persistDrives: suspend () -> Unit = {},
        onOnlineIdentity: () -> Unit = {}
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
            getCharges = { _, _, _, _ -> ApiResult.Success(emptyList()) },
            persistDrives = { persistDrives() },
            persistCharges = {},
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
        vm = viewModel(f, { "synthetic-vehicle-1" }, persistDrives = {
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
}

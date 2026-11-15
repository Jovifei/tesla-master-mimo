package com.matelink.ui.screens.charges

import android.app.Application
import android.content.res.Configuration
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.matelink.R
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.HistoryContextData
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.data.repository.HistoryReadDependencies
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.ui.components.historyReadErrorMessageRes
import com.matelink.ui.components.historyReadWarningMessageRes
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ChargeRefreshRoomConsumerTest {
    @get:Rule val temp = TemporaryFolder()

    private class Source {
        val calls = AtomicInteger()
        var identityAllowed = true
        var cachedIdentityAvailable = true
        var failChargeWrite = false
        var discover: suspend (Int) -> ApiResult<HistoryContextData> = { ApiResult.Error("offline", 503) }
    }

    private fun online(id: Int): ApiResult<HistoryContextData> =
        ApiResult.Success(HistoryContextData(1, id, "synthetic-vehicle-$id"))

    private fun page(f: HistoryResourceFixture, source: Source): ChargesViewModel {
        val history = UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = {
                if (!source.identityAllowed) throw HistoryIdentityUnavailableException()
                f.vehicleContexts.captureReadScope()
            },
            getCars = { ApiResult.Error("offline", 503) },
            getHistoryContext = { id -> source.calls.incrementAndGet(); source.discover(id) },
            resolveCar = { car, scope -> f.vehicleContexts.resolveVerifiedHistoryCar(car, scope) },
            cachedContext = { id, scope ->
                if (source.cachedIdentityAvailable) f.vehicleContexts.cachedContextForRemote(id, scope) else null
            },
            localDrives = { f.db.driveSummaryDao().getAllChronological(it) },
            localCharges = { f.db.chargeSummaryDao().getAllForCar(it) },
            getDrives = { _, _, _, _ -> ApiResult.Success(emptyList()) },
            getCharges = { _, _, _, _ -> ApiResult.Success(emptyList()) },
            persistDrives = { f.db.driveSummaryDao().upsertPreservingEvidence(it) },
            persistCharges = {
                if (source.failChargeWrite) error("simulated Room write failure")
                f.db.chargeSummaryDao().upsertPreservingEvidence(it)
            },
            scopeChanges = f.vehicleContexts.historyScopeChanges
        ))
        return ChargesViewModel(f.context, f.apiRepo, history, f.db.chargeSummaryDao(), f.settings,
            ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings), f.db.aggregateDao(),
            f.vehicleContexts, Clock.fixed(Instant.parse("2026-10-25T08:00:00Z"), ZoneId.of("Asia/Shanghai")),
            SavedStateHandle())
    }

    private suspend fun seed(f: HistoryResourceFixture, remoteId: Int = 1): ChargeData {
        val charge = ChargeData(50 + remoteId, startDate = "2026-10-20T10:00:00Z",
            endDate = "2026-10-20T11:00:00Z", address = "杭州测试充电站", chargeEnergyAdded = 3.0,
            source = "teslamate_archive", sourceInstanceId = "archive-A", qualityState = "observed", qualityReason = "api")
        f.db.chargeSummaryDao().upsert(requireNotNull(charge.toSyncSummary(f.localId(remoteId))))
        return charge
    }

    private fun TestScope.await(message: String, ready: () -> Boolean) {
        repeat(500) {
            runCurrent()
            if (ready()) return
            Thread.sleep(10)
        }
        fail(message)
    }

    private fun TestScope.awaitOffline(page: ChargesViewModel, source: Source, chargeId: Int) =
        await("offline Room frame was not published") {
            source.calls.get() >= 2 && page.uiState.value.historySyncWarning == "history_cached" &&
                page.uiState.value.charges.any { it.chargeId == chargeId }
        }

    @Test fun refreshSurvivesRoomSettingsPriceAndFilterCallbacksUntilRemoteSuccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = HistoryResourceFixture(File(temp.root, "refresh-callbacks.db"))
        val source = Source()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var page: ChargesViewModel? = null
        try {
            f.initialize(cloud = true)
            f.status = { 503 }
            f.settings.saveCurrency("CNY")
            f.settings.saveDefaultChargePrice(1.5)
            f.settings.saveTariffConfig(false, 1.0, 0.7, 0.3, "[[10,14],[18,20]]", "[[7,9],[15,17],[21,22]]", "[[23,23],[0,6]]")
            val charge = seed(f)
            val vm = page(f, source).also { page = it }
            vm.setCarId(1)
            awaitOffline(vm, source, charge.chargeId)
            source.discover = { id -> started.complete(Unit); gate.await(); online(id) }
            vm.refresh()
            await("refresh did not reach identity request") { started.isCompleted }
            assertTrue(vm.uiState.value.isRefreshing)
            assertEquals(listOf(charge.chargeId), vm.uiState.value.charges.map { it.chargeId })

            vm.setCostFilter(CostFilter.HAS_COST)
            vm.setChargeTypeFilter(ChargeTypeFilter.AC)
            vm.setLocationFilter("杭州测试充电站")
            vm.clearLocationFilter()
            vm.setChargeTypeFilter(ChargeTypeFilter.ALL)
            assertTrue("filter callbacks must not end refresh", vm.uiState.value.isRefreshing)
            f.settings.saveDefaultChargePrice(2.0)
            await("settings were not applied while refreshing") { vm.uiState.value.effectiveCosts[charge.chargeId]?.cost == 6.0 }
            assertTrue(vm.uiState.value.isRefreshing)
            val beforeWrite = testScheduler.currentTime
            val write = async {
                ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings)
                    .saveBound(f.localId(1), charge, 23.0, "CNY", 0L)
            }
            await("manual price write did not finish") { write.isCompleted }
            assertTrue(write.await())
            await("manual price callback was not applied") { vm.uiState.value.effectiveCosts[charge.chargeId]?.cost == 23.0 }
            assertEquals("Room write must not advance the virtual timeout", beforeWrite, testScheduler.currentTime)
            assertFalse("the provider request is still held", gate.isCompleted)
            assertTrue("manual update must not end refresh; warning=${vm.uiState.value.historySyncWarning}",
                vm.uiState.value.isRefreshing)

            gate.complete(Unit)
            await("refresh did not finish successfully") { !vm.uiState.value.isRefreshing && vm.uiState.value.historySyncWarning == null }
            assertEquals(listOf(charge.chargeId), vm.uiState.value.charges.map { it.chargeId })
            assertEquals(1, f.db.chargeSummaryDao().getAllForCar(f.localId(1)).size)
        } finally { gate.complete(Unit); page?.viewModelScope?.cancel(); runCurrent(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun timeoutUsesStableLocalizedFeedbackWithAndWithoutRoomRows() = runTest {
        for (hasCache in listOf(true, false)) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = HistoryResourceFixture(File(temp.root, "refresh-timeout-$hasCache.db"))
            val source = Source()
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            var page: ChargesViewModel? = null
            try {
                f.initialize(cloud = true); f.status = { 503 }
                if (hasCache) seed(f)
                val vm = page(f, source).also { page = it }
                vm.setCarId(1)
                if (hasCache) awaitOffline(vm, source, 51) else await("initial empty request did not fail") {
                    source.calls.get() >= 2 && !vm.uiState.value.isLoading && vm.uiState.value.error != null
                }
                source.discover = { id -> started.complete(Unit); gate.await(); online(id) }
                vm.refresh()
                await("timeout request did not start") { started.isCompleted }
                assertTrue(vm.uiState.value.isRefreshing)
                advanceTimeBy(20_000); runCurrent()
                assertFalse(vm.uiState.value.isRefreshing)
                val resource = if (hasCache) {
                    assertEquals("history_sync_timeout", vm.uiState.value.historySyncWarning)
                    assertEquals(listOf(51), vm.uiState.value.charges.map { it.chargeId })
                    historyReadWarningMessageRes(requireNotNull(vm.uiState.value.historySyncWarning))
                } else {
                    assertEquals("history_sync_timeout", vm.uiState.value.error)
                    historyReadErrorMessageRes(requireNotNull(vm.uiState.value.error))
                }
                assertEquals(if (hasCache) R.string.history_sync_timeout else R.string.settings_connection_timeout, resource)
                val config = Configuration(f.context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
                assertTrue(f.context.createConfigurationContext(config).getString(resource).contains("超时"))
            } finally { gate.complete(Unit); page?.viewModelScope?.cancel(); runCurrent(); f.close(); Dispatchers.resetMain() }
        }
    }

    @Test fun directFailureAndThrownWriteFailureKeepRoomAndFinishRefresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = HistoryResourceFixture(File(temp.root, "refresh-errors.db"))
        val source = Source()
        var page: ChargesViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }; seed(f)
            val vm = page(f, source).also { page = it }
            vm.setCarId(1); awaitOffline(vm, source, 51)
            source.discover = { online(it) }
            vm.refresh()
            await("baseline refresh did not succeed") { !vm.uiState.value.isRefreshing && vm.uiState.value.historySyncWarning == null }

            source.cachedIdentityAvailable = false
            source.discover = { ApiResult.Error("offline", 503) }
            vm.refresh()
            await("direct error did not finish") { !vm.uiState.value.isRefreshing && vm.uiState.value.historySyncWarning == "history_cached" }
            assertEquals(listOf(51), vm.uiState.value.charges.map { it.chargeId })

            source.cachedIdentityAvailable = true
            source.discover = { online(it) }
            source.failChargeWrite = true
            vm.refresh()
            await("write error did not finish") { !vm.uiState.value.isRefreshing }
            assertEquals("history_cached", vm.uiState.value.historySyncWarning)
            assertEquals(listOf(51), vm.uiState.value.charges.map { it.chargeId })
        } finally { page?.viewModelScope?.cancel(); runCurrent(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun losingIdentityEndsRefreshAndRemovesUnverifiedRoomFrame() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = HistoryResourceFixture(File(temp.root, "refresh-identity.db"))
        val source = Source()
        var page: ChargesViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }; seed(f)
            val vm = page(f, source).also { page = it }
            vm.setCarId(1); awaitOffline(vm, source, 51)
            source.identityAllowed = false
            vm.refresh()
            await("identity loss did not finish refresh") { vm.uiState.value.error == HISTORY_IDENTITY_UNAVAILABLE }
            assertFalse(vm.uiState.value.isRefreshing)
            assertTrue(vm.uiState.value.charges.isEmpty())
            assertEquals(1, f.db.chargeSummaryDao().getAllForCar(f.localId(1)).size)
        } finally { page?.viewModelScope?.cancel(); runCurrent(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun switchingCarsEndsOldRefreshAndRejectsItsLateResult() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = HistoryResourceFixture(File(temp.root, "refresh-car-switch.db"))
        val source = Source()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        var page: ChargesViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }; seed(f); seed(f, 2)
            val vm = page(f, source).also { page = it }
            vm.setCarId(1); awaitOffline(vm, source, 51)
            source.discover = { id ->
                if (id == 1) withContext(NonCancellable) { started.complete(Unit); gate.await(); oldFinished.complete(Unit) }
                ApiResult.Error("offline", 503)
            }
            vm.refresh()
            await("old vehicle refresh did not start") { started.isCompleted }
            assertTrue(vm.uiState.value.isRefreshing)
            vm.setCarId(2)
            assertFalse(vm.uiState.value.isRefreshing)
            await("new vehicle Room frame missing") {
                vm.uiState.value.historySyncWarning == "history_cached" && vm.uiState.value.charges.map { it.chargeId } == listOf(52)
            }
            gate.complete(Unit)
            await("old request did not return") { oldFinished.isCompleted }
            runCurrent()
            assertEquals(listOf(52), vm.uiState.value.charges.map { it.chargeId })
            assertFalse(vm.uiState.value.isRefreshing)
        } finally { gate.complete(Unit); page?.viewModelScope?.cancel(); runCurrent(); f.close(); Dispatchers.resetMain() }
    }
}

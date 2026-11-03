package com.matelink.ui.screens.charges

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.entity.ChargeCostOverride
import com.matelink.data.repository.HistoryDetailRepository
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.TripDetector
import com.matelink.domain.TripRepository
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ChargeDetailDeletedManualCostRoomTest {
    @get:Rule val temp = TemporaryFolder()

    private val charge = ChargeData(64, startDate = "2026-10-14T00:00:00Z",
        endDate = "2026-10-14T00:01:00Z", source = "teslamate_archive",
        sourceInstanceId = "synthetic-instance", sourceRecordId = "teslamate:charge:64",
        qualityState = "observed", chargeEnergyAdded = 2.0)

    private suspend fun fixture(name: String): HistoryResourceFixture {
        val f = HistoryResourceFixture(File(temp.root, "$name.db"))
        f.initialize()
        f.settings.saveCurrency("CNY")
        f.db.chargeSummaryDao().upsert(requireNotNull(charge.toSyncSummary(f.localId(1))))
        return f
    }

    private fun page(f: HistoryResourceFixture): ChargeDetailViewModel {
        val trips = TripRepository(f.db.driveSummaryDao(), f.db.chargeSummaryDao(),
            f.db.aggregateDao(), f.db.savedTripDao(), TripDetector())
        return ChargeDetailViewModel(f.context, f.apiRepo, f.settings,
            ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings), trips,
            UnifiedHistoryRepository(f.apiRepo, f.vehicleContexts, f.db.driveSummaryDao(), f.db.chargeSummaryDao()),
            f.db.chargeSummaryDao(), f.db, HistoryDetailRepository(f.db.historyDetailDao()), f.vehicleContexts)
    }

    private suspend fun loaded(page: ChargeDetailViewModel): ChargeDetailUiState = withTimeout(10_000) {
        page.uiState.first { !it.isLoading && !it.isRefreshing && it.chargeDetail?.chargeId == 64 }
    }

    private suspend fun seededTombstone(f: HistoryResourceFixture): ChargeCostOverrideStore {
        val store = ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings)
        val id = f.localId(1)
        assertTrue(store.saveBound(id, charge, 20.0, "CNY", 0))
        assertTrue(store.saveBound(id, charge, null, "CNY", 1))
        return store
    }

    @Test fun clearedPriceIsNotResurrectedAndTombstoneSurvivesReopen() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = fixture("detail-cleared")
        var vm: ChargeDetailViewModel? = null
        try {
            f.settings.saveDefaultChargePrice(10.0)
            f.settings.saveTariffConfig(false, 1.0, 0.7, 0.3, "[[10,14],[18,20]]", "[[7,9],[15,17],[21,22]]", "[[23,23],[0,6]]")
            f.status = { 503 }
            val page = page(f).also { vm = it }
            page.loadChargeDetail(1, 64)
            loaded(page)
            page.saveManualTotalAmount(20.0)
            withTimeout(10_000) { page.uiState.first { it.manualTotalAmount == 20.0 && it.manualVersion == 1L } }
            page.saveManualTotalAmount(null)
            val cleared = withTimeout(10_000) { page.uiState.first { it.manualTotalAmount == null && it.manualVersion == 2L } }
            assertNull("A deleted manual amount must not appear as pending", cleared.pendingBoundAmount)
            assertNull(pendingChargeAmountForDisplay(false, cleared.pendingBoundAmount, cleared.pendingLegacyAmount))
            assertEquals(ChargeDetailCostState.ESTIMATE, cleared.costPresentation.state)
            assertEquals(20.0, cleared.costPresentation.cost ?: error("default estimate missing"), 0.0)
            assertEquals("≈¥20.00", formatChargeDetailCostText(cleared.costPresentation.state,
                cleared.costPresentation.cost, "¥", "free", "unavailable"))
            val record = f.db.chargeCostOverrideDao().getRecordsForCharge(f.localId(1), 64).single()
            assertTrue(record.deleted)
            assertEquals(20.0, record.manualTotalAmount, 0.0)
            assertEquals(2L, record.version)
            vm?.viewModelScope?.cancel(); vm = null
            f.reopen()
            val reopened = f.db.chargeCostOverrideDao().getRecordsForCharge(f.localId(1), 64).single()
            assertEquals(record, reopened)
        } finally { vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun coldCacheAndSuccessfulRefreshKeepDeletedExactRecordAndOtherAmountsSeparate() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = fixture("detail-cold")
        var vm: ChargeDetailViewModel? = null
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        try {
            val store = seededTombstone(f)
            val id = f.localId(1)
            assertTrue(store.saveBound(id, charge, 31.0, "USD", 0))
            val otherSession = charge.copy(sourceInstanceId = "synthetic-other", sourceRecordId = "teslamate:charge:other")
            assertTrue(store.saveBound(id, otherSession, 44.0, "CNY", 0))
            f.db.chargeCostOverrideDao().upsert(ChargeCostOverride(id, 64, 55.0))
            f.reopen()
            f.status = { 200 }
            f.onRequest = { path -> if (path.endsWith("/cars")) { entered.complete(Unit); gate.await() } }
            f.detailResponse = { path -> if (path.endsWith("/charges/64"))
                """{"data":{"charge":{"charge_id":64,"start_date":"2026-10-14T00:00:00Z","end_date":"2026-10-14T00:01:00Z","source":"teslamate_archive","source_instance_id":"synthetic-instance","source_record_id":"teslamate:charge:64","quality_state":"observed","charge_energy_added":2.0}}}""" else null }
            val page = page(f).also { vm = it }
            page.loadChargeDetail(1, 64)
            withTimeout(10_000) { entered.await() }
            val cold = page.uiState.value
            assertEquals(64, cold.chargeDetail?.chargeId)
            assertNull("Neither the tombstone nor a conflicting session is a pending price", cold.pendingBoundAmount)
            assertNull("A deleted exact price must not fall back to a legacy same-ID amount", cold.pendingLegacyAmount)
            assertNull(pendingChargeAmountForDisplay(false, cold.pendingBoundAmount, cold.pendingLegacyAmount))
            assertEquals("USD", cold.otherCurrencyAmount?.first)
            assertEquals(31.0, cold.otherCurrencyAmount?.second ?: error("missing foreign-currency record"), 0.0)
            gate.complete(Unit)
            val refreshed = loaded(page)
            assertNull(refreshed.pendingBoundAmount)
            assertNull(refreshed.pendingLegacyAmount)
            assertEquals(cold.otherCurrencyAmount, refreshed.otherCurrencyAmount)
            val records = f.db.chargeCostOverrideDao().getRecordsForCharge(id, 64)
            assertEquals(4, records.size)
            assertTrue(records.any { it.sessionKey == "legacy" && !it.deleted && it.manualTotalAmount == 55.0 })
            assertTrue(records.any { it.currencyCode == "CNY" && it.deleted && it.manualTotalAmount == 20.0 && it.version == 2L })
            assertTrue(records.any { it.currencyCode == "CNY" && !it.deleted && it.manualTotalAmount == 44.0 })
            assertTrue(records.any { it.currencyCode == "USD" && !it.deleted && it.manualTotalAmount == 31.0 })
            page.viewModelScope.cancel(); vm = null
            f.settings.saveCurrency("USD")
            val usdPage = page(f).also { vm = it }
            usdPage.loadChargeDetail(1, 64)
            val usd = loaded(usdPage)
            assertEquals("USD", usd.currencyCode)
            assertEquals(31.0, usd.manualTotalAmount ?: error("foreign-currency manual amount missing"), 0.0)
            assertEquals(ChargeDetailCostState.MANUAL, usd.costPresentation.state)
            assertNull(pendingChargeAmountForDisplay(true, usd.pendingBoundAmount, usd.pendingLegacyAmount))
        } finally { gate.complete(Unit); vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun staleEditCannotOverwriteTombstoneAndNewEditUsesItsVersion() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = fixture("detail-cas")
        var vm: ChargeDetailViewModel? = null
        try {
            val store = seededTombstone(f)
            val id = f.localId(1)
            f.status = { 503 }
            val page = page(f).also { vm = it }
            page.loadChargeDetail(1, 64)
            val state = loaded(page)
            assertEquals(2L, state.manualVersion)
            assertNull(state.pendingBoundAmount)
            assertFalse(store.saveBound(id, charge, 77.0, "CNY", 1))
            val unchanged = f.db.chargeCostOverrideDao().getRecordsForCharge(id, 64).single()
            assertTrue(unchanged.deleted)
            assertEquals(20.0, unchanged.manualTotalAmount, 0.0)
            assertEquals(2L, unchanged.version)
            page.saveManualTotalAmount(7.0)
            val saved = withTimeout(10_000) { page.uiState.first { it.manualTotalAmount == 7.0 && it.manualVersion == 3L } }
            assertNull(saved.pendingBoundAmount)
            val updated = f.db.chargeCostOverrideDao().getRecordsForCharge(id, 64).single()
            assertFalse(updated.deleted)
            assertEquals(7.0, updated.manualTotalAmount, 0.0)
            assertEquals(3L, updated.version)
        } finally { vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }
}

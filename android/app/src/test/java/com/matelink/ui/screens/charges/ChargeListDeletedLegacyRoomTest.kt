package com.matelink.ui.screens.charges

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.entity.ChargeCostOverride
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.HistoryReadDependencies
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.ChargeCostSource
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
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
class ChargeListDeletedLegacyRoomTest {
    @get:Rule val temp = TemporaryFolder()
    private val charge = ChargeData(64, startDate = "2026-10-20T10:00:00Z",
        endDate = "2026-10-20T11:00:00Z", source = "teslamate_archive",
        sourceInstanceId = "synthetic-instance", qualityState = "observed", chargeEnergyAdded = 2.0)

    private suspend fun fixture(name: String): HistoryResourceFixture {
        val f = HistoryResourceFixture(File(temp.root, "$name.db"))
        f.initialize(cloud = true)
        f.settings.saveCurrency("CNY")
        f.db.chargeSummaryDao().upsert(requireNotNull(charge.toSyncSummary(f.localId(1))))
        return f
    }

    private fun page(f: HistoryResourceFixture): ChargesViewModel {
        val history = UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { f.vehicleContexts.captureReadScope() },
            getCars = { ApiResult.Error("synthetic-offline", 503) },
            getHistoryContext = { ApiResult.Error("synthetic-offline", 503) },
            resolveCar = { car, scope -> f.vehicleContexts.resolveVerifiedHistoryCar(car, scope) },
            cachedContext = { id, scope -> f.vehicleContexts.cachedContextForRemote(id, scope) },
            localDrives = { f.db.driveSummaryDao().getAllChronological(it) },
            localCharges = { f.db.chargeSummaryDao().getAllForCar(it) },
            getDrives = { _, _, _, _ -> error("must stay offline") },
            getCharges = { _, _, _, _ -> error("must stay offline") },
            persistDrives = {}, persistCharges = {}, scopeChanges = f.vehicleContexts.historyScopeChanges
        ))
        return ChargesViewModel(f.context, f.apiRepo, history, f.db.chargeSummaryDao(),
            f.settings, ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings),
            f.db.aggregateDao(), f.vehicleContexts,
            Clock.fixed(Instant.parse("2026-10-25T08:00:00Z"), ZoneId.of("Asia/Shanghai")), SavedStateHandle())
    }

    @Test fun deletedExactPriceMustNotFallBackToLegacySameNumericId() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = fixture("list-deleted-legacy")
        var vm: ChargesViewModel? = null
        try {
            val local = f.localId(1)
            val store = ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings)
            assertTrue(store.saveBound(local, charge, 20.0, "CNY", 0))
            assertTrue(store.saveBound(local, charge, null, "CNY", 1))
            assertTrue(store.saveBound(local, charge, 31.0, "USD", 0))
            f.db.chargeCostOverrideDao().upsert(ChargeCostOverride(local, 64, 55.0))
            f.reopen()
            val page = page(f).also { vm = it }
            page.setCarId(1)
            val state = withTimeout(10_000) { page.uiState.first {
                it.charges.any { row -> row.chargeId == 64 } && it.effectiveCosts.containsKey(64) &&
                    it.otherCurrencyAmounts[64] == ("USD" to 31.0)
            } }
            assertNull("A tombstone must not display another same-ID legacy amount", state.pendingLegacyAmounts[64])
            assertNull(state.pendingBoundAmounts[64])
            f.settings.saveCurrency("USD")
            val usd = withTimeout(10_000) { page.uiState.first {
                it.currencyCode == "USD" && it.effectiveCosts[64]?.source == ChargeCostSource.MANUAL
            } }
            assertEquals(31.0, usd.effectiveCosts[64]?.cost ?: error("USD amount missing"), 0.0)
            assertNull(pendingChargeAmountForDisplay(true, usd.pendingBoundAmounts[64], usd.pendingLegacyAmounts[64]))
            val rows = f.db.chargeCostOverrideDao().getRecordsForCharge(local, 64)
            assertTrue(rows.any { it.deleted && it.manualTotalAmount == 20.0 && it.version == 2L })
            assertTrue(rows.any { it.sessionKey == "legacy" && it.manualTotalAmount == 55.0 && !it.deleted })
            assertTrue(rows.any { it.currencyCode == "USD" && it.manualTotalAmount == 31.0 && !it.deleted })
        } finally { vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun otherSessionTombstoneDoesNotHideCurrentSessionsLegacyAmount() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = fixture("list-other-session")
        var vm: ChargesViewModel? = null
        try {
            val local = f.localId(1)
            val store = ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings)
            val other = charge.copy(sourceInstanceId = "synthetic-other")
            assertTrue(store.saveBound(local, other, 20.0, "CNY", 0))
            assertTrue(store.saveBound(local, other, null, "CNY", 1))
            assertTrue(store.saveBound(local, charge, 31.0, "USD", 0))
            f.db.chargeCostOverrideDao().upsert(ChargeCostOverride(local, 64, 55.0))
            val page = page(f).also { vm = it }
            page.setCarId(1)
            val state = withTimeout(10_000) { page.uiState.first {
                it.charges.any { row -> row.chargeId == 64 } && it.otherCurrencyAmounts[64] == ("USD" to 31.0)
            } }
            assertEquals(55.0, state.pendingLegacyAmounts[64] ?: error("legacy amount hidden by other session"), 0.0)
            val rows = f.db.chargeCostOverrideDao().getRecordsForCharge(local, 64)
            assertTrue(rows.any { it.deleted && it.sourceInstanceId == "synthetic-other" })
        } finally { vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun tombstoneInOtherCurrencyDoesNotSuppressCurrentCurrency() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = fixture("list-other-currency")
        var vm: ChargesViewModel? = null
        try {
            val local = f.localId(1)
            val store = ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings)
            assertTrue(store.saveBound(local, charge, 20.0, "USD", 0))
            assertTrue(store.saveBound(local, charge, null, "USD", 1))
            assertTrue(store.saveBound(local, charge, 31.0, "GBP", 0))
            f.db.chargeCostOverrideDao().upsert(ChargeCostOverride(local, 64, 55.0))
            val page = page(f).also { vm = it }
            page.setCarId(1)
            val cny = withTimeout(10_000) { page.uiState.first {
                it.currencyCode == "CNY" && it.otherCurrencyAmounts[64] == ("GBP" to 31.0)
            } }
            assertEquals(55.0, cny.pendingLegacyAmounts[64] ?: error("legacy amount hidden by other currency"), 0.0)
            f.settings.saveCurrency("USD")
            val usd = withTimeout(10_000) { page.uiState.first {
                it.currencyCode == "USD" && it.effectiveCosts[64]?.currencyCode == "USD" &&
                    it.otherCurrencyAmounts[64] == ("GBP" to 31.0)
            } }
            assertNull("USD tombstone applies only in its own currency", usd.pendingLegacyAmounts[64])
            val rows = f.db.chargeCostOverrideDao().getRecordsForCharge(local, 64)
            assertTrue(rows.any { it.currencyCode == "USD" && it.deleted && it.version == 2L })
            assertTrue(rows.any { it.currencyCode == "GBP" && !it.deleted && it.manualTotalAmount == 31.0 })
        } finally { vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun referenceEstimateEqualToClearedAmountDoesNotRestoreManualRecord() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = fixture("list-reference-equals-deleted")
        var vm: ChargesViewModel? = null
        try {
            f.settings.saveDefaultChargePrice(2.0)
            f.settings.saveTariffConfig(false, 1.0, 0.7, 0.3, "[[10,14],[18,20]]", "[[7,9],[15,17],[21,22]]", "[[23,23],[0,6]]")
            val local = f.localId(1)
            val imported = charge.copy(chargeId = 65, source = "local_import", sourceInstanceId = null,
                qualityState = "incomplete", qualityReason = "local_import_summary_only", chargeEnergyAdded = 10.0)
            f.db.chargeSummaryDao().upsert(requireNotNull(imported.toSyncSummary(local)))
            val store = ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings)
            assertTrue(store.saveBound(local, imported, 20.0, "CNY", 0))
            assertTrue(store.saveBound(local, imported, null, "CNY", 1))
            f.db.chargeCostOverrideDao().upsert(ChargeCostOverride(local, 65, 55.0))
            f.reopen()
            val page = page(f).also { vm = it }
            page.setCarId(1)
            val state = withTimeout(10_000) { page.uiState.first {
                it.referenceEnergyKwh[65] == 10.0 && it.referenceEstimates[65] == 20.0
            } }
            assertEquals(20.0, state.referenceEstimates[65] ?: error("reference estimate missing"), 0.0)
            assertNull(state.pendingLegacyAmounts[65])
            assertNull(state.pendingBoundAmounts[65])
            assertFalse(state.priceOverrides.containsKey(65))
            assertEquals(ChargeCostSource.UNAVAILABLE, state.effectiveCosts[65]?.source)
            assertEquals("Unverified imported energy and reference estimate do not enter totals", 1, state.summary.totalCharges)
            assertEquals(4.0, state.summary.totalCost ?: error("qualified estimate missing"), 0.0)
            val records = f.db.chargeCostOverrideDao().getRecordsForCharge(local, 65)
            assertTrue(records.any { it.deleted && it.version == 2L && it.manualTotalAmount == 20.0 })
            assertTrue(records.any { it.sessionKey == "legacy" && !it.deleted && it.manualTotalAmount == 55.0 })
        } finally { vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }
}

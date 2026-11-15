package com.matelink.ui.screens.charges

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.repository.HistoryDetailRepository
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.TripDetector
import com.matelink.domain.TripRepository
import com.matelink.domain.analytics.chargeCostSessionKey
import com.matelink.domain.analytics.toRawAnalysisChargeData
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
class ChargeAddressLookupConsumerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun charge(id: Int, address: String?, latitude: Double?, longitude: Double?) = ChargeData(
        id, startDate = "2026-10-20T10:00:00Z", endDate = "2026-10-20T11:00:00Z",
        source = "teslamate_archive", sourceInstanceId = "archive-A", sourceRecordId = "record-$id",
        qualityState = "observed", qualityReason = "api", address = address,
        latitude = latitude, longitude = longitude, chargeEnergyAdded = 5.0
    )

    private fun history(f: HistoryResourceFixture) = UnifiedHistoryRepository(
        f.apiRepo, f.vehicleContexts, f.db.driveSummaryDao(), f.db.chargeSummaryDao()
    )

    private fun list(f: HistoryResourceFixture, savedState: SavedStateHandle = SavedStateHandle()) = ChargesViewModel(
        f.context, f.apiRepo, history(f), f.db.chargeSummaryDao(), f.settings,
        ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings), f.db.aggregateDao(),
        f.vehicleContexts, Clock.fixed(Instant.parse("2026-10-25T08:00:00Z"), ZoneId.of("Asia/Shanghai")),
        savedState, f.geocodingRepository
    )

    @Test fun listUsesChineseLookupOnlyInPresentationAndKeepsCostIdentity() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "address-list.db"))
        val savedState = SavedStateHandle()
        var vm: ChargesViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }
            val local = f.localId(1)
            val raw = charge(61, "English charging station", 30.1, 120.1)
            f.db.chargeSummaryDao().upsert(raw.toSyncSummary(local)!!)
            val page = list(f, savedState).also { vm = it }
            page.addressLookup = { _, _ -> "杭州市测试充电站" }
            page.setCarId(1)
            val state = withTimeout(10_000) { page.uiState.first { it.charges.any { row -> row.address == "杭州市测试充电站" } } }
            assertEquals("English charging station", state.availableLocations.single())
            assertEquals("杭州市测试充电站", state.locationLabels["English charging station"])
            assertEquals(chargeCostSessionKey(raw), chargeCostSessionKey(state.charges.single()))
            assertEquals(raw.address, f.db.chargeSummaryDao().get(local, 61)!!.toRawAnalysisChargeData().address)
            page.setLocationFilter("English charging station")
            page.viewModelScope.cancel()
            val restored = list(f, savedState).also { vm = it }
            restored.addressLookup = { _, _ -> "杭州市测试充电站" }
            restored.setCarId(1)
            val reopened = withTimeout(10_000) { restored.uiState.first {
                it.selectedLocations == setOf("English charging station") &&
                    it.charges.any { row -> row.address == "杭州市测试充电站" }
            } }
            assertEquals(listOf(61), reopened.charges.map { it.chargeId })
        } finally { vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun delayedLookupCannotChangeAnActiveLocationFilter() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "address-selection.db"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var vm: ChargesViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }
            val local = f.localId(1)
            val raw = charge(62, "English charging station", 30.2, 120.2)
            f.db.chargeSummaryDao().upsert(raw.toSyncSummary(local)!!)
            val page = list(f).also { vm = it }
            page.addressLookup = { _, _ ->
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                "杭州市延迟充电站"
            }
            page.setCarId(1)
            withTimeout(10_000) { entered.await() }
            page.setLocationFilter("English charging station")
            release.complete(Unit)
            val selected = withTimeout(10_000) { page.uiState.first {
                it.selectedLocations.isNotEmpty() && it.charges.any { row -> row.address == "杭州市延迟充电站" }
            } }
            assertEquals(listOf(62), selected.charges.map { it.chargeId })
            assertEquals(setOf("English charging station"), selected.selectedLocations)
            assertEquals("杭州市延迟充电站", selected.locationLabels["English charging station"])
            page.clearLocationFilter()
            withTimeout(10_000) { page.uiState.first { it.charges.any { row -> row.address == "杭州市延迟充电站" } } }
            Unit
        } finally { release.complete(Unit); vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun coordinateOnlySelectionSurvivesSavedStateRecreationAndLateLabel() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "address-state-recreation.db"))
        val savedState = SavedStateHandle()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var first: ChargesViewModel? = null
        var second: ChargesViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }
            val local = f.localId(1)
            f.db.chargeSummaryDao().upsert(charge(65, null, 30.5, 120.5).toSyncSummary(local)!!)
            val initial = list(f, savedState).also { first = it }
            initial.addressLookup = { _, _ -> "杭州市原地点" }
            initial.setCarId(1)
            val loaded = withTimeout(10_000) { initial.uiState.first {
                it.charges.any { row -> row.address == "杭州市原地点" } && it.availableLocations.size == 1
            } }
            val key = loaded.availableLocations.single()
            assertNotEquals("杭州市原地点", key)
            initial.setLocationFilter(key)
            assertEquals(listOf(65), initial.uiState.value.charges.map { it.chargeId })
            initial.viewModelScope.cancel()

            val restored = list(f, savedState).also { second = it }
            restored.addressLookup = { _, _ ->
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                "杭州市更新地点"
            }
            restored.setCarId(1)
            withTimeout(10_000) { entered.await() }
            val pending = withTimeout(10_000) { restored.uiState.first { it.charges.any { row -> row.chargeId == 65 } } }
            assertEquals(setOf(key), pending.selectedLocations)
            release.complete(Unit)
            val updated = withTimeout(10_000) { restored.uiState.first { it.locationLabels[key] == "杭州市更新地点" } }
            assertEquals(setOf(key), updated.selectedLocations)
            assertEquals(listOf(65), updated.charges.map { it.chargeId })
        } finally {
            release.complete(Unit); first?.viewModelScope?.cancel(); second?.viewModelScope?.cancel()
            f.close(); Dispatchers.resetMain()
        }
    }

    @Test fun sameChineseLabelDoesNotMergeDifferentOriginalLocationKeys() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "address-duplicate-label.db"))
        var vm: ChargesViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }
            val local = f.localId(1)
            val a = charge(66, "English place A", 30.6, 120.6)
            val b = charge(67, "English place B", 30.7, 120.7).copy(
                startDate = "2026-10-21T10:00:00Z", endDate = "2026-10-21T11:00:00Z")
            f.db.chargeSummaryDao().upsert(a.toSyncSummary(local)!!)
            f.db.chargeSummaryDao().upsert(b.toSyncSummary(local)!!)
            val page = list(f).also { vm = it }
            page.addressLookup = { _, _ -> "杭州市同名充电站" }
            page.setCarId(1)
            val loaded = withTimeout(10_000) { page.uiState.first {
                it.locationLabels["English place A"] == "杭州市同名充电站" &&
                    it.locationLabels["English place B"] == "杭州市同名充电站"
            } }
            assertEquals(setOf("English place A", "English place B"), loaded.availableLocations.toSet())
            page.setLocationFilter("English place A")
            assertEquals(listOf(66), page.uiState.value.charges.map { it.chargeId })
            assertEquals(1, page.uiState.value.summary.totalCharges)
        } finally { vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun legacyChineseSavedSelectionMigratesOnlyWhenItMapsToOneStableKey() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "address-legacy-selection.db"))
        val savedState = SavedStateHandle(mapOf("filter_locations" to arrayListOf("杭州市原地点")))
        var vm: ChargesViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }
            val local = f.localId(1)
            f.db.chargeSummaryDao().upsert(charge(68, "English original", 30.8, 120.8).toSyncSummary(local)!!)
            val page = list(f, savedState).also { vm = it }
            page.addressLookup = { _, _ -> "杭州市原地点" }
            page.setCarId(1)
            val restored = withTimeout(10_000) { page.uiState.first {
                it.selectedLocations == setOf("English original") && it.charges.any { row -> row.chargeId == 68 }
            } }
            assertEquals("杭州市原地点", restored.locationLabels["English original"])
        } finally { vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun ambiguousLegacyChineseSelectionDoesNotGuessAnOriginalKey() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "address-ambiguous-selection.db"))
        val savedState = SavedStateHandle(mapOf("filter_locations" to arrayListOf("杭州市同名地点")))
        var vm: ChargesViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }
            val local = f.localId(1)
            val first = charge(69, "Place A", 30.9, 120.9)
            val second = charge(70, "Place B", 31.0, 121.0).copy(
                startDate = "2026-10-21T10:00:00Z", endDate = "2026-10-21T11:00:00Z")
            f.db.chargeSummaryDao().upsert(first.toSyncSummary(local)!!)
            f.db.chargeSummaryDao().upsert(second.toSyncSummary(local)!!)
            val page = list(f, savedState).also { vm = it }
            page.addressLookup = { _, _ -> "杭州市同名地点" }
            page.setCarId(1)
            val state = withTimeout(10_000) { page.uiState.first {
                it.locationLabels["Place A"] == "杭州市同名地点" && it.locationLabels["Place B"] == "杭州市同名地点"
            } }
            assertEquals(setOf("杭州市同名地点"), state.selectedLocations)
            assertTrue(state.charges.isEmpty())
        } finally { vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun delayedLookupCannotPublishAfterAccountChanges() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "address-account-change.db"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var vm: ChargesViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }
            val local = f.localId(1)
            f.db.chargeSummaryDao().upsert(charge(64, "Old address", 30.4, 120.4).toSyncSummary(local)!!)
            val page = list(f).also { vm = it }
            page.addressLookup = { _, _ ->
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                "旧账号地点"
            }
            page.setCarId(1)
            withTimeout(10_000) { entered.await() }
            f.sessions.save("other-access", "other-refresh", 3600, "other-account")
            release.complete(Unit)
            withTimeout(10_000) { page.uiState.first { state -> state.charges.none { it.chargeId == 64 } } }
            assertTrue(page.uiState.value.charges.none { it.address == "旧账号地点" })
        } finally { release.complete(Unit); vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun cachedAddressLookupBatchIsBounded() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "address-limit.db"))
        val releaseHistory = CompletableDeferred<Unit>()
        var vm: ChargesViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }
            val local = f.localId(1)
            for (id in 1..12) {
                val start = Instant.parse("2026-10-20T00:00:00Z").plusSeconds(id * 3600L)
                val row = charge(id, null, 30.0 + id / 100.0, 120.0).copy(
                    startDate = start.toString(), endDate = start.plusSeconds(1800).toString())
                f.db.chargeSummaryDao().upsert(row.toSyncSummary(local)!!)
            }
            f.onRequest = { path -> if (path.endsWith("/history-context")) releaseHistory.await() }
            val lookups = AtomicInteger()
            val page = list(f).also { vm = it }
            page.addressLookup = { _, _ -> "测试地点${lookups.incrementAndGet()}" }
            page.setCarId(1)
            val state = withTimeout(10_000) { page.uiState.first { state ->
                state.charges.size == 12 && state.charges.count { it.address?.contains("测试地点") == true } == 10
            } }
            assertEquals(10, lookups.get())
            assertEquals(2, state.charges.count { it.address == null })
        } finally { releaseHistory.complete(Unit); vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun detailUsesSameRecordCoordinatesWithoutChangingRoom() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "address-detail.db"))
        var vm: ChargeDetailViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }
            val local = f.localId(1)
            val raw = charge(63, "Old English address", 30.3, 120.3)
            f.db.chargeSummaryDao().upsert(raw.toSyncSummary(local)!!)
            val trips = TripRepository(f.db.driveSummaryDao(), f.db.chargeSummaryDao(),
                f.db.aggregateDao(), f.db.savedTripDao(), TripDetector())
            val page = ChargeDetailViewModel(f.context, f.apiRepo, f.settings,
                ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings), trips, history(f),
                f.db.chargeSummaryDao(), f.db, HistoryDetailRepository(f.db.historyDetailDao()),
                f.vehicleContexts, f.geocodingRepository).also { vm = it }
            page.addressLookup = { _, _ -> "杭州市详情充电站" }
            page.loadChargeDetail(1, 63)
            val state = withTimeout(10_000) { page.uiState.first { it.chargeDetail?.address == "杭州市详情充电站" } }
            assertEquals(raw.chargeId, state.chargeDetail?.chargeId)
            assertEquals(raw.address, f.db.chargeSummaryDao().get(local, 63)!!.toRawAnalysisChargeData().address)
        } finally { vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun invalidCoordinatesAndNonChineseResultsNeverFabricateAPlace() {
        assertNull(chargeAddressKey(charge(1, null, 30.0, null)))
        assertNull(chargeAddressKey(charge(1, null, 91.0, 120.0)))
        assertNull(chargeAddressKey(charge(1, null, 30.0, 120.0).copy(source = "local_import")))
        assertNull(chargeAddressKey(charge(1, null, 30.0, 120.0).copy(source = "unknown_source")))
        assertNull("English address".usableChineseChargeAddress())
        assertNull(" ".usableChineseChargeAddress())
        assertEquals("杭州市充电站", " 杭州市充电站 ".usableChineseChargeAddress())
    }
}

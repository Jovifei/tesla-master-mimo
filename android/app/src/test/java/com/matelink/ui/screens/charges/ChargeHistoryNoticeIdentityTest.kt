package com.matelink.ui.screens.charges

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.data.repository.HistoryReadDependencies
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
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
class ChargeHistoryNoticeIdentityTest {
    @get:Rule val temp = TemporaryFolder()

    private fun page(
        f: HistoryResourceFixture,
        captureAllowed: AtomicBoolean = AtomicBoolean(true),
        observeScope: Boolean = true,
        beforeDiscovery: suspend (Int) -> Unit = {}
    ): ChargesViewModel {
        val history = UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = {
                if (!captureAllowed.get()) throw HistoryIdentityUnavailableException()
                f.vehicleContexts.captureReadScope()
            },
            getCars = { ApiResult.Error("offline", 503) },
            getHistoryContext = { id -> beforeDiscovery(id); ApiResult.Error("offline", 503) },
            resolveCar = { car, scope -> f.vehicleContexts.resolveVerifiedHistoryCar(car, scope) },
            cachedContext = { id, scope -> f.vehicleContexts.cachedContextForRemote(id, scope) },
            localDrives = { f.db.driveSummaryDao().getAllChronological(it) },
            localCharges = { f.db.chargeSummaryDao().getAllForCar(it) },
            getDrives = { _, _, _, _ -> error("offline") },
            getCharges = { _, _, _, _ -> error("offline") },
            persistDrives = {}, persistCharges = {},
            legacyLinkPending = { _, _, _ -> true },
            scopeChanges = if (observeScope) f.vehicleContexts.historyScopeChanges else null
        ))
        return ChargesViewModel(f.context, f.apiRepo, history, f.db.chargeSummaryDao(), f.settings,
            ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings), f.db.aggregateDao(),
            f.vehicleContexts, Clock.fixed(Instant.parse("2026-10-25T08:00:00Z"), ZoneId.of("Asia/Shanghai")),
            SavedStateHandle(), f.geocodingRepository)
    }

    private suspend fun seed(f: HistoryResourceFixture, remoteId: Int = 1) {
        val local = f.localId(remoteId)
        f.db.chargeSummaryDao().upsert(ChargeData(50 + remoteId, startDate = "2026-10-20T10:00:00Z",
            endDate = "2026-10-20T11:00:00Z", source = "teslamate_archive", qualityState = "observed",
            chargeEnergyAdded = 3.0, address = "测试地点").toSyncSummary(local)!!)
    }

    @Test fun changingVehicleClearsBothNoticeFieldsBeforeNewIdentityFinishes() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "notice-car.db"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var vm: ChargesViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }; seed(f); seed(f, 2)
            val firstId = f.localId(1); val secondId = f.localId(2)
            val page = page(f, beforeDiscovery = { id -> if (id == 2) { entered.complete(Unit); release.await() } }).also { vm = it }
            page.setCarId(1)
            withTimeout(10_000) { page.uiState.first { it.localArchiveLinkPending && it.historyNoticeLocalCarId == firstId } }
            page.setCarId(2)
            withTimeout(10_000) { entered.await() }
            assertFalse(page.uiState.value.localArchiveLinkPending)
            assertNull(page.uiState.value.historyNoticeLocalCarId)
            release.complete(Unit)
            val updated = withTimeout(10_000) { page.uiState.first { it.localArchiveLinkPending && it.historyNoticeLocalCarId == secondId } }
            assertNotEquals(firstId, updated.historyNoticeLocalCarId)
        } finally { release.complete(Unit); vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun changingAccountClearsBothNoticeFieldsWhileDiscoveryIsPending() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "notice-account.db"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var vm: ChargesViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }; seed(f)
            val oldId = f.localId(1)
            val page = page(f, beforeDiscovery = {
                if (f.sessions.current()?.userId == "other-account") { entered.complete(Unit); release.await() }
            }).also { vm = it }
            page.setCarId(1)
            withTimeout(10_000) { page.uiState.first { it.localArchiveLinkPending && it.historyNoticeLocalCarId == oldId } }
            f.sessions.save("other-access", "other-refresh", 3600, "other-account")
            withTimeout(10_000) { entered.await() }
            assertFalse(page.uiState.value.localArchiveLinkPending)
            assertNull(page.uiState.value.historyNoticeLocalCarId)
        } finally { release.complete(Unit); vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun identityUnavailableClearsBothNoticeFields() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "notice-unavailable.db"))
        val allowed = AtomicBoolean(true)
        var vm: ChargesViewModel? = null
        try {
            f.initialize(cloud = true); f.status = { 503 }; seed(f)
            val oldId = f.localId(1)
            val page = page(f, captureAllowed = allowed, observeScope = false).also { vm = it }
            page.setCarId(1)
            withTimeout(10_000) { page.uiState.first { it.localArchiveLinkPending && it.historyNoticeLocalCarId == oldId } }
            allowed.set(false)
            page.refresh()
            val state = withTimeout(10_000) { page.uiState.first { it.error == HISTORY_IDENTITY_UNAVAILABLE } }
            assertFalse(state.localArchiveLinkPending)
            assertNull(state.historyNoticeLocalCarId)
        } finally { vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }
}

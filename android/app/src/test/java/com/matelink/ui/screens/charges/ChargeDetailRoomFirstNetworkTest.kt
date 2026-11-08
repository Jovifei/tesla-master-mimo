package com.matelink.ui.screens.charges

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.repository.HistoryDetailRepository
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.TripDetector
import com.matelink.domain.TripRepository
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
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
class ChargeDetailRoomFirstNetworkTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun scopedColdChargeReceiptPrecedesHeldProviderAndSurvivesFailedRefresh() = runBlocking<Unit> {
        val f = HistoryResourceFixture(File(temp.root, "charge-held-network.db"))
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var vm: ChargeDetailViewModel? = null
        try {
            f.initialize()
            val context = f.vehicleContexts.cachedContextForRemote(1)!!
            f.db.chargeSummaryDao().upsert(ChargeData(64, startDate = "2026-10-14T00:00:00Z",
                endDate = "2026-10-14T00:01:00Z", source = "teslamate_archive", qualityState = "observed",
                address = "cached charger", chargeEnergyAdded = 2.0).toSyncSummary(context.localHistoryCarId)!!)
            f.reopen(); f.status = { 503 }
            f.onRequest = { path -> if (path.endsWith("/cars")) { entered.complete(Unit); gate.await() } }
            val trips = TripRepository(f.db.driveSummaryDao(), f.db.chargeSummaryDao(), f.db.aggregateDao(), f.db.savedTripDao(), TripDetector())
            val page = ChargeDetailViewModel(f.context, f.apiRepo, f.settings,
                ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings), trips,
                UnifiedHistoryRepository(f.apiRepo, f.vehicleContexts, f.db.driveSummaryDao(), f.db.chargeSummaryDao()),
                f.db.chargeSummaryDao(), f.db, HistoryDetailRepository(f.db.historyDetailDao()), f.vehicleContexts)
            vm = page
            page.loadChargeDetail(1, 64)
            withTimeout(10_000) { entered.await() }
            assertEquals("Verified Room charge must be visible before provider discovery completes", 64, page.uiState.value.chargeDetail?.chargeId)
            assertEquals("cached charger", page.uiState.value.chargeDetail?.address)
            assertFalse(page.uiState.value.isLoading)
            gate.complete(Unit)
            val completed = withTimeout(10_000) { page.uiState.first { !it.isLoading && !it.isRefreshing } }
            assertEquals(64, completed.chargeDetail?.chargeId)
        } finally { gate.complete(Unit); vm?.viewModelScope?.cancel(); f.close(); Dispatchers.resetMain() }
    }
}

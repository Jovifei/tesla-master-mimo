package com.matelink.ui.screens.charges

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.ChargerDetails
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.repository.HistoryDetailRepository
import com.matelink.data.repository.HistoryDetailIdentity
import com.matelink.data.repository.HistoryDetailWriteResult
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.TripDetector
import com.matelink.domain.TripRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real ViewModel consumes only scoped points read from Room after the database is reopened. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ChargeDetailOfflinePointConsumerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun offlineDetailViewModelReplaysSavedPointsWithIncompleteCoverageNotice() = runBlocking {
        val fixture = HistoryResourceFixture(File(temp.root, "charge-vm-points.db"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var vm: ChargeDetailViewModel? = null
        try {
            fixture.initialize()
            val context = fixture.vehicleContexts.cachedContextForRemote(1)!!
            val start = "2026-10-14T00:00:00Z"
            val end = "2026-10-14T00:01:00Z"
            val list = ChargeData(64, startDate = start, endDate = end,
                source = "teslamate_archive", sourceInstanceId = "archive-A",
                sourceId = "record-64", chargeEnergyAdded = 24.0, qualityState = "observed")
            fixture.db.chargeSummaryDao().upsert(list.toSyncSummary(context.localHistoryCarId)!!)
            val identity = HistoryDetailIdentity.create(context, HistoryDetailIdentity.CHARGE,
                64, list.source, list.sourceInstanceId, start, end, list.sourceId)
            val detail = ChargeDetail(64, startDate = start, endDate = end,
                source = list.source, sourceInstanceId = list.sourceInstanceId,
                sourceId = list.sourceId, chargeEnergyAdded = 24.02,
                chargePoints = listOf(
                    ChargePoint(date = start, batteryLevel = 0, chargeEnergyAdded = 0.0),
                    ChargePoint(date = start, chargerDetails = ChargerDetails(chargerPower = -3.5)),
                    ChargePoint(date = end, batteryLevel = 88, chargeEnergyAdded = 24.02)))
            assertTrue(HistoryDetailRepository(fixture.db.historyDetailDao())
                .saveCharge(identity, detail) is HistoryDetailWriteResult.Stored)
            fixture.reopen()
            fixture.status = { 503 }

            val tripRepo = TripRepository(fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao(),
                fixture.db.aggregateDao(), fixture.db.savedTripDao(), TripDetector())
            val viewModel = ChargeDetailViewModel(fixture.context, fixture.apiRepo, fixture.settings,
                ChargeCostOverrideStore(fixture.db.chargeCostOverrideDao(), fixture.settings),
                tripRepo, UnifiedHistoryRepository(fixture.apiRepo, fixture.vehicleContexts,
                    fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao()),
                fixture.db.chargeSummaryDao(), fixture.db,
                HistoryDetailRepository(fixture.db.historyDetailDao()))
            vm = viewModel
            viewModel.loadChargeDetail(1, 64)
            val state = withTimeout(10_000) { viewModel.uiState.first { !it.isLoading } }
            assertNotNull(state.chargeDetail)
            assertEquals(3, state.chargeDetail!!.chargePoints!!.size)
            assertEquals(0.0, state.chargeDetail!!.chargePoints!![0].chargeEnergyAdded!!, 0.0)
            assertEquals(-3.5, state.chargeDetail!!.chargePoints!![1].chargerPowerValue!!, 0.0)
            assertEquals("history_detail_cached_total_unverified", state.historySyncWarning)
            assertFalse(state.chargeDetail!!.chargePoints!!.isEmpty())
        } finally {
            vm?.viewModelScope?.cancel()
            fixture.close()
            Dispatchers.resetMain()
        }
    }
}

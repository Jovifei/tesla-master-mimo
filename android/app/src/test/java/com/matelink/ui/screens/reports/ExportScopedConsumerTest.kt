package com.matelink.ui.screens.reports

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.repository.StatsRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class ExportScopedConsumerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun previewCountsAndYearsBelongToTheSameVerifiedLocalVehicle() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = HistoryResourceFixture(File(temporary.root, "scoped-export.db"))
        f.initialize(cloud = true)
        fun row(id: Int) = DriveData(id, startDate = "2026-10-14T10:00:00Z",
            endDate = "2026-10-14T11:00:00Z", durationMin = 60,
            odometerDetails = DriveOdometerDetails(distance = 10.0),
            source = "teslamate_archive", qualityState = "observed")
        f.db.driveSummaryDao().upsert(requireNotNull(row(7).toSyncSummary(f.localId(1))))
        // A previous, unlinked positive-ID namespace is deliberately a different cohort.
        f.db.driveSummaryDao().upsertAll(listOf(
            requireNotNull(row(8).toSyncSummary(1)), requireNotNull(row(9).toSyncSummary(1))))
        val costs = ChargeCostOverrideStore(f.db.chargeCostOverrideDao(), f.settings)
        val stats = StatsRepository(f.db.driveSummaryDao(), f.db.chargeSummaryDao(),
            f.db.aggregateDao(), f.syncManager, f.geocodingRepository, f.vehicleContexts,
            costs, f.db, f.settings)
        val vm = ExportViewModel(f.context, f.db.driveSummaryDao(), f.db.chargeSummaryDao(), stats,
            f.vehicleContexts, f.db, f.settings, costs)
        try {
            vm.init(1)
            advanceUntilIdle()
            withContext(Dispatchers.Default) {
                withTimeout(15_000) { vm.uiState.first { it.availableYears.isNotEmpty() } }
            }
            advanceUntilIdle()
            assertTrue(vm.uiState.value.availableYears.contains(2026))
            assertEquals(1, vm.uiState.value.driveCount)
        } finally {
            vm.viewModelScope.cancel(); f.close(); Dispatchers.resetMain()
        }
    }
}

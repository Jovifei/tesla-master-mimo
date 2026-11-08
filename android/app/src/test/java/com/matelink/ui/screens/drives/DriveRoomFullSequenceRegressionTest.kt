package com.matelink.ui.screens.drives

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
@Config(application=Application::class, sdk=[28], manifest=Config.NONE)
class DriveRoomFullSequenceRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun quarantinedMiddleRowBlocksParkingInTheActualRoomFirstList() = runBlocking {
        val fixture = HistoryResourceFixture(File(temp.root, "drive-full-sequence.db"))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var vm: DrivesViewModel? = null
        try {
            fixture.initialize()
            val id = fixture.vehicleContexts.cachedContextForRemote(1)!!.localHistoryCarId
            fun row(key: Int, hour: Int, quality: String) = DriveData(key,
                startDate="2026-10-23T%02d:00:00Z".format(hour), endDate="2026-10-23T%02d:10:00Z".format(hour),
                source="teslamate_archive", sourceInstanceId="archive-A", qualityState=quality,
                odometerDetails=DriveOdometerDetails(distance=1.0), lengthUnit="km",
                startLatitude=31.0, startLongitude=121.0, endLatitude=31.0, endLongitude=121.0).toSyncSummary(id)!!
            fixture.db.driveSummaryDao().upsertAll(listOf(row(10, 1, "observed"), row(12, 2, "quarantined"), row(11, 3, "observed")))
            fixture.reopen()
            fixture.onRequest = { path -> if (path.endsWith("/cars")) { entered.complete(Unit); gate.await() } }
            fixture.status = { 503 }
            val page = DrivesViewModel(fixture.context, fixture.apiRepo,
                UnifiedHistoryRepository(fixture.apiRepo, fixture.vehicleContexts, fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao()),
                fixture.geocodingRepository, fixture.settings, fixture.db.driveSummaryDao(), fixture.vehicleContexts,
                Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC), SavedStateHandle())
            vm = page
            page.setCarId(1)
            withTimeout(10_000) { entered.await() }
            // The optional units request also calls /cars. Establish the real Room publication
            // before asserting its contents; every remote /cars request remains held.
            val local = withTimeout(10_000) { page.uiState.first { state -> state.historySequence.any { it.driveId == 10 || it.driveId == 11 } } }
            assertTrue("the adjacency input must retain quarantined chronology: ${local.historySequence.map { it.driveId }}",
                local.historySequence.any { it.driveId == 12 })
            assertFalse(local.drives.any { it.driveId == 12 })
            assertFalse(10 to 11 in local.parkedPairs)
        } finally {
            gate.complete(Unit); vm?.viewModelScope?.cancel(); fixture.close(); Dispatchers.resetMain()
        }
    }
}

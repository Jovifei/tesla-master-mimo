package com.matelink.ui.screens.drives

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.OpenMeteoApi
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.HistoryDetailRepository
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.WeatherRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.TripDetector
import com.matelink.domain.TripRepository
import java.io.File
import java.lang.reflect.Proxy
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

/** Actual Room + actual VM + intercepted HTTP; the held network cannot hide a verified cold cache. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class DriveDetailRoomFirstNetworkTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun cachedDetailIsPublishedBeforeSlowIdentityDiscoveryAndSurvivesNetworkFailure() = runBlocking<Unit> {
        val fixture = HistoryResourceFixture(File(temp.root, "drive-room-first.db"))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var vm: DriveDetailViewModel? = null
        try {
            fixture.initialize()
            val context = fixture.vehicleContexts.cachedContextForRemote(1)!!
            fixture.db.driveSummaryDao().upsert(DriveData(64, startDate="2026-10-14T00:00:00Z",
                endDate="2026-10-14T00:01:00Z", source="teslamate_archive", sourceInstanceId="archive-A",
                qualityState="observed", startAddress="cached A", endAddress="cached B").toSyncSummary(context.localHistoryCarId)!!)
            fixture.reopen()
            fixture.status = { 503 }
            fixture.onRequest = { path -> if (path.endsWith("/cars")) {
                entered.complete(Unit)
                gate.await()
            } }
            val trips = TripRepository(fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao(),
                fixture.db.aggregateDao(), fixture.db.savedTripDao(), TripDetector())
            val noWeather = Proxy.newProxyInstance(OpenMeteoApi::class.java.classLoader,
                arrayOf(OpenMeteoApi::class.java)) { _, _, _ -> error("No distance/route weather must be requested") } as OpenMeteoApi
            val page = DriveDetailViewModel(fixture.apiRepo, fixture.db.driveSummaryDao(),
                UnifiedHistoryRepository(fixture.apiRepo, fixture.vehicleContexts, fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao()),
                WeatherRepository(noWeather), trips, fixture.geocodingRepository, fixture.db,
                HistoryDetailRepository(fixture.db.historyDetailDao()))
            vm = page
            page.loadDriveDetail(1, 64)
            withTimeout(10_000) { entered.await() }
            assertEquals("cold Room cache must precede the held provider request", 64, page.uiState.value.driveDetail?.driveId)
            assertEquals("cached A", page.uiState.value.driveDetail?.startAddress)
            assertFalse(page.uiState.value.isLoading)
            gate.complete(Unit)
            val failedRefresh = withTimeout(10_000) { page.uiState.first { !it.isLoading && !it.isRefreshing } }
            assertEquals(64, failedRefresh.driveDetail?.driveId)
            assertEquals("cached A", failedRefresh.driveDetail?.startAddress)
        } finally {
            gate.complete(Unit)
            vm?.viewModelScope?.cancel()
            fixture.close()
            Dispatchers.resetMain()
        }
    }
}

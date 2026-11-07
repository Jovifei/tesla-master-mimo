package com.matelink.ui.screens.drives

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.OpenMeteoApi
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DrivePosition
import com.matelink.data.repository.HistoryDetailIdentity
import com.matelink.data.repository.HistoryDetailRepository
import com.matelink.data.repository.HistoryDetailWriteResult
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.WeatherRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.TripDetector
import com.matelink.domain.TripRepository
import java.io.File
import java.lang.reflect.Proxy
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real drive VM replays scoped Room points and suppresses a damaged local trace. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DriveDetailOfflinePointConsumerTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun offlineDriveViewModelReplaysPointsThenRejectsCorruptChunk() = runBlocking {
        val fixture = HistoryResourceFixture(File(temp.root, "drive-vm-points.db"))
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var vm: DriveDetailViewModel? = null
        try {
            fixture.initialize()
            val context = fixture.vehicleContexts.cachedContextForRemote(1)!!
            val start = "2026-10-14T00:00:00Z"
            val end = "2026-10-14T00:01:00Z"
            val list = DriveData(64, startDate = start, endDate = end,
                source = "teslamate_archive", sourceInstanceId = "archive-A",
                sourceId = "record-64", qualityState = "observed")
            fixture.db.driveSummaryDao().upsert(list.toSyncSummary(context.localHistoryCarId)!!)
            val identity = HistoryDetailIdentity.create(context, HistoryDetailIdentity.DRIVE,
                64, list.source, list.sourceInstanceId, start, end, list.sourceId)
            val detail = DriveDetail(64, startDate = start, endDate = end,
                source = list.source, sourceInstanceId = list.sourceInstanceId,
                sourceId = list.sourceId, lengthUnit = "km",
                positions = listOf(
                    DrivePosition(date = start, speed = 0.0, power = -55.0, batteryLevel = 80),
                    DrivePosition(date = start, speed = null, power = null),
                    DrivePosition(date = end, speed = 12.0, power = 10.0, batteryLevel = 79)))
            assertTrue(HistoryDetailRepository(fixture.db.historyDetailDao())
                .saveDrive(identity, detail) is HistoryDetailWriteResult.Stored)
            fixture.reopen()
            fixture.status = { 503 }

            val tripRepo = TripRepository(fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao(),
                fixture.db.aggregateDao(), fixture.db.savedTripDao(), TripDetector())
            val noWeather = Proxy.newProxyInstance(OpenMeteoApi::class.java.classLoader,
                arrayOf(OpenMeteoApi::class.java)) { _, _, _ ->
                error("Weather must not be requested for a distance-less cached drive")
            } as OpenMeteoApi
            val viewModel = DriveDetailViewModel(fixture.apiRepo, fixture.db.driveSummaryDao(),
                UnifiedHistoryRepository(fixture.apiRepo, fixture.vehicleContexts,
                    fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao()),
                WeatherRepository(noWeather), tripRepo, fixture.geocodingRepository,
                fixture.db, HistoryDetailRepository(fixture.db.historyDetailDao()))
            vm = viewModel
            viewModel.loadDriveDetail(1, 64)
            val restored = withTimeout(10_000) { viewModel.uiState.first { !it.isLoading } }
            assertEquals(3, restored.driveDetail?.positions?.size)
            assertEquals(-55.0, restored.driveDetail!!.positions!![0].power!!, 0.0)
            assertEquals(0.0, restored.driveDetail!!.positions!![0].speed!!, 0.0)
            assertEquals("history_detail_cached_total_unverified", restored.historySyncWarning)

            val receiptId = HistoryDetailRepository(fixture.db.historyDetailDao()).readDrive(identity)
                .receipt!!.receiptId
            fixture.db.openHelper.writableDatabase.execSQL(
                "UPDATE history_detail_chunks SET payload = X'00' WHERE receiptId = ? AND chunkIndex = 0",
                arrayOf(receiptId))
            viewModel.loadDriveDetail(1, 64)
            val damaged = withTimeout(10_000) { viewModel.uiState.first { !it.isLoading } }
            assertNull(damaged.driveDetail?.positions)
            assertEquals("history_detail_cache_corrupt", damaged.historySyncWarning)
            assertFalse(damaged.isLoading)
        } finally {
            vm?.viewModelScope?.cancel()
            fixture.close()
            Dispatchers.resetMain()
        }
    }
}

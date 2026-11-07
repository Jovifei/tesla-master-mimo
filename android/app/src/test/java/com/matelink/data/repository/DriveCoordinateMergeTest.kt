package com.matelink.data.repository

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.HistoryContextData
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.cloudOriginVehicleStableIdentity
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.toAnalysisDriveData
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class DriveCoordinateMergeTest {
    @get:Rule val temp = TemporaryFolder()

    private val drive = DriveData(41, startDate = "2026-10-30T10:00:00Z", endDate = "2026-10-30T10:30:00Z",
        source = "teslamate_archive", sourceInstanceId = "archive-A", sourceRecordId = "drive-41", qualityState = "observed")

    private fun merge(remote: DriveData, cached: DriveData): DriveData =
        UnifiedHistoryRepository.mergeDrivesRaw(listOf(remote), listOf(cached)).single()

    @Test fun complementaryPartialAxesNeverBecomeAnInventedLocation() {
        val remote = drive.copy(startLatitude = 31.0, endLongitude = 121.0)
        val cached = drive.copy(startLongitude = 120.0, endLatitude = 30.0)
        val result = merge(remote, cached)
        assertNull(result.startLatitude); assertNull(result.startLongitude)
        assertNull(result.endLatitude); assertNull(result.endLongitude)
    }

    @Test fun eachEndpointUsesOneCompleteObservationAndRemoteWinsWhenValid() {
        val remote = drive.copy(startLatitude = 32.0, endLatitude = 0.0, endLongitude = 122.0)
        val cached = drive.copy(startLatitude = 30.0, startLongitude = 120.0, endLatitude = 31.0, endLongitude = 121.0)
        val result = merge(remote, cached)
        assertEquals(30.0, requireNotNull(result.startLatitude), 0.0)
        assertEquals(120.0, requireNotNull(result.startLongitude), 0.0)
        assertEquals(0.0, requireNotNull(result.endLatitude), 0.0)
        assertEquals(122.0, requireNotNull(result.endLongitude), 0.0)
    }

    @Test fun invalidRemotePairFallsBackOnlyToACompleteValidCachedPair() {
        val cached = drive.copy(startLatitude = 30.0, startLongitude = 120.0)
        for ((latitude, longitude) in listOf(
            Double.NaN to 121.0, Double.POSITIVE_INFINITY to 121.0, 91.0 to 121.0,
            30.0 to 181.0, 0.0 to 0.0
        )) {
            val result = merge(drive.copy(startLatitude = latitude, startLongitude = longitude), cached)
            assertEquals(30.0, requireNotNull(result.startLatitude), 0.0)
            assertEquals(120.0, requireNotNull(result.startLongitude), 0.0)
        }
        val invalidBoth = merge(drive.copy(startLatitude = 30.0), drive.copy(startLongitude = 120.0))
        assertNull(invalidBoth.startLatitude); assertNull(invalidBoth.startLongitude)
    }

    @Test fun foreignSourceOrSessionNeverBorrowsCachedCoordinates() {
        val cached = drive.copy(startLatitude = 30.0, startLongitude = 120.0)
        for (foreign in listOf(
            drive.copy(sourceInstanceId = "archive-B", startLatitude = 31.0),
            drive.copy(sourceRecordId = "other-drive", startLatitude = 31.0),
            drive.copy(endDate = "2026-10-30T10:31:00Z", startLatitude = 31.0)
        )) {
            val result = merge(foreign, cached)
            assertEquals(31.0, requireNotNull(result.startLatitude), 0.0)
            assertNull(result.startLongitude)
        }
    }

    @Test fun scopedRepositoryAndRoomReopenKeepValidPairsAndOriginalWireReceipt() = runBlocking {
        val path = File(temp.root, "drive-coordinates.db").absolutePath
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), StatsDatabase::class.java, path)
            .allowMainThreadQueries().build()
        var db = open()
        val cached = drive.copy(startLatitude = 30.0, startLongitude = 120.0, endLatitude = 31.0, endLongitude = 121.0)
        val remote = drive.copy(startLatitude = 32.0, endLatitude = 32.0, endLongitude = 122.0)
        val incoming = requireNotNull(remote.toSyncSummary(-7))
        try {
            db.driveSummaryDao().upsert(requireNotNull(cached.toSyncSummary(-7)))
            val loaded = repository(db.driveSummaryDao(), remote).load(7) as ApiResult.Success
            val shown = loaded.data.drives.single()
            assertEquals(30.0, requireNotNull(shown.startLatitude), 0.0)
            assertEquals(120.0, requireNotNull(shown.startLongitude), 0.0)
            assertEquals(32.0, requireNotNull(shown.endLatitude), 0.0)
            assertEquals(122.0, requireNotNull(shown.endLongitude), 0.0)
            val saved = requireNotNull(db.driveSummaryDao().get(-7, drive.driveId))
            assertEquals(HistorySummaryEvidenceCodec.sourceJson(incoming.apiEvidence),
                HistorySummaryEvidenceCodec.sourceJson(saved.apiEvidence))
            db.close(); db = open()
            val reopened = requireNotNull(db.driveSummaryDao().get(-7, drive.driveId)).toAnalysisDriveData()
            assertEquals(30.0, requireNotNull(reopened.startLatitude), 0.0)
            assertEquals(120.0, requireNotNull(reopened.startLongitude), 0.0)
            assertEquals(32.0, requireNotNull(reopened.endLatitude), 0.0)
            assertEquals(122.0, requireNotNull(reopened.endLongitude), 0.0)
        } finally { db.close() }
    }

    private fun repository(dao: DriveSummaryDao, remote: DriveData): UnifiedHistoryRepository {
        val scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "coordinate-test", "https://coordinate.invalid")
        val context = VehicleContext(7,
            cloudOriginVehicleStableIdentity("coordinate-test", "https://coordinate.invalid", "vehicle-test"),
            -7, HistoryConnectionSource.CLOUD, "cloud")
        return UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { scope }, getCars = { ApiResult.Success(listOf(CarData(7, vehicleUid = "vehicle-test"))) },
            getHistoryContext = { ApiResult.Success(HistoryContextData(1, 7, "vehicle-test")) },
            resolveCar = { _, _ -> context }, cachedContext = { _, _ -> context },
            localDrives = { dao.getAllChronological(it) }, localCharges = { emptyList() },
            getDrives = { _, _, _, page -> ApiResult.Success(if (page == 1) listOf(remote) else emptyList()) },
            getCharges = { _, _, _, _ -> ApiResult.Success(emptyList()) },
            persistDrives = { dao.upsertPreservingEvidence(it) }, persistCharges = {}
        ))
    }
}

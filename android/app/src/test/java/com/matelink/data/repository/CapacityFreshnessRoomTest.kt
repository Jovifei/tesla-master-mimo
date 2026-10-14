package com.matelink.data.repository

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.*
import com.matelink.data.local.StatsDatabase
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class CapacityFreshnessRoomTest {
    @get:Rule val temp = TemporaryFolder()
    private val raw = DriveData(4, startDate = "2026-10-13T00:00:00Z",
        endDate = "2026-10-13T00:01:00Z", source = "teslamate_archive",
        qualityState = "observed", batteryDetails = DriveBatteryDetails(80, 79),
        odometerDetails = DriveOdometerDetails(distance = 2.0))
    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), StatsDatabase::class.java,
        File(temp.root, "capacity.db").absolutePath).allowMainThreadQueries().build()
    private fun resolve(capacity: Double) = raw.asCachedDetail().resolveDriveEnergy(
        batteryCapacityKwh = capacity, capacityBasis = "api_reported_current_capacity")

    @Test fun delayedSameRankResponseCannotOverwriteRoomReceiptAndReopen() = runBlocking {
        var db = open()
        try {
            val dao = db.driveSummaryDao()
            val detail = raw.asCachedDetail()
            dao.upsert(raw.toSyncSummary(-7)!!)
            dao.mergeResolvedDetail(-7, detail, resolve(90.0)) {}
            val delayed = CompletableDeferred<ResolvedDriveEnergy>()
            val stale = async { dao.refineResolvedCapacity(-7, detail, delayed.await()) {} }
            yield()
            delayed.complete(resolve(60.0))
            withTimeout(10_000) { stale.await() }
            db.close()
            db = open()
            val saved = db.driveSummaryDao().get(-7, 4)!!
            assertEquals(90.0, saved.toAnalysisDriveData().energyContract!!.netEnergy!!.capacityKwh!!, 0.0)
            assertEquals("api_reported_current_capacity", saved.toAnalysisDriveData().energyContract!!.netEnergy!!.capacityBasis)
            assertEquals(0.9, saved.energyConsumed!!, 1e-12)
            assertEquals(450.0, saved.efficiency!!, 1e-12)
            assertEquals(raw.toSyncSummary(-7)!!.apiEvidence, HistorySummaryEvidenceCodec.sourceJson(saved.apiEvidence))
        } finally { if (db.isOpen) db.close() }
    }

    @Test fun roomRejectsUnknownLargerSameRankCapacityRatherThanPickingMaximum() = runBlocking {
        val db = open()
        try {
            val dao = db.driveSummaryDao()
            val detail = raw.asCachedDetail()
            dao.upsert(raw.toSyncSummary(-7)!!)
            dao.mergeResolvedDetail(-7, detail, resolve(60.0)) {}
            dao.refineResolvedCapacity(-7, detail, resolve(90.0)) {}
            val saved = dao.get(-7, 4)!!
            assertEquals(60.0, saved.toAnalysisDriveData().energyContract!!.netEnergy!!.capacityKwh!!, 0.0)
            assertEquals(0.6, saved.energyConsumed!!, 1e-12)
        } finally { db.close() }
    }

    @Test fun capacityRefinementRetainsVehicleSourceWindowAndAccountFences() = runBlocking {
        val db = open()
        try {
            val dao = db.driveSummaryDao()
            val detail = raw.asCachedDetail()
            dao.upsert(raw.toSyncSummary(-7)!!)
            dao.mergeResolvedDetail(-7, detail, resolve(90.0)) {}
            val before = dao.get(-7, 4)!!
            assertNull(dao.refineResolvedCapacity(-8, detail, resolve(60.0)) {})
            assertNull(dao.refineResolvedCapacity(-7, detail.copy(source = "local_import"), resolve(60.0)) {})
            assertNull(dao.refineResolvedCapacity(-7, detail.copy(endDate = "2026-10-13T00:02:00Z"), resolve(60.0)) {})
            try {
                dao.refineResolvedCapacity(-7, detail, resolve(60.0)) {
                    throw CancellationException("account generation invalidated")
                }
                fail("account fence must reject metadata")
            } catch (_: CancellationException) { }
            assertEquals(before, dao.get(-7, 4))
        } finally { db.close() }
    }
}

package com.matelink.ui.screens.drives

import android.app.Application
import androidx.room.Room
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.ChargeSummary
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Room query + reopen regression; no owner database or network is accessed. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ParkedRoomAdjacencyIntegrationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(),
        StatsDatabase::class.java, File(temp.root, "parked-adjacency.db").absolutePath)
        .allowMainThreadQueries().build()

    private fun drive(carId: Int, id: Int, start: String, end: String, quality: String = "observed") =
        DriveSummary(
            driveId = id, carId = carId, startDate = start, endDate = end, durationMin = 2,
            startAddress = "", endAddress = "", distance = 1.0, speedMax = 30, speedAvg = 20,
            powerMax = 10, powerMin = -1, startBatteryLevel = 40, endBatteryLevel = 39,
            outsideTempAvg = null, insideTempAvg = null, energyConsumed = null, efficiency = null,
            qualityState = quality,
            apiEvidence = """{"drive_id":$id,"start_date":"$start","end_date":"$end","source":"teslamate_archive","source_instance_id":"archive-A"}"""
        )

    private fun charge(carId: Int) : ChargeSummary {
        val start = "2026-10-23T21:10:00Z"
        val end = "2026-10-23T21:30:00Z"
        val receipt = """{"charge_id":501,"start_date":"$start","end_date":"$end","source":"teslamate_archive","source_instance_id":"archive-A","charge_energy_added":0.0}"""
        return ChargeSummary(501, carId, start, end, 20, "", 0.0, 0.0,
            0.0, null, null, 39, 39, null, 0.0, apiEvidence = receipt, qualityState = "observed")
    }

    @Test fun actualRoomReopenRetainsAdjacencyVetoWithoutDestroyingHistory() = runBlocking {
        var db = open()
        try {
            val older = drive(-101, 10, "2026-10-23T20:58:00Z", "2026-10-23T20:59:00Z")
            val newer = drive(-101, 11, "2026-10-23T22:20:00Z", "2026-10-23T22:21:00Z")
            db.driveSummaryDao().upsertAll(listOf(older, newer))
            db.chargeSummaryDao().upsert(charge(-101))
            assertNotNull(readLocalParkedDetail(db.driveSummaryDao(), -101, 10, 11))
            assertEquals(listOf(501), readLocalParkedCharges(db.driveSummaryDao(),
                db.chargeSummaryDao(), -101, 10, 11).map { it.chargeId })
            val middle = drive(-101, 12, "2026-10-23T21:00:00+00:00",
                "2026-10-23T21:01:00Z", quality = "quarantined").copy(distance = 0.1)
            db.driveSummaryDao().upsert(middle)
            db.close()
            db = open()
            assertNull(readLocalParkedDetail(db.driveSummaryDao(), -101, 10, 11))
            assertTrue(readLocalParkedCharges(db.driveSummaryDao(), db.chargeSummaryDao(),
                -101, 10, 11).isEmpty())
            assertNotNull(db.driveSummaryDao().getAnyForPersistence(-101, 12))
            db.driveSummaryDao().deleteDriveById(-101, 12)
            db.driveSummaryDao().upsert(middle.copy(carId = -202))
            assertNotNull(readLocalParkedDetail(db.driveSummaryDao(), -101, 10, 11))
            assertEquals(listOf(501), readLocalParkedCharges(db.driveSummaryDao(),
                db.chargeSummaryDao(), -101, 10, 11).map { it.chargeId })
        } finally { db.close() }
    }
}

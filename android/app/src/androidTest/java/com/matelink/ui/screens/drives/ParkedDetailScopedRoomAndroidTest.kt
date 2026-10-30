package com.matelink.ui.screens.drives

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParkedDetailScopedRoomAndroidTest {
    private fun drive(carId: Int, driveId: Int, start: String, end: String, startSoc: Int, endSoc: Int,
                      instance: String = "instance-A") =
        DriveSummary(
            driveId = driveId, carId = carId, startDate = start, endDate = end, durationMin = 1,
            startAddress = "", endAddress = "", distance = 1.0, speedMax = 20, speedAvg = 10,
            powerMax = 5, powerMin = -2, startBatteryLevel = startSoc, endBatteryLevel = endSoc,
            outsideTempAvg = null, insideTempAvg = null, energyConsumed = null, efficiency = null,
            apiEvidence = """{"drive_id":$driveId,"start_date":"$start","end_date":"$end","battery_details":{"start_battery_level":$startSoc,"end_battery_level":$endSoc},"source":"teslamate_archive","source_instance_id":"$instance"}"""
        )

    private fun charge(carId: Int, id: Int, instance: String?) : ChargeSummary {
        val start = "2026-10-23T21:10:00Z"
        val end = "2026-10-23T21:30:00Z"
        val receipt = instance?.let {
            """{"charge_id":$id,"start_date":"$start","end_date":"$end","source":"teslamate_archive","source_instance_id":"$it","charge_energy_added":0.0}"""
        }
        return ChargeSummary(id, carId, start, end, 20, "", 0.0, 0.0,
            0.0, null, null, 31, 32, null, 0.0,
            apiEvidence = receipt, qualityState = "observed")
    }

    @Test fun localParkedReaderUsesOneVerifiedHistoryCarKeyForBothBoundaries() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, StatsDatabase::class.java
        ).build()
        try {
            val dao = db.driveSummaryDao()
            val older = drive(-101, 10, "2026-10-23T20:58:00Z", "2026-10-23T20:59:00Z", 32, 31)
            val foreignNewer = drive(-202, 11, "2026-10-23T22:20:00Z", "2026-10-23T22:21:00Z", 80, 79)
            dao.upsertAll(listOf(older, foreignNewer))
            assertNull(readLocalParkedDetail(dao, -101, 10, 11))
            assertNull(readLocalParkedDetail(dao, -202, 10, 11))

            dao.upsert(drive(-101, 11, "2026-10-23T22:20:00Z", "2026-10-23T22:21:00Z", 30, 79,
                instance = "instance-B"))
            assertNull(readLocalParkedDetail(dao, -101, 10, 11))
            dao.upsert(drive(-101, 11, "2026-10-23T22:20:00Z", "2026-10-23T22:21:00Z", 30, 79))
            val own = requireNotNull(readLocalParkedDetail(dao, -101, 10, 11))
            assertEquals(31, own.startBatteryLevel)
            assertEquals(30, own.endBatteryLevel)
            assertEquals(-1, own.batteryDelta)
            assertEquals("local_room_cache", own.source)

            db.chargeSummaryDao().upsertAll(listOf(
                charge(-101, 501, "instance-A"), charge(-101, 502, "instance-B"),
                charge(-101, 503, null), charge(-202, 504, "instance-A")
            ))
            assertEquals(listOf(501), readLocalParkedCharges(dao, db.chargeSummaryDao(), -101, 10, 11)
                .map { it.chargeId })

            dao.upsert(older.copy(qualityState = "quarantined"))
            assertNull(readLocalParkedDetail(dao, -101, 10, 11))
        } finally {
            db.close()
        }
    }

    @Test fun interveningShortOrQuarantinedDriveStopsLocalParkedAndChargePublication() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, StatsDatabase::class.java
        ).build()
        try {
            val dao = db.driveSummaryDao()
            val older = drive(-101, 10, "2026-10-23T20:58:00Z", "2026-10-23T20:59:00Z", 32, 31)
            val newer = drive(-101, 11, "2026-10-23T22:20:00Z", "2026-10-23T22:21:00Z", 30, 29)
            dao.upsertAll(listOf(older, newer))
            db.chargeSummaryDao().upsert(charge(-101, 501, "instance-A"))
            assertEquals(-1, requireNotNull(readLocalParkedDetail(dao, -101, 10, 11)).batteryDelta)
            assertEquals(listOf(501), readLocalParkedCharges(dao, db.chargeSummaryDao(), -101, 10, 11)
                .map { it.chargeId })

            val short = drive(-101, 12, "2026-10-23T21:00:00+00:00",
                "2026-10-23T21:01:00Z", 31, 31).copy(distance = 0.1,
                    qualityState = "quarantined")
            dao.upsert(short)
            assertNull(readLocalParkedDetail(dao, -101, 10, 11))
            assertEquals(emptyList<Int>(), readLocalParkedCharges(dao, db.chargeSummaryDao(), -101, 10, 11)
                .map { it.chargeId })
            dao.deleteDriveById(-101, 12)
            dao.upsert(short.copy(carId = -202))
            assertEquals(-1, requireNotNull(readLocalParkedDetail(dao, -101, 10, 11)).batteryDelta)
            assertEquals(listOf(501), readLocalParkedCharges(dao, db.chargeSummaryDao(), -101, 10, 11)
                .map { it.chargeId })
        } finally {
            db.close()
        }
    }
}

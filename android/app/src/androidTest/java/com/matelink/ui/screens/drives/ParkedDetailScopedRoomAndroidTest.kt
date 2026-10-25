package com.matelink.ui.screens.drives

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.entity.DriveSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParkedDetailScopedRoomAndroidTest {
    private fun drive(carId: Int, driveId: Int, start: String, end: String, startSoc: Int, endSoc: Int) =
        DriveSummary(
            driveId = driveId, carId = carId, startDate = start, endDate = end, durationMin = 1,
            startAddress = "", endAddress = "", distance = 1.0, speedMax = 20, speedAvg = 10,
            powerMax = 5, powerMin = -2, startBatteryLevel = startSoc, endBatteryLevel = endSoc,
            outsideTempAvg = null, insideTempAvg = null, energyConsumed = null, efficiency = null
        )

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

            dao.upsert(foreignNewer.copy(carId = -101, startBatteryLevel = 30))
            val own = requireNotNull(readLocalParkedDetail(dao, -101, 10, 11))
            assertEquals(31, own.startBatteryLevel)
            assertEquals(30, own.endBatteryLevel)
            assertEquals(-1, own.batteryDelta)
            assertEquals("local_room_cache", own.source)

            dao.upsert(older.copy(qualityState = "quarantined"))
            assertNull(readLocalParkedDetail(dao, -101, 10, 11))
        } finally {
            db.close()
        }
    }
}

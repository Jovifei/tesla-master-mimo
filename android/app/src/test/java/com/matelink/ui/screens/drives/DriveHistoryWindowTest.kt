package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveHistoryWindowTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun selectedSevenDaysExcludeOlderAndNextDayFromTheActualSummaryCohort() {
        val start = LocalDate.of(2026, 10, 19)
        val end = LocalDate.of(2026, 10, 25)
        val rows = listOf(
            drive(1, "2026-10-18T23:59:59+08:00"),
            drive(2, "2026-10-19T00:00:00+08:00"),
            drive(3, "2026-10-25T23:59:59.999+08:00"),
            drive(4, "2026-10-26T00:00:00+08:00")
        )
        val visible = rows.filter { driveInHistoryWindow(it.startDate, start, end, zone) }
        assertEquals(listOf(2, 3), visible.map { it.driveId })
        assertEquals(2, calculateDrivesSummary(visible).totalDrives)
    }

    @Test fun oldSingleDayRemainsAvailableFromLocalRowsWithoutWideningARecentPreset() {
        val old = drive(8, "2026-09-20T10:00:00+08:00")
        val recent = drive(9, "2026-10-23T10:00:00+08:00")
        val rows = listOf(old, recent)
        assertEquals(listOf(9), rows.filter { driveInHistoryWindow(it.startDate,
            LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25), zone) }.map { it.driveId })
        assertEquals(listOf(8), rows.filter { driveInHistoryWindow(it.startDate,
            LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 20), zone) }.map { it.driveId })
    }

    @Test fun localCalendarDayUsesTheDeviceZoneAndUnknownTimeFailsClosed() {
        val utc = "2026-10-18T16:30:00Z"
        val day = LocalDate.of(2026, 10, 19)
        assertTrue(driveInHistoryWindow(utc, day, day, zone))
        assertFalse(driveInHistoryWindow(utc, day, day, ZoneId.of("America/Los_Angeles")))
        assertFalse(driveInHistoryWindow(null, day, day, zone))
        assertTrue(driveInHistoryWindow(null, null, null, zone))
    }

    private fun drive(id: Int, timestamp: String) = DriveData(
        driveId = id,
        startDate = timestamp,
        odometerDetails = DriveOdometerDetails(distance = 1.0),
        durationMin = 5,
        source = "fixture",
        qualityState = "observed"
    )
}

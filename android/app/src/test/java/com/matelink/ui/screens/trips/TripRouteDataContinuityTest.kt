package com.matelink.ui.screens.trips

import com.matelink.data.api.models.*
import com.matelink.data.sync.toSyncSummary
import org.junit.Assert.*
import org.junit.Test

class TripRouteDataContinuityTest {
    @Test fun oneDriveKeepsItsWeatherSlotButBreaksMapLinesAcrossUnobservedGps() {
        val drive = DriveData(7, startDate = "2026-10-27T00:00:00Z", endDate = "2026-10-27T00:01:00Z",
            source = "teslamate_archive", qualityState = "observed").toSyncSummary(-1)!!
        val detail = DriveDetail(7, startDate = drive.startDate, endDate = drive.endDate, positions = listOf(
            DrivePosition(date = "2026-10-27T00:00:00Z", latitude = 30.0, longitude = 120.0),
            DrivePosition(date = "2026-10-27T00:00:10Z", latitude = 30.01, longitude = 120.01),
            DrivePosition(date = "2026-10-27T00:00:15Z"),
            DrivePosition(date = "2026-10-27T00:00:20Z", latitude = 30.02, longitude = 120.02),
            DrivePosition(date = "2026-10-27T00:00:30Z", latitude = 30.03, longitude = 120.03)))
        val slot = tripRouteSegment(drive, detail, "receipt-v1")
        assertEquals(7, slot.driveId)
        assertEquals("receipt-v1", slot.receiptId)
        assertEquals(2, slot.parts.size)
        assertEquals(listOf(listOf(30.0, 30.01), listOf(30.02, 30.03)), slot.parts.map { it.map { p -> p.latitude } })
        assertEquals("2026-10-27T00:00:10Z", slot.points[1].observedAt)
    }
}

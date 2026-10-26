package com.matelink.domain

import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class TripChronologyConsumerTest {
    private fun drive(id: Int, start: String, end: String) = DriveSummary(
        driveId = id, carId = -701, startDate = start, endDate = end, durationMin = 60,
        startAddress = "A$id", endAddress = "B$id", distance = 10.0,
        speedMax = 100, speedAvg = 80, powerMax = 30, powerMin = -10,
        startBatteryLevel = 80, endBatteryLevel = 50, outsideTempAvg = null,
        insideTempAvg = null, energyConsumed = null, efficiency = null)

    @Test fun aggregatorOrdersOffsetTimesByTheirInstantBeforeTakingEndpoints() {
        val later = drive(2, "2026-10-14T08:30:00Z", "2026-10-14T09:30:00Z")
        val earlier = drive(1, "2026-10-14T09:00:00+02:00", "2026-10-14T10:00:00+02:00")
        val trip = requireNotNull(TripAggregator.buildTrip(listOf(later, earlier), emptyList()))
        assertEquals(listOf(1, 2), trip.drives.map { it.driveId })
        assertEquals(earlier.startDate, trip.startDate)
        assertEquals(later.endDate, trip.endDate)
        assertEquals(150, trip.totalDurationMin)
    }

    @Test fun manualTripDurationIncludesItsExplicitChargeOutsideTheDriveWindow() {
        val drive = drive(1, "2026-10-14T10:00:00Z", "2026-10-14T11:00:00Z")
        val charge = ChargeSummary(chargeId = 9, carId = -701,
            startDate = "2026-10-14T09:00:00Z", endDate = "2026-10-14T09:30:00Z",
            durationMin = 30, address = "A", latitude = 1.0, longitude = 2.0,
            energyAdded = 20.0, energyUsed = null, cost = null, startBatteryLevel = 50,
            endBatteryLevel = 80, outsideTempAvg = null, odometer = 100.0)
        val trip = requireNotNull(TripAggregator.buildTrip(listOf(drive), listOf(charge)))
        assertEquals(charge.startDate, trip.startDate)
        assertEquals(drive.endDate, trip.endDate)
        assertEquals(120, trip.totalDurationMin)
    }
}

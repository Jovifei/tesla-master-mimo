package com.matelink.reports

import com.matelink.data.api.models.ChargeBatteryDetails
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.ui.screens.reports.annualStandbyKwh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class AnnualReportMetricsTest {
    @Test
    fun standbyRequiresObservedCapacityAndNoDrive() {
        val previous = ChargeData(1, startDate = "2026-01-01T00:00:00Z", endDate = "2026-01-01T01:00:00Z", batteryDetails = ChargeBatteryDetails(endBatteryLevel = 80))
        val next = ChargeData(2, startDate = "2026-01-02T00:00:00Z", endDate = "2026-01-02T01:00:00Z", batteryDetails = ChargeBatteryDetails(startBatteryLevel = 70))
        assertNull(annualStandbyKwh(2026, listOf(previous, next), emptyList()))
        assertEquals(7.5, annualStandbyKwh(2026, listOf(previous, next), emptyList(), 75.0)!!, 0.0)
        val drive = DriveData(9, startDate = "2026-01-01T12:00:00Z", endDate = "2026-01-01T13:00:00Z")
        assertNull(annualStandbyKwh(2026, listOf(previous, next), listOf(drive), 75.0))
    }

    @Test
    fun offsetOrderingAndLocalYearUseTheSameInstant() {
        val previous = ChargeData(1, startDate = "2026-12-31T23:00:00+08:00",
            endDate = "2026-12-31T23:30:00+08:00",
            batteryDetails = ChargeBatteryDetails(endBatteryLevel = 80))
        val next = ChargeData(2, startDate = "2026-12-31T17:00:00Z",
            endDate = "2026-12-31T17:30:00Z",
            batteryDetails = ChargeBatteryDetails(startBatteryLevel = 70))
        val charges = listOf(next, previous) // Neither caller order nor text order is instant order.
        val shanghai = ZoneId.of("Asia/Shanghai")
        val utc = ZoneId.of("UTC")
        assertEquals(7.5, annualStandbyKwh(2027, charges, emptyList(), 75.0, shanghai)!!, 0.0)
        assertNull(annualStandbyKwh(2026, charges, emptyList(), 75.0, shanghai))
        assertEquals(7.5, annualStandbyKwh(2026, charges, emptyList(), 75.0, utc)!!, 0.0)
        assertNull(annualStandbyKwh(2027, charges, emptyList(), 75.0, utc))
    }

    @Test
    fun zeroNegativeOrInvalidSocDropDoesNotInventStandbyEnergy() {
        val previous = ChargeData(1, startDate = "2026-01-01T00:00:00Z",
            endDate = "2026-01-01T01:00:00Z",
            batteryDetails = ChargeBatteryDetails(endBatteryLevel = 80))
        val next = ChargeData(2, startDate = "2026-01-02T00:00:00Z",
            endDate = "2026-01-02T01:00:00Z",
            batteryDetails = ChargeBatteryDetails(startBatteryLevel = 80))
        val zone = ZoneId.of("UTC")
        assertNull(annualStandbyKwh(2026, listOf(previous, next), emptyList(), 75.0, zone))
        assertEquals(60.0, annualStandbyKwh(2026, listOf(previous,
            next.copy(batteryDetails = ChargeBatteryDetails(startBatteryLevel = 0))),
            emptyList(), 75.0, zone)!!, 0.0)
        assertNull(annualStandbyKwh(2026, listOf(previous,
            next.copy(batteryDetails = ChargeBatteryDetails(startBatteryLevel = 90))),
            emptyList(), 75.0, zone))
        assertNull(annualStandbyKwh(2026, listOf(previous,
            next.copy(batteryDetails = ChargeBatteryDetails(startBatteryLevel = -1))),
            emptyList(), 75.0, zone))
    }

    @Test
    fun onlyAdjacentNonOverlappingChargeBoundariesWithoutDriveQualify() {
        val previous = ChargeData(1, startDate = "2026-01-01T00:00:00Z",
            endDate = "2026-01-01T01:00:00Z",
            batteryDetails = ChargeBatteryDetails(endBatteryLevel = 80))
        val next = ChargeData(2, startDate = "2026-01-02T00:00:00Z",
            endDate = "2026-01-02T01:00:00Z",
            batteryDetails = ChargeBatteryDetails(startBatteryLevel = 70))
        val zone = ZoneId.of("UTC")
        val parkedDrive = DriveData(1, startDate = "2026-01-01T12:00:00Z",
            endDate = "2026-01-01T13:00:00Z")
        val endsAtBoundary = DriveData(2, startDate = "2026-01-01T00:30:00Z",
            endDate = "2026-01-01T01:00:00Z")
        val startsAtBoundary = DriveData(3, startDate = "2026-01-02T00:00:00Z",
            endDate = "2026-01-02T00:30:00Z")
        assertNull(annualStandbyKwh(2026, listOf(previous, next), listOf(parkedDrive), 75.0, zone))
        assertEquals(7.5, annualStandbyKwh(2026, listOf(previous, next),
            listOf(endsAtBoundary, startsAtBoundary), 75.0, zone)!!, 0.0)
        assertNull(annualStandbyKwh(2026, listOf(previous,
            next.copy(startDate = "2026-01-01T00:30:00Z")), emptyList(), 75.0, zone))
        val middle = ChargeData(3, startDate = "2026-01-01T12:00:00Z",
            endDate = "2026-01-01T13:00:00Z")
        assertNull(annualStandbyKwh(2026, listOf(previous, next, middle), emptyList(), 75.0, zone))
        assertNull(annualStandbyKwh(2026, listOf(previous,
            next.copy(startDate = "2026-01-02T00:00:00")), emptyList(), 75.0, zone))
        assertNull(annualStandbyKwh(2026, listOf(previous, next),
            listOf(parkedDrive.copy(endDate = null)), 75.0, zone))
    }
}

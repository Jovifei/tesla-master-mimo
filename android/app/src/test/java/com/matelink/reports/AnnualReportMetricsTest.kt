package com.matelink.reports

import com.matelink.data.api.models.ChargeBatteryDetails
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.ui.screens.reports.annualEffectiveCost
import com.matelink.ui.screens.reports.annualStandbyKwh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnnualReportMetricsTest {
    @Test
    fun manualTotalOverridesOriginalCost() {
        val charge = ChargeData(1, startDate = "2026-02-01T00:00:00Z", cost = 10.0)
        assertEquals(38.5, annualEffectiveCost(4, 2026, listOf(charge), mapOf("4:1" to 38.5))!!, 0.0)
    }

    @Test
    fun standbyRequiresObservedCapacityAndNoDrive() {
        val previous = ChargeData(1, startDate = "2026-01-01T00:00:00Z", endDate = "2026-01-01T01:00:00Z", batteryDetails = ChargeBatteryDetails(endBatteryLevel = 80))
        val next = ChargeData(2, startDate = "2026-01-02T00:00:00Z", endDate = "2026-01-02T01:00:00Z", batteryDetails = ChargeBatteryDetails(startBatteryLevel = 70))
        assertNull(annualStandbyKwh(2026, listOf(previous, next), emptyList()))
        assertEquals(7.5, annualStandbyKwh(2026, listOf(previous, next), emptyList(), 75.0)!!, 0.0)
        val drive = DriveData(9, startDate = "2026-01-01T12:00:00Z", endDate = "2026-01-01T13:00:00Z")
        assertNull(annualStandbyKwh(2026, listOf(previous, next), listOf(drive), 75.0))
    }
}

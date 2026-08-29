package com.matelink.reports

import com.matelink.data.api.models.ChargeBatteryDetails
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.StandbyWindowData
import com.matelink.ui.screens.reports.annualEffectiveCost
import com.matelink.ui.screens.reports.annualStandbyKwh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnnualReportMetricsTest {
    @Test
    fun manualTotalOverridesOriginalCost() {
        val charge = ChargeData(
            1,
            startDate = "2026-02-01T00:00:00Z",
            cost = 10.0
        )
        assertEquals(
            38.5,
            annualEffectiveCost(
                4,
                2026,
                listOf(charge),
                mapOf("4:1" to 38.5)
            )!!,
            0.0
        )
    }

    @Test
    fun missingTeslaMateCostUsesTheSharedEstimateRule() {
        val charge = ChargeData(
            1,
            startDate = "2026-02-01T00:00:00Z",
            chargeEnergyAdded = 10.0
        )
        assertEquals(
            11.0,
            annualEffectiveCost(
                4,
                2026,
                listOf(charge),
                emptyMap()
            )!!,
            0.0
        )
    }

    @Test
    fun standbyWindowsRequireObservedPowerCoverage() {
        val qualified = StandbyWindowData(
            startDate = "2026-01-01T00:00:00Z",
            endDate = "2026-01-01T03:00:00Z",
            energyKwh = 1.5,
            coverageRatio = 0.9
        )
        val insufficient = StandbyWindowData(
            startDate = "2026-01-02T00:00:00Z",
            endDate = "2026-01-02T03:00:00Z",
            energyKwh = 2.0,
            coverageRatio = 0.5
        )
        assertEquals(
            1.5,
            annualStandbyKwh(2026, listOf(qualified, insufficient))!!,
            0.0
        )
        assertNull(annualStandbyKwh(2025, listOf(qualified)))
    }

    @Test
    fun legacyStandbyRequiresObservedCapacityAndNoDrive() {
        val previous = ChargeData(
            1,
            startDate = "2026-01-01T00:00:00Z",
            endDate = "2026-01-01T01:00:00Z",
            batteryDetails = ChargeBatteryDetails(endBatteryLevel = 80)
        )
        val next = ChargeData(
            2,
            startDate = "2026-01-02T00:00:00Z",
            endDate = "2026-01-02T01:00:00Z",
            batteryDetails = ChargeBatteryDetails(startBatteryLevel = 70)
        )
        assertNull(
            annualStandbyKwh(
                2026,
                listOf(previous, next),
                emptyList()
            )
        )
        assertEquals(
            7.5,
            annualStandbyKwh(
                2026,
                listOf(previous, next),
                emptyList(),
                75.0
            )!!,
            0.0
        )
        val drive = DriveData(
            9,
            startDate = "2026-01-01T12:00:00Z",
            endDate = "2026-01-01T13:00:00Z"
        )
        assertNull(
            annualStandbyKwh(
                2026,
                listOf(previous, next),
                listOf(drive),
                75.0
            )
        )
    }
}

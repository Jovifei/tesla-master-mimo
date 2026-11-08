package com.matelink.ui.screens.cost

import com.matelink.domain.analytics.calculateCostTotals

import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.ChargeData
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CostBreakdownTest {
    @Test
    fun monthlyBreakdownOmitsMissingPricesButKeepsObservedFreeCharge() {
        val charges = listOf(
            ChargeData(chargeId = 1, startDate = "2026-01-02T10:00:00", cost = null),
            ChargeData(chargeId = 2, startDate = "2026-02-02T10:00:00", cost = 0.0),
            ChargeData(chargeId = 3, startDate = "2026-03-02T10:00:00", cost = 12.5)
        )

        val result = buildMonthlyCosts(charges, emptySet()) { it.cost }

        assertEquals(listOf("2026-02", "2026-03"), result.map { it.month })
        assertEquals(0.0, result.first().acCost, 0.0001)
        assertEquals(12.5, result.last().unknownCost, 0.0001)
    }

    @Test
    fun locationBreakdownOmitsLocationsWithNoPriceSource() {
        val charges = listOf(
            ChargeData(chargeId = 1, address = "Unknown station", cost = null),
            ChargeData(chargeId = 2, address = "Free station", cost = 0.0),
            ChargeData(chargeId = 3, address = "Free station", cost = 0.0),
            ChargeData(chargeId = 4, address = "Paid station", cost = 8.0)
        )

        val result = buildLocationCosts(charges) { it.cost }

        assertTrue(result.none { it.address == "Unknown station" })
        assertEquals(2, result.first { it.address == "Free station" }.count)
        assertEquals(0.0, result.first { it.address == "Free station" }.totalCost, 0.0001)
    }

    @Test fun unclassifiedChargeIsNotCountedAsAc() {
        val charge = ChargeData(1, startDate = "2026-10-20T10:00:00Z", cost = 8.0)
        val result = buildMonthlyCosts(listOf(charge), emptySet()) { it.cost }
        assertEquals(0.0, result.single().acCost, 0.0)
        assertEquals(0.0, result.single().dcCost, 0.0)
    }

    @Test fun monthUsesVehicleOwnersLocalCalendarDate() {
        val charge = ChargeData(1, startDate = "2026-09-30T17:00:00Z", cost = 8.0)
        val result = buildMonthlyCosts(listOf(charge), emptySet(),
            zoneId = ZoneId.of("Asia/Shanghai")) { it.cost }
        assertEquals("2026-10", result.single().month)
    }

    @Test fun unitCostUsesOnlySameIdKnownPriceAndPositiveQualifiedBatteryEnergy() {
        val rows = listOf(
            ChargeData(1, source = "teslamate_archive", cost = 10.0),
            ChargeData(2, source = "teslamate_archive", chargeEnergyAdded = 2.0),
            ChargeData(3, source = "teslamate_archive", cost = 0.0, chargeEnergyAdded = 1.0),
            ChargeData(4, source = "teslamate_archive", chargeEnergyAdded = 1.0)
        )
        val totals = calculateCostTotals(rows, { if (it.chargeId == 4) 5.0 else it.cost }, 1.14)
        assertEquals(15.0, totals.knownTotal!!, 0.001)
        assertEquals(4.0, totals.qualifiedBatteryTotal!!, 0.001)
        assertEquals(2.28, totals.estimatedTotal!!, 0.001)
        assertEquals(2.5, totals.pairedUnitCost!!, 0.001)
        assertEquals(3, totals.pricedCount)
        assertEquals(3, totals.energyCount)
        assertEquals(2, totals.pairedCount)
        assertEquals(1, totals.estimatedCount)
    }

    @Test fun unknownEnergyNeverPairsWithKnownPriceOrDefaultRate() {
        val row = ChargeData(1, source = "telemetry_mqtt", cost = 8.0,
            chargeEnergyAdded = 99.0, energyContract = EnergyContract())
        val totals = calculateCostTotals(listOf(row), { it.cost }, 1.14)
        assertEquals(8.0, totals.knownTotal!!, 0.001)
        assertEquals(0, totals.energyCount)
        assertEquals(0, totals.pairedCount)
        assertNull(totals.pairedUnitCost)
        assertNull(totals.estimatedTotal)
    }

    @Test fun monthlyChartKeepsAcDcAndUnknownSeparate() {
        val ac = ChargeData(1, startDate = "2026-10-20T10:00:00Z", cost = 2.0,
            chargeType = "ac", energyContract = EnergyContract(
                chargeMode = "ac", chargeModeEvidence = "observed_boundary_modes_no_conflict"))
        val dc = ChargeData(2, startDate = "2026-10-20T10:00:00Z", cost = 3.0)
        val unknown = ChargeData(3, startDate = "2026-10-20T10:00:00Z", cost = 4.0)
        val result = buildMonthlyCosts(listOf(ac, dc, unknown), setOf(2)) { it.cost }.single()
        assertEquals(2.0, result.acCost, 0.001)
        assertEquals(3.0, result.dcCost, 0.001)
        assertEquals(4.0, result.unknownCost, 0.001)
    }
}

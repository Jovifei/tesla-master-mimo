package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.local.AppSettings
import org.junit.Assert.*
import org.junit.Test

class PartialCostTotalsTest {
    private val start = "2026-01-01T00:00:00Z"
    private val end = "2026-01-01T01:00:00Z"
    private fun partial(energy: Double = 10.0) = ChargeData(7,
        startDate = start, endDate = end, source = "telemetry_mqtt",
        qualityState = "incomplete", qualityReason = "partial_or_missing_charge_counter",
        energyContract = EnergyContract(batteryInput = EnergyMetric(
            method = "session_counter_delta", measurementPoint = "battery_input",
            source = "telemetry_mqtt", sourceField = "DCChargingEnergyIn",
            quality = "unknown", reason = "partial_counter_window", startDate = start, endDate = end,
            observedStartAt = "2026-01-01T00:05:00Z", observedEndAt = "2026-01-01T00:55:00Z",
            timeBasis = "collector_received_at", coverageKind = "endpoints",
            coverageRatio = 5.0 / 6.0, coveredEnergyKwh = energy)))

    @Test fun sameTariffConsumerKeepsPartialEstimateOutsideBillsAndFullEnergy() {
        val row = partial()
        val settings = AppSettings(tariffEnabled = false, defaultChargePrice = 1.5)
        val projected = projectEffectiveChargeCost(row, -1, null, "CNY",
            tariffEstimate = estimateChargeTariff(row, settings))
        val totals = calculateCostTotals(listOf(row), { null }, 1.5) {
            projectEffectiveChargeCost(it, -1, null, "CNY",
                tariffEstimate = estimateChargeTariff(it, settings)).cost
        }
        assertEquals(15.0, projected.cost!!, 0.0)
        assertEquals(projected.cost!!, totals.partialEstimatedTotal!!, 0.0)
        assertEquals(1, totals.partialEstimatedCount)
        assertNull(totals.estimatedTotal)
        assertNull(totals.knownTotal)
        assertNull(totals.qualifiedBatteryTotal)
        assertNull(totals.pairedUnitCost)
        assertEquals(0, totals.energyCount)
    }

    @Test fun wholePartialAndConfirmedValuesHaveDisjointTotals() {
        val full = ChargeData(8, source = "teslamate_archive", chargeEnergyAdded = 2.0)
        val paid = full.copy(chargeId = 9, cost = 4.0)
        val totals = calculateCostTotals(listOf(partial(), full, paid), { it.cost }, 1.5)
        assertEquals(4.0, totals.knownTotal!!, 0.0)
        assertEquals(3.0, totals.estimatedTotal!!, 0.0)
        assertEquals(15.0, totals.partialEstimatedTotal!!, 0.0)
        assertEquals(4.0, totals.qualifiedBatteryTotal!!, 0.0)
        assertEquals(2.0, totals.pairedUnitCost!!, 0.0)
    }

    @Test fun explicitZeroBillWinsAndValidZeroPartialIsNotMissing() {
        val billed = calculateCostTotals(listOf(partial()), { 0.0 }, 1.5)
        assertEquals(0.0, billed.knownTotal!!, 0.0)
        assertNull(billed.partialEstimatedTotal)
        val zero = calculateCostTotals(listOf(partial(0.0)), { null }, 1.5)
        assertEquals(0.0, zero.partialEstimatedTotal!!, 0.0)
        assertEquals(1, zero.partialEstimatedCount)
    }

    @Test fun unknownOrConflictingEnergyCannotBeQualifiedByAnEstimateCallback() {
        val bad = partial().copy(sourceId = "a", sourceRecordId = "b")
        var called = false
        val totals = calculateCostTotals(listOf(bad, ChargeData(99)), { null }, 1.5) {
            called = true; 999.0
        }
        assertFalse(called)
        assertNull(totals.partialEstimatedTotal)
        assertNull(totals.estimatedTotal)
        assertNull(calculateCostTotals(listOf(partial()), { null }, 1.5) { Double.NaN }.partialEstimatedTotal)
    }
}

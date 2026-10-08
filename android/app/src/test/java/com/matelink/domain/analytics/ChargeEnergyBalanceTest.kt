package com.matelink.domain.analytics

import com.matelink.data.api.models.*
import org.junit.Assert.*
import org.junit.Test

class ChargeEnergyBalanceTest {
    private val start = "2026-10-08T01:00:00Z"
    private val end = "2026-10-08T02:00:00Z"
    private fun metric(value: Double, point: String) = EnergyMetric(valueKwh = value, method = "session_counter_delta",
        measurementPoint = point, source = "telemetry_mqtt", quality = "reported", startDate = start, endDate = end,
        observedStartAt = start, observedEndAt = end, timeBasis = "receiver_observation", coverageKind = "endpoints", coverageRatio = 1.0)
    private fun contract(input: Double = 10.0, battery: Double = 9.0) = EnergyContract(
        acInput = metric(input, "ac_charger_input"), batteryInput = metric(battery, "battery_input"))
    @Test fun completeCompatibleAcCountersProduceLossAndEfficiency() {
        val result = qualifiedAcEnergyBalance(contract(), start, end, "ac")!!
        assertEquals(1.0, result.lossKwh, 0.0)
        assertEquals(90.0, result.efficiencyPercent, 0.0)
    }
    @Test fun zeroLossAndZeroBatteryInputAreValidButZeroDenominatorIsUnknown() {
        assertEquals(0.0, qualifiedAcEnergyBalance(contract(10.0, 10.0), start, end, "ac")!!.lossKwh, 0.0)
        assertEquals(0.0, qualifiedAcEnergyBalance(contract(10.0, 0.0), start, end, "ac")!!.efficiencyPercent, 0.0)
        assertNull(qualifiedAcEnergyBalance(contract(0.0, 0.0), start, end, "ac"))
    }
    @Test fun dcUnknownTypeAndNegativeLossNeverBecomeAcEfficiency() {
        listOf("dc", "unknown", null).forEach { type -> assertNull(qualifiedAcEnergyBalance(contract(), start, end, type)) }
        assertNull(qualifiedAcEnergyBalance(contract(9.0, 10.0), start, end, "ac"))
    }
    @Test fun sourceWindowCoverageAndMeasurementPointMustMatch() {
        val original = contract()
        val battery = original.batteryInput!!
        listOf(battery.copy(source = "archive"), battery.copy(coverageRatio = 0.99),
            battery.copy(observedEndAt = "2026-10-08T01:59:59Z"), battery.copy(method = "energy_remaining_delta"),
            battery.copy(valueKwh = Double.NaN)).forEach { bad ->
            assertNull(qualifiedAcEnergyBalance(original.copy(batteryInput = bad), start, end, "ac"))
        }
    }
    @Test fun explicitUnknownEnergyCannotFallBackToLegacyChargeScalars() {
        val raw = ChargeData(1, startDate = start, endDate = end, chargeEnergyAdded = 10.0,
            chargeEnergyUsed = 11.0, energyContract = EnergyContract())
        assertNull(raw.withQualifiedEnergy().chargeEnergyAdded)
        assertNull(raw.withQualifiedEnergy().chargeEnergyUsed)
    }
}

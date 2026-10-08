package com.matelink.ui.screens.charges

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests the same API projection consumed by list, detail and tariff estimates. */
class ChargeQualifiedEnergyFlowTest {
    private val start = "2026-10-08T00:00:00Z"
    private val end = "2026-10-08T00:30:00Z"

    @Test fun explicitlyUnknownBatteryInputDoesNotResurrectHistoricalScalar() {
        val unknown = EnergyContract(batteryInput = EnergyMetric(
            valueKwh = null, method = "session_counter_delta",
            measurementPoint = "battery_input", source = "telemetry_mqtt",
            quality = "unknown", reason = "partial_counter_window"
        ))
        val list = ChargeData(chargeId = 7, startDate = start, endDate = end,
            chargeEnergyAdded = 9.0, energyContract = unknown)
        val detail = ChargeDetail(chargeId = 7, startDate = start, endDate = end,
            chargeEnergyAdded = 9.0, energyContract = unknown)
        assertNull(list.batteryInputKwh)
        assertNull(detail.batteryInputKwh)
        assertNull(ChargeStatsCalculator.calculateStats(detail).energyAdded)
        assertEquals(ChargeDetailCostState.UNAVAILABLE,
            presentChargeDetailCost(energyKwh = detail.batteryInputKwh,
                defaultPricePerKwh = 1.5).state)
    }

    @Test fun observedZeroRemainsZeroInListAndDetail() {
        val measured = EnergyContract(batteryInput = EnergyMetric(
            valueKwh = 0.0, method = "session_counter_delta",
            measurementPoint = "battery_input", source = "telemetry_mqtt",
            quality = "reported", startDate = start, endDate = end,
            coverageKind = "endpoints", coverageRatio = 1.0
        ))
        val list = ChargeData(chargeId = 8, startDate = start, endDate = end,
            energyContract = measured)
        val detail = ChargeDetail(chargeId = 8, startDate = start, endDate = end,
            energyContract = measured)
        assertEquals(0.0, list.batteryInputKwh!!, 0.0)
        assertEquals(0.0, detail.batteryInputKwh!!, 0.0)
        assertEquals(0.0, ChargeStatsCalculator.calculateStats(detail).energyAdded!!, 0.0)
    }

    @Test fun acInputCannotMasqueradeAsBatteryInput() {
        val acCounter = EnergyContract(batteryInput = EnergyMetric(
            valueKwh = 4.0, method = "session_counter_delta",
            measurementPoint = "ac_charger_input", source = "telemetry_mqtt",
            quality = "reported", startDate = start, endDate = end,
            coverageKind = "endpoints", coverageRatio = 1.0
        ))
        assertNull(ChargeData(chargeId = 9, startDate = start, endDate = end,
            chargeEnergyAdded = 4.0, energyContract = acCounter).batteryInputKwh)
    }
}

package com.matelink.domain.analytics

import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.api.models.netValueForWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnergyPhysicalPurposeRegressionTest {
    @Test fun chargingInputCannotBeUsedAsDrivingNetEnergy() {
        val start = "2026-10-08T00:00:00Z"
        val end = "2026-10-08T00:10:00Z"
        val chargingInput = EnergyMetric(
            valueKwh = 1.0, method = "session_counter_delta",
            measurementPoint = "ac_charger_input", source = "fleet_telemetry",
            sourceField = "ACChargingEnergyIn",
            quality = "reported", startDate = start, endDate = end,
            observedStartAt = start, observedEndAt = end, timeBasis = "source_sample_time",
            coverageKind = "endpoints", coverageRatio = 1.0
        )
        assertEquals(1.0, chargingInput.valueForWindow(start, end)!!, 0.000001)
        assertNull(EnergyContract(netEnergy = chargingInput).netValueForWindow(start, end))
    }
}

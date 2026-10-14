package com.matelink.ui.components

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TelemetryMetricFormatTest {
    @Test fun canonicalEfficiencyIsPerHundredKilometres() {
        withLocale { assertEquals("16.4 kWh/100km", formatTelemetryEfficiency(164.0)) }
    }
    @Test fun zeroNegativeAndTinyNonzeroRemainDistinct() {
        withLocale {
            assertEquals("0.0", formatTelemetryNumber(0.0))
            assertEquals("-0.04", formatTelemetryNumber(-0.04))
            assertNotEquals("0.0", formatTelemetryNumber(0.0004))
            assertNotEquals("-0.0", formatTelemetryNumber(-1e-8))
            assertEquals("—", formatTelemetryNumber(Double.NaN))
        }
    }
    @Test fun fullUnitsAreSeparateWithoutSplittingCompoundTimeOrUnknown() {
        assertEquals("16.4" to "kWh/100km", telemetryMetricParts("16.4 kWh/100km"))
        assertEquals("-1.0e-09" to "kWh/100km", telemetryMetricParts("-1.0e-09 kWh/100km"))
        assertEquals("158" to "km/h", telemetryMetricParts("158 km/h"))
        assertEquals("2d 23h" to null, telemetryMetricParts("2d 23h"))
        assertEquals("暂无数据" to null, telemetryMetricParts("暂无数据"))
    }
    private fun withLocale(action: () -> Unit) {
        val previous = Locale.getDefault()
        try { Locale.setDefault(Locale.US); action() } finally { Locale.setDefault(previous) }
    }
}

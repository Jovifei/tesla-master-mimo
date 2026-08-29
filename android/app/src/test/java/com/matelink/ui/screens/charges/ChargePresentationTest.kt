package com.matelink.ui.screens.charges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargePresentationTest {
    @Test
    fun chargePhaseMappingDoesNotGuessUnknownValues() {
        assertEquals(ChargePhase.DC, chargePhaseFor(0))
        assertEquals(ChargePhase.SINGLE_PHASE_AC, chargePhaseFor(1))
        assertEquals(ChargePhase.THREE_PHASE_AC, chargePhaseFor(2))
        assertEquals(ChargePhase.THREE_PHASE_AC, chargePhaseFor(3))
        assertNull(chargePhaseFor(null))
        assertNull(chargePhaseFor(4))
    }

    @Test
    fun chargeMetricFormattingKeepsObservedZeroAndDecimal() {
        assertEquals("0.0 A", formatChargeMetric(0.0, "A"))
        assertEquals("48.9 kW", formatChargeMetric(48.9, "kW"))
        assertNull(formatChargeMetric(null, "A"))
    }

    @Test
    fun scheduledChargingTimeAcceptsOnlyParseableIsoValues() {
        assertTrue(isValidScheduledChargingTime("2026-08-26T12:00:00Z"))
        assertFalse(isValidScheduledChargingTime("not-a-time"))
        assertFalse(isValidScheduledChargingTime(null))
    }
}

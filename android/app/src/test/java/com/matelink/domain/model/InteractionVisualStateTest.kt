package com.matelink.domain.model

import org.junit.Assert.*
import org.junit.Test

class InteractionVisualStateTest {
    private val now = 1_800_000_000_000L
    @Test fun connectionNeverBecomesConfirmedFromAssetLoadingOrTime() {
        repeat(20) { assertEquals(ConnectionVisualPhase.IDLE, connectionVisualPhase(false, false, false)) }
        assertEquals(ConnectionVisualPhase.CONNECTING, connectionVisualPhase(false, true, false))
        assertEquals(ConnectionVisualPhase.ACTION_REQUIRED, connectionVisualPhase(true, false, false, true))
        assertEquals(ConnectionVisualPhase.UNAVAILABLE, connectionVisualPhase(true, false, true))
        assertEquals(ConnectionVisualPhase.CONNECTED, connectionVisualPhase(true, false, false))
    }
    @Test fun zeroIsAReadingNotMissingAndNoPressureThresholdIsInvented() {
        val zero = visualWheelReading(0.0, now, now, true)
        assertEquals(0.0, zero.pressureBar!!, 0.0)
        assertEquals(VisualWheelState.RECORDED, zero.state)
        assertEquals(VisualWheelState.RECORDED, visualWheelReading(10.0, now, now, true).state)
        assertEquals(VisualWheelState.WARNING, visualWheelReading(2.1, now, now, true, true).state)
    }
    @Test fun invalidProvenanceAndFutureObservationsStayUnknown() {
        assertEquals(VisualWheelState.UNKNOWN, visualWheelReading(2.4, now, now, false).state)
        assertEquals(VisualWheelState.UNKNOWN, visualWheelReading(2.4, now + 1, now, true).state)
        assertEquals(VisualWheelState.UNKNOWN, visualWheelReading(2.4, null, now, true).state)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 16.0))
            assertNull(visualWheelReading(value, now, now, true).pressureBar)
    }
    @Test fun staleValuesDoNotAcquireALiveWarningOrNewTime() {
        val old = now - VISUAL_TPMS_MAX_AGE_MS - 1
        val result = visualWheelReading(2.4, old, now, true, true)
        assertEquals(VisualWheelState.STALE, result.state)
        assertEquals(old, result.observedAt)
        assertEquals(VisualWheelState.RECORDED, visualWheelReading(2.4, now - VISUAL_TPMS_MAX_AGE_MS, now, true).state)
    }
    @Test fun endpointAbsenceCannotBecomeAnActualRoute() {
        assertFalse(hasVisualRouteEndpoints(null, "B"))
        assertFalse(hasVisualRouteEndpoints("A", " "))
        assertTrue(hasVisualRouteEndpoints("A", "B"))
    }
    @Test fun animationStopsOffscreenOrUnderReducedMotion() {
        assertTrue(visualMotionAllowed(true, true, true))
        assertFalse(visualMotionAllowed(false, true, true))
        assertFalse(visualMotionAllowed(true, false, true))
        assertFalse(visualMotionAllowed(true, true, false))
    }
}

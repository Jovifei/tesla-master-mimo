package com.matelink.ui.components

import com.matelink.ui.theme.AppearancePalette
import org.junit.Assert.*
import org.junit.Test

class UiProductPolicyTest {
    @Test fun neutralIsTheDefaultIncludingUnknownStoredValues() {
        for (value in listOf(null, "", "blue", "NEUTRAL")) assertEquals(AppearancePalette.NEUTRAL, AppearancePalette.fromStorage(value))
        assertEquals(AppearancePalette.CREAM, AppearancePalette.fromStorage("cream"))
        assertEquals(AppearancePalette.NEUTRAL, AppearancePalette.fromStorage("neutral"))
    }
    @Test fun verticalDirectionsAndBoundariesDoNotWrap() {
        assertEquals(1, dayStackTarget(0, 3, -60f, 48f))
        assertEquals(0, dayStackTarget(1, 3, 60f, 48f))
        assertEquals(0, dayStackTarget(0, 3, 60f, 48f))
        assertEquals(2, dayStackTarget(2, 3, -60f, 48f))
        assertEquals(1, dayStackTarget(1, 3, -20f, 48f))
        assertEquals(0, dayStackTarget(0, 0, -60f, 48f))
        assertEquals(1, dayStackTarget(1, 3, Float.NaN, 48f))
    }
    @Test fun chargeCellsPreserveZeroAndUnknown() {
        assertEquals(0f, chargeActivityIntensity(null, 10.0), 0f)
        assertEquals(0f, chargeActivityIntensity(0.0, 10.0), 0f)
        assertEquals(0f, chargeActivityIntensity(Double.NaN, 10.0), 0f)
        assertEquals(0f, chargeActivityIntensity(-1.0, 10.0), 0f)
        assertTrue(chargeActivityIntensity(5.0, 10.0) > 0)
        assertTrue(chargeActivityIntensity(10.0, 10.0) > chargeActivityIntensity(5.0, 10.0))
        assertEquals(0f, chargeActivityIntensity(0.0, 0.0), 0f)
    }
}

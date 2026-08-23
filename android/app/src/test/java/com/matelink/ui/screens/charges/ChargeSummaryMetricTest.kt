package com.matelink.ui.screens.charges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeSummaryMetricTest {
    @Test
    fun missingNonFiniteAndNegativeEnergyAreUnavailable() {
        assertNull(observedChargeEnergy(null))
        assertNull(observedChargeEnergy(Double.NaN))
        assertNull(observedChargeEnergy(-0.1))
    }

    @Test
    fun observedZeroEnergyRemainsAvailable() {
        assertEquals(0.0, observedChargeEnergy(0.0) ?: Double.NaN, 0.0)
    }
}

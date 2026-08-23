package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyEnergyTest {
    @Test
    fun observedCapacityProducesEnergyAndPower() {
        val result = estimateStandbyEnergy(10, 60.0, 2.0)!!

        assertEquals(6.0, result.energyKwh, 0.0001)
        assertEquals(3000.0, result.averagePowerW, 0.0001)
    }

    @Test
    fun missingCapacityDoesNotUseAHiddenDefault() {
        assertNull(estimateStandbyEnergy(10, null, 2.0))
        assertNull(estimateStandbyEnergy(10, 75.0, 0.0))
    }

    @Test
    fun standbyWindowRequiresAtLeastTwoHours() {
        assertFalse(isQualifiedStandbyWindow(1.99))
        assertTrue(isQualifiedStandbyWindow(2.0))
        assertTrue(isQualifiedStandbyWindow(8.0))
        assertFalse(isQualifiedStandbyWindow(Double.NaN))
        assertFalse(isQualifiedStandbyWindow(Double.POSITIVE_INFINITY))
    }
}

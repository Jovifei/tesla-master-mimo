package com.matelink.ui.screens.charges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeEnergyPresentationTest {

    @Test
    fun positiveEnergyStaysAvailable() {
        assertEquals(12.5, presentChargeEnergy(12.5).energyKwh ?: error("energy missing"), 0.0001)
    }

    @Test
    fun trueZeroEnergyStaysAvailable() {
        assertEquals(0.0, presentChargeEnergy(0.0).energyKwh ?: error("energy missing"), 0.0001)
    }

    @Test
    fun missingOrInvalidEnergyIsUnavailable() {
        listOf<Double?>(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0).forEach { energyKwh ->
            assertNull(presentChargeEnergy(energyKwh).energyKwh)
        }
    }

    @Test
    fun presentationIsDeterministic() {
        assertEquals(presentChargeEnergy(12.5), presentChargeEnergy(12.5))
    }
}

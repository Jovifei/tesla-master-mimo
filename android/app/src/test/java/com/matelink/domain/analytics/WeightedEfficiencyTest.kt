package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeightedEfficiencyTest {
    @Test
    fun longDriveCarriesMoreWeightThanShortDrive() {
        val result = calculateWeightedEfficiency(
            listOf(
                EfficiencySample(distanceKm = 1.0, energyKwh = 0.30),
                EfficiencySample(distanceKm = 99.0, energyKwh = 14.85)
            )
        )

        assertEquals(151.5, result.efficiencyWhKm!!, 0.0001)
        assertEquals(2, result.sampleCount)
        assertEquals(100.0, result.coveragePercent, 0.0)
    }

    @Test
    fun invalidAndShortSamplesAreNotTurnedIntoZero() {
        val result = calculateWeightedEfficiency(
            listOf(
                EfficiencySample(distanceKm = 0.5, energyKwh = 0.2),
                EfficiencySample(distanceKm = 10.0, energyKwh = null),
                EfficiencySample(distanceKm = Double.NaN, energyKwh = 1.0)
            )
        )

        assertNull(result.efficiencyWhKm)
        assertEquals(0, result.sampleCount)
        assertEquals(0.0, result.coveragePercent, 0.0)
    }
}

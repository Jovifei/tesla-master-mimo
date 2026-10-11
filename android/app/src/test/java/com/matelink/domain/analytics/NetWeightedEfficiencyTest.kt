package com.matelink.domain.analytics

import org.junit.Assert.*
import org.junit.Test

class NetWeightedEfficiencyTest {
    @Test fun totalsUseTheSameSubsetIncludingZeroAndRecovery() {
        val result = calculateWeightedEfficiency(listOf(EfficiencySample(10.0, 2.0),
            EfficiencySample(5.0, -0.5), EfficiencySample(5.0, 0.0), EfficiencySample(80.0, null)))
        assertEquals(75.0, result.efficiencyWhKm!!, 1e-12)
        assertEquals(20.0, result.validDistanceKm, 1e-12)
        assertEquals(1.5, result.validEnergyKwh, 1e-12)
        assertEquals(75.0, result.coveragePercent, 1e-12)
        assertEquals(20.0, result.distanceCoveragePercent!!, 1e-12)
    }
    @Test fun shortPositiveDistanceIsNotSilentlyDiscarded() {
        assertEquals(100.0, calculateWeightedEfficiency(listOf(EfficiencySample(0.5, 0.05))).efficiencyWhKm!!, 1e-12)
    }
    @Test fun notTheArithmeticMeanOfRatios() {
        val result = calculateWeightedEfficiency(listOf(EfficiencySample(1.0, 1.0), EfficiencySample(9.0, 0.9)))
        assertEquals(190.0, result.efficiencyWhKm!!, 1e-12)
    }
    @Test fun invalidPairsDoNotContributeEitherNumeratorOrDenominator() {
        val result = calculateWeightedEfficiency(listOf(EfficiencySample(0.0, 2.0), EfficiencySample(null, 1.0),
            EfficiencySample(10.0, Double.NaN), EfficiencySample(1.0, -0.2)))
        assertEquals(-200.0, result.efficiencyWhKm!!, 1e-12)
        assertEquals(1, result.sampleCount)
    }
}

package com.matelink.ui.screens.efficiency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EfficiencyBarScaleTest {
    @Test fun mixedRecoveryAndConsumptionStraddleTheZeroBaseline() {
        val scale = signedEfficiencyScale(listOf(-60.0, 0.0, 180.0))

        assertTrue(scale.yFraction(180.0) < scale.yFraction(0.0))
        assertTrue(scale.yFraction(0.0) < scale.yFraction(-60.0))
        assertTrue(scale.yFraction(0.0) > 0.0)
        assertTrue(scale.yFraction(0.0) < 1.0)
    }

    @Test fun allNegativeBinsGrowDownwardFromTheTopZeroLine() {
        val scale = signedEfficiencyScale(listOf(-40.0, -100.0))

        assertEquals(0.0, scale.yFraction(0.0), 0.0001)
        assertTrue(scale.yFraction(-100.0) > scale.yFraction(-40.0))
        assertTrue(scale.yFraction(-100.0) <= 1.0)
    }

    @Test fun allPositiveBinsRiseFromTheBottomZeroLine() {
        val scale = signedEfficiencyScale(listOf(40.0, 100.0))

        assertEquals(1.0, scale.yFraction(0.0), 0.0001)
        assertTrue(scale.yFraction(100.0) < scale.yFraction(40.0))
        assertTrue(scale.yFraction(100.0) >= 0.0)
    }

    @Test fun allZeroBinsRemainFiniteAndExactlyOnBaseline() {
        val scale = signedEfficiencyScale(listOf(0.0, 0.0))

        assertEquals(0.5, scale.yFraction(0.0), 0.0001)
        assertTrue(scale.yFraction(0.0).isFinite())
    }
}

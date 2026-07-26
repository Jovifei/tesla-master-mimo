package com.matelink.ui.screens.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryRangeMetricsTest {

    @Test
    fun lossIsZeroWhenCurrentRangeExceedsHistoricalMaximum() {
        val metrics = ranges(maxRangeKm = 423.2, currentRangeKm = 424.0)

        assertEquals(423.2, metrics.maxRangeKm ?: error("max range missing"), 0.0001)
        assertEquals(424.0, metrics.currentRangeKm ?: error("current range missing"), 0.0001)
        assertEquals(0.0, metrics.rangeLossKm ?: error("range loss missing"), 0.0001)
    }

    @Test
    fun lossIsThePositiveDifferenceWhenCurrentRangeDrops() {
        val metrics = ranges(maxRangeKm = 423.2, currentRangeKm = 414.7)

        assertEquals(423.2, metrics.maxRangeKm ?: error("max range missing"), 0.0001)
        assertEquals(414.7, metrics.currentRangeKm ?: error("current range missing"), 0.0001)
        assertEquals(8.5, metrics.rangeLossKm ?: error("range loss missing"), 0.0001)
    }

    @Test
    fun equalRangesArePreservedWithZeroLoss() {
        val metrics = ranges(maxRangeKm = 400.0, currentRangeKm = 400.0)

        assertEquals(400.0, metrics.maxRangeKm ?: error("max range missing"), 0.0001)
        assertEquals(400.0, metrics.currentRangeKm ?: error("current range missing"), 0.0001)
        assertEquals(0.0, metrics.rangeLossKm ?: error("range loss missing"), 0.0001)
    }

    @Test
    fun missingCurrentRangeKeepsMaxRangeAndHidesLoss() {
        val metrics = ranges(maxRangeKm = 400.0, currentRangeKm = null)

        assertEquals(400.0, metrics.maxRangeKm ?: error("max range missing"), 0.0001)
        assertNull(metrics.currentRangeKm)
        assertNull(metrics.rangeLossKm)
    }

    @Test
    fun missingMaxRangeKeepsCurrentRangeAndHidesLoss() {
        val metrics = ranges(maxRangeKm = null, currentRangeKm = 390.0)

        assertNull(metrics.maxRangeKm)
        assertEquals(390.0, metrics.currentRangeKm ?: error("current range missing"), 0.0001)
        assertNull(metrics.rangeLossKm)
    }

    @Test
    fun missingRangesStayUnavailable() {
        val metrics = ranges(maxRangeKm = null, currentRangeKm = null)

        assertNull(metrics.maxRangeKm)
        assertNull(metrics.currentRangeKm)
        assertNull(metrics.rangeLossKm)
    }

    @Test
    fun nonFiniteCurrentRangesStayUnavailable() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { currentRangeKm ->
            val metrics = ranges(maxRangeKm = 400.0, currentRangeKm = currentRangeKm)

            assertEquals(400.0, metrics.maxRangeKm ?: error("max range missing"), 0.0001)
            assertNull(metrics.currentRangeKm)
            assertNull(metrics.rangeLossKm)
        }
    }

    @Test
    fun nonFiniteMaxRangesStayUnavailable() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { maxRangeKm ->
            val metrics = ranges(maxRangeKm = maxRangeKm, currentRangeKm = 390.0)

            assertNull(metrics.maxRangeKm)
            assertEquals(390.0, metrics.currentRangeKm ?: error("current range missing"), 0.0001)
            assertNull(metrics.rangeLossKm)
        }
    }

    @Test
    fun negativeCurrentRangeStaysUnavailable() {
        val metrics = ranges(maxRangeKm = 400.0, currentRangeKm = -1.0)

        assertEquals(400.0, metrics.maxRangeKm ?: error("max range missing"), 0.0001)
        assertNull(metrics.currentRangeKm)
        assertNull(metrics.rangeLossKm)
    }

    @Test
    fun negativeMaxRangeStaysUnavailable() {
        val metrics = ranges(maxRangeKm = -1.0, currentRangeKm = 390.0)

        assertNull(metrics.maxRangeKm)
        assertEquals(390.0, metrics.currentRangeKm ?: error("current range missing"), 0.0001)
        assertNull(metrics.rangeLossKm)
    }

    @Test
    fun trueZeroCurrentRangeIsPreservedAndCanProduceLoss() {
        val metrics = ranges(maxRangeKm = 400.0, currentRangeKm = 0.0)

        assertEquals(400.0, metrics.maxRangeKm ?: error("max range missing"), 0.0001)
        assertEquals(0.0, metrics.currentRangeKm ?: error("current range missing"), 0.0001)
        assertEquals(400.0, metrics.rangeLossKm ?: error("range loss missing"), 0.0001)
    }

    @Test
    fun trueZeroRangesArePreservedWithZeroLoss() {
        val metrics = ranges(maxRangeKm = 0.0, currentRangeKm = 0.0)

        assertEquals(0.0, metrics.maxRangeKm ?: error("max range missing"), 0.0001)
        assertEquals(0.0, metrics.currentRangeKm ?: error("current range missing"), 0.0001)
        assertEquals(0.0, metrics.rangeLossKm ?: error("range loss missing"), 0.0001)
    }

    @Test
    fun calculationIsDeterministic() {
        assertEquals(
            ranges(maxRangeKm = 423.2, currentRangeKm = 414.7),
            ranges(maxRangeKm = 423.2, currentRangeKm = 414.7)
        )
    }

    private fun ranges(maxRangeKm: Double?, currentRangeKm: Double?) =
        BatteryRangeMetrics.from(maxRangeKm = maxRangeKm, currentRangeKm = currentRangeKm)
}

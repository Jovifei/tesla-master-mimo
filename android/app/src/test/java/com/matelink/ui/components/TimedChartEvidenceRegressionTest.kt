package com.matelink.ui.components

import org.junit.Assert.*
import org.junit.Test

class TimedChartEvidenceRegressionTest {
    @Test fun missingTimestampBetweenObservedSamplesMustBreakTheLine() {
        val series = projectTimedChartSeries(listOf(TimedChartSample(0, 10.0, 0),
            TimedChartSample(null, 20.0, 1), TimedChartSample(20_000, 30.0, 2)))
        assertEquals(listOf(0, 2), series.sourceIndices)
        assertEquals(setOf(1), series.gaps)
    }

    @Test fun outOfOrderValidTimesAreSortedWithoutInventingAnIndexGap() {
        val series = projectTimedChartSeries(listOf(TimedChartSample(20_000, 30.0, 0),
            TimedChartSample(0, 10.0, 1), TimedChartSample(10_000, 20.0, 2)))
        assertEquals(listOf(1, 2, 0), series.sourceIndices)
        assertEquals(listOf(0f, .5f, 1f), series.xFractions)
        assertTrue(series.gaps.isEmpty())
    }

    @Test fun missingValueLongGapAndSameTimeConflictNeverBecomeZeroSamples() {
        val series = projectTimedChartSeries(listOf(TimedChartSample(0, 0.0, 0),
            TimedChartSample(10_000, null, 1), TimedChartSample(20_000, -3.0, 2),
            TimedChartSample(20_000, -4.0, 3), TimedChartSample(120_000, 5.0, 4)))
        assertEquals(0f, series.values.first(), 0f)
        assertTrue(series.values[1].isNaN())
        assertTrue(series.values[2].isNaN())
        assertEquals(setOf(3), series.gaps)
        assertEquals(2, series.validCount)
    }
}

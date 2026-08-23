package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisCoverageTest {
    @Test
    fun coverageCountsOnlyFiniteUsableInputsAndKeepsZeroCostObserved() {
        val coverage = buildAnalysisCoverage(
            drives = listOf(
                AnalysisDriveCoverageSample(100.0, 20.0, "2026-01-01T10:00:00Z"),
                AnalysisDriveCoverageSample(0.0, null, "2026-01-02T10:00:00Z"),
                AnalysisDriveCoverageSample(Double.NaN, Double.POSITIVE_INFINITY, "2026-01-03T10:00:00Z")
            ),
            charges = listOf(
                AnalysisChargeCoverageSample(20.0, 0.0, "2026-01-04"),
                AnalysisChargeCoverageSample(null, -1.0, "2026-01-05")
            )
        )

        assertEquals(3, coverage.driveRecordCount)
        assertEquals(1, coverage.driveDistanceSampleCount)
        assertEquals(1, coverage.driveEnergySampleCount)
        assertEquals(2, coverage.chargeRecordCount)
        assertEquals(1, coverage.chargeEnergySampleCount)
        assertEquals(1, coverage.chargeCostSampleCount)
        assertEquals(33.3333, coverage.distanceCoveragePercent!!, 0.0001)
        assertEquals(50.0, coverage.costCoveragePercent!!, 0.0001)
        assertEquals(4L, coverage.observationDays)
    }

    @Test
    fun noRecordsProduceUnknownCoverageInsteadOfZero() {
        val coverage = buildAnalysisCoverage(emptyList(), emptyList())

        assertNull(coverage.distanceCoveragePercent)
        assertNull(coverage.driveEnergyCoveragePercent)
        assertNull(coverage.chargeEnergyCoveragePercent)
        assertNull(coverage.costCoveragePercent)
        assertNull(coverage.observationDays)
    }

    @Test
    fun localDateTimeAndInvalidDatesAreHandledWithoutChangingCounts() {
        val coverage = buildAnalysisCoverage(
            drives = listOf(
                AnalysisDriveCoverageSample(10.0, 2.0, "2026-02-01T12:00:00"),
                AnalysisDriveCoverageSample(10.0, 2.0, "not-a-date")
            ),
            charges = emptyList()
        )

        assertEquals("2026-02-01", coverage.firstObservedDate.toString())
        assertEquals("2026-02-01", coverage.lastObservedDate.toString())
        assertEquals(2, coverage.driveDistanceSampleCount)
    }
}

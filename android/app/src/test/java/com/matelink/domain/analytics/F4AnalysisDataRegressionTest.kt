package com.matelink.domain.analytics

import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import java.time.LocalDate
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Actual model/standby producer consumers; no source-text assertions or provider data. */
class F4AnalysisDataRegressionTest {
    @Test fun localStandbyWindowsAreNewestFirstForTheRecentTenConsumer() {
        val start = Instant.parse("2026-10-01T00:00:00Z")
        val drives = (0..12).map { index ->
            DriveData(index + 1, startDate = start.plusSeconds(index * 86400L).toString(),
                endDate = start.plusSeconds(index * 86400L + 3600).toString(),
                batteryDetails = DriveBatteryDetails(80, 79))
        }
        val windows = buildLocalStandbyWindows(drives, emptyList())
        assertEquals(12, windows.size)
        assertEquals(drives[11].endDate, windows.first().startDate)
        assertEquals(drives[2].endDate, windows.take(10).last().startDate)
        assertNull(windows.first().energyKwh)
    }

    @Test fun insufficientModelKeepsAdoptedCoverageZeroUntilARealGatePasses() {
        val today = LocalDate.of(2026, 10, 27)
        val samples = List(3) { PersonalizedRangeSample(today.minusDays(it.toLong()),
            20.0, 4.0, 60.0, 20.0) }
        val result = estimatePersonalizedRange(samples, 60.0, 20.0, 60.0, today)
        assertEquals(PersonalizedRangeSource.UNAVAILABLE, result.source)
        assertEquals(0, result.sampleCount)
        assertEquals(0.0, result.distanceKm, 0.0)
        assertNull(result.rangeKm)
        assertNull(result.efficiencyWhKm)
        // Candidate coverage is separate from adopted-model evidence.
        assertEquals("candidate sample count must remain visible without inventing an adopted model", 3,
            result.candidateSampleCount)
        assertEquals(60.0, result.candidateDistanceKm, 0.0)
    }

    @Test fun candidateCoverageCannotRelaxEitherModelGate() {
        val today = LocalDate.of(2026, 10, 27)
        for ((count, distance) in listOf(9 to 300.0, 10 to 299.0)) {
            val samples = List(count) { PersonalizedRangeSample(today.minusDays(it.toLong()),
                distance / count, 4.0, null, null) }
            val result = estimatePersonalizedRange(samples, 60.0, null, null, today)
            assertEquals(PersonalizedRangeSource.UNAVAILABLE, result.source)
            assertEquals(count, result.candidateSampleCount)
            assertEquals(distance, result.candidateDistanceKm, 1e-9)
            assertEquals(0, result.sampleCount)
            assertEquals(0, result.confidencePercent)
            assertNull(result.rangeKm)
        }
    }
}

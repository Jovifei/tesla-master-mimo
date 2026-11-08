package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DrivePosition
import org.junit.Assert.*
import org.junit.Test

class AppReleaseDrivingRegressionTest {
    private val unknownEnergy = DriveDetailEnergyPresentation(null, null, null, null, null)

    @Test fun sparseSamplesDoNotLowerReportedJourneyExtrema() {
        val stats = calculateDriveDetailStats(DriveDetail(1, speedMax = 100, powerMax = 200, powerMin = -60,
            positions = listOf(DrivePosition(speed = 40.0, power = 20.0))), unknownEnergy)
        assertEquals(100, stats.speedMax)
        assertEquals(200, stats.powerMax)
        assertEquals(-60, stats.powerMin)
    }

    @Test fun equidistantConflictingSocCannotDependOnInputOrder() {
        val left = DrivePosition(date = "2026-10-27T00:00:00Z", batteryLevel = 68)
        val right = DrivePosition(date = "2026-10-27T00:00:20Z", batteryLevel = 69)
        for (points in listOf(listOf(left, right), listOf(right, left))) {
            val stats = calculateDriveDetailStats(DriveDetail(1, startDate = "2026-10-27T00:00:10Z",
                endDate = "2026-10-27T00:10:10Z", positions = points), unknownEnergy)
            assertNull(stats.batteryStart)
            assertNull(stats.batteryUsed)
        }
    }

    @Test fun missingElevationCannotBeBridgedIntoClimb() {
        val stats = calculateDriveDetailStats(DriveDetail(1, positions = listOf(
            DrivePosition(date = "2026-10-27T00:00:00Z", elevation = 10),
            DrivePosition(date = "2026-10-27T00:00:10Z"),
            DrivePosition(date = "2026-10-27T00:00:20Z", elevation = 100))), unknownEnergy)
        assertNull(stats.elevationGain)
        assertNull(stats.elevationLoss)
    }

    @Test fun reportedSampleMeanKeepsSampleLabel() {
        assertEquals(com.matelink.R.string.drive_detail_sample_mean, driveDetailSpeedAverageLabel(
            DriveDetail(1, speedAvg = 45.0, speedMetricsSource = "observed_route_samples")))
    }

    @Test fun sampleStatisticsMustIgnoreMissingClockAndOutOfWindowPoints() {
        val stats = calculateDriveDetailStats(DriveDetail(1,startDate="2026-10-27T00:00:00Z",endDate="2026-10-27T00:00:30Z",
            positions=listOf(DrivePosition(date="2026-10-26T23:59:00Z",speed=500.0,power=100.0,elevation=100),
                DrivePosition(speed=900.0,power=Double.MAX_VALUE,elevation=999),
                DrivePosition(date="2026-10-27T00:00:10Z",speed=40.0,power=20.0,elevation=10))),unknownEnergy)
        assertEquals(40,stats.speedMax)
        assertEquals(20.0,stats.powerAvg!!,0.0)
        assertEquals(10,stats.elevationMax)
        assertNull(stats.elevationGain)
    }

    @Test fun elevationStatisticsSortTimeAndDoNotBridgeLongSamplingGaps() {
        val gap = calculateDriveDetailStats(DriveDetail(1,positions=listOf(
            DrivePosition(date="2026-10-27T00:00:00Z",elevation=10),
            DrivePosition(date="2026-10-27T00:02:00Z",elevation=20))),unknownEnergy)
        assertNull(gap.elevationGain)
        assertNull(gap.elevationLoss)
        val ordered = calculateDriveDetailStats(DriveDetail(1,positions=listOf(
            DrivePosition(date="2026-10-27T00:00:10Z",elevation=20),
            DrivePosition(date="2026-10-27T00:00:00Z",elevation=10),
            DrivePosition(date="2026-10-27T00:00:20Z",elevation=15))),unknownEnergy)
        assertEquals(10,ordered.elevationGain)
        assertEquals(5,ordered.elevationLoss)
    }
}

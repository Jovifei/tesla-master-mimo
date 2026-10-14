package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrivesSummaryEvidenceTest {
    @Test
    fun unknownDistanceIsRetainedOnlyInUnfilteredWindowAndMilesUseKilometreThresholds() {
        assertEquals(true, drive().matchesDistanceFilter(DriveDistanceFilter.ALL))
        assertEquals(false, drive().matchesDistanceFilter(DriveDistanceFilter.COMMUTE))
        assertEquals(false, drive(distance = 7.0).copy(lengthUnit = "mi").matchesDistanceFilter(DriveDistanceFilter.COMMUTE))
        assertEquals(true, drive(distance = 7.0).copy(lengthUnit = "mi").matchesDistanceFilter(DriveDistanceFilter.DAY_TRIP))
    }
    @Test
    fun mixedReceiptUnitsHaveOneCanonicalSummaryAndUnknownDoesNotContribute() {
        val summary = calculateDrivesSummary(listOf(
            drive(distance = 10.0, duration = 5).copy(lengthUnit = "mi", speedMax = 60),
            drive(distance = 10.0, duration = 5).copy(lengthUnit = "km", speedMax = 80),
            drive(distance = 99.0).copy(lengthUnit = "", speedMax = 999)
        ))
        assertEquals(26.09344, summary.totalDistanceKm!!, 0.000001)
        assertEquals(2, summary.distanceSampleCount)
        assertEquals(96, summary.maxSpeedKmh)
    }

    @Test
    fun missingMaximumSpeedDoesNotBecomeZero() {
        val summary = calculateDrivesSummary(
            listOf(
                DriveData(
                    driveId = 1,
                    durationMin = 12,
                    odometerDetails = DriveOdometerDetails(distance = 8.5)
                )
            )
        )

        assertEquals(1, summary.totalDrives)
        assertEquals(8.5, summary.totalDistanceKm!!, 0.0001)
        assertNull(summary.maxSpeedKmh)
    }

    @Test
    fun emptySummaryKeepsMetricsUnavailable() {
        val summary = calculateDrivesSummary(emptyList())

        assertEquals(0, summary.totalDrives)
        assertNull(summary.totalDistanceKm)
        assertNull(summary.totalDurationMin)
        assertNull(summary.maxSpeedKmh)
    }

    @Test
    fun missingDistanceAndDurationAreUnavailableDespiteExistingRecords() {
        val summary = calculateDrivesSummary(listOf(drive()))
        assertNull(summary.totalDistanceKm)
        assertNull(summary.totalDurationMin)
        assertNull(summary.avgDistancePerDrive)
        assertNull(summary.avgDurationPerDrive)
    }

    @Test
    fun partialAveragesUseOnlyKnownMetricRecords() {
        val summary = calculateDrivesSummary(listOf(drive(distance = 10.0, duration = 20), drive()))
        assertEquals(10.0, summary.avgDistancePerDrive!!, 0.0001)
        assertEquals(20, summary.avgDurationPerDrive)
        assertEquals(1, summary.distanceSampleCount)
        assertEquals(1, summary.durationSampleCount)
        assertEquals(2, summary.totalDrives)
    }

    @Test
    fun realZeroRemainsKnown() {
        val summary = calculateDrivesSummary(listOf(drive(distance = 0.0, duration = 0)))
        assertEquals(0.0, summary.totalDistanceKm!!, 0.0001)
        assertEquals(0, summary.totalDurationMin)
        assertEquals(1, summary.distanceSampleCount)
        assertEquals(1, summary.durationSampleCount)
    }

    @Test
    fun invalidMetricInputsAreUnavailable() {
        val summary = calculateDrivesSummary(listOf(
            drive(distance = Double.NaN, duration = -1),
            drive(distance = Double.POSITIVE_INFINITY),
            drive(distance = -2.0)
        ))
        assertNull(summary.totalDistanceKm)
        assertNull(summary.totalDurationMin)
        assertEquals(0, summary.distanceSampleCount)
        assertEquals(0, summary.durationSampleCount)
    }

    @Test
    fun overflowDoesNotBecomeAnObservedTotal() {
        val summary = calculateDrivesSummary(listOf(
            drive(distance = Double.MAX_VALUE, duration = Int.MAX_VALUE),
            drive(distance = Double.MAX_VALUE, duration = Int.MAX_VALUE)
        ))
        assertNull(summary.totalDistanceKm)
        assertNull(summary.totalDurationMin)
    }

    @Test
    fun eachMetricHasIndependentCoverageAndZerosCount() {
        val summary = calculateDrivesSummary(listOf(
            drive(distance = 10.0), drive(duration = 20), drive(distance = 0.0, duration = 0)
        ))
        assertEquals(3, summary.totalDrives)
        assertEquals(2, summary.distanceSampleCount)
        assertEquals(2, summary.durationSampleCount)
        assertEquals(10.0, summary.totalDistanceKm!!, 0.0001)
        assertEquals(20, summary.totalDurationMin)
        assertEquals(5.0, summary.avgDistancePerDrive!!, 0.0001)
        assertEquals(10, summary.avgDurationPerDrive)
    }

    private fun drive(distance: Double? = null, duration: Int? = null) = DriveData(
        driveId = 1,
        durationMin = duration,
        odometerDetails = DriveOdometerDetails(distance = distance)
    )
}

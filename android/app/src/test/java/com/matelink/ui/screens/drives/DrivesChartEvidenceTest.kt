package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DrivesChartEvidenceTest {
    @Test
    fun emptyBucketHasNoDistanceOrDurationMeasurement() {
        val point = createChartPoint("day", 42, emptyList())
        assertEquals(0, point.count)
        assertNull(point.totalDistance)
        assertNull(point.totalDurationMin)
        assertEquals(42L, point.sortKey)
    }

    @Test
    fun recordsWithUnknownMetricsDoNotBecomeZeroBars() {
        val point = createChartPoint("day", 42, listOf(DriveData(driveId = 1)))
        assertEquals(1, point.count)
        assertNull(point.totalDistance)
        assertNull(point.totalDurationMin)
        assertEquals(0, point.distanceSampleCount)
        assertEquals(0, point.durationSampleCount)
    }

    @Test
    fun realZeroIsStillAnObservedBar() {
        val point = createChartPoint("day", 42, listOf(drive(0.0, 0)))
        assertEquals(0.0, point.totalDistance!!, 0.0001)
        assertEquals(0, point.totalDurationMin)
        assertEquals(1, point.distanceSampleCount)
        assertEquals(1, point.durationSampleCount)
    }

    @Test
    fun partialBucketsCarryPerMetricCoverageWithoutDilution() {
        val point = createChartPoint("day", 42, listOf(
            drive(8.5, null), drive(null, 12), DriveData(driveId = 3)
        ))
        assertEquals(3, point.count)
        assertEquals(8.5, point.totalDistance!!, 0.0001)
        assertEquals(12, point.totalDurationMin)
        assertEquals(1, point.distanceSampleCount)
        assertEquals(1, point.durationSampleCount)
    }

    @Test
    fun malformedBucketInputsStayUnavailable() {
        val point = createChartPoint("day", 42, listOf(drive(Double.NaN, -1), drive(-1.0, null)))
        assertNull(point.totalDistance)
        assertNull(point.totalDurationMin)
    }

    private fun drive(distance: Double?, duration: Int?) = DriveData(
        driveId = 1,
        odometerDetails = DriveOdometerDetails(distance = distance),
        durationMin = duration
    )
}

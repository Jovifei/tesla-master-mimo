package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.DrivePosition
import org.junit.Assert.*
import org.junit.Test

/** The projection is consumed by both the real inline and dedicated curve cards. */
class DriveCurvePhysicalEvidenceTest {
    private fun detail(unit: String = "mi", positions: List<DrivePosition>) = DriveDetail(7,
        lengthUnit = unit, odometerDetails = DriveOdometerDetails(distance = 10.0), positions = positions)

    @Test fun estimatedDistanceUsesElapsedTimeAndStartsAtZeroInsteadOfScalingTheReportedTotal() {
        val curve = projectDriveCurves(detail(positions = listOf(
            DrivePosition(date="2026-10-27T00:00:00Z", speed=0.0),
            DrivePosition(date="2026-10-27T00:00:01Z", speed=100.0),
            DrivePosition(date="2026-10-27T00:00:10Z", speed=100.0)))).distance
        assertEquals(0f, curve.values.first(), 0f)
        assertEquals(50.0 / 3600.0, curve.values[1].toDouble(), 1e-7)
        assertEquals(950.0 / 3600.0, curve.values.last().toDouble(), 1e-7)
        assertEquals(listOf(0f, .1f, 1f), curve.xFractions)
    }

    @Test fun reportedDistanceWithoutObservedSpeedCannotManufactureALinearCurve() {
        val curve = projectDriveCurves(detail(positions = listOf(
            DrivePosition(date="2026-10-27T00:00:00Z"), DrivePosition(date="2026-10-27T00:00:10Z")))).distance
        assertEquals(0, curve.validCount)
    }

    @Test fun unrelatedRowsDoNotFragmentSpeedOrPowerButLongMissingIntervalsStillDo() {
        val projection = projectDriveCurves(detail(positions = listOf(
            DrivePosition(date = "2026-10-27T00:00:00Z", speed = 20.0, power = 4.0),
            DrivePosition(date = "2026-10-27T00:00:05Z", batteryLevel = 55),
            DrivePosition(date = "2026-10-27T00:00:10Z", speed = 40.0, power = -2.0),
            DrivePosition(date = "2026-10-27T00:01:00Z", batteryLevel = 54),
            DrivePosition(date = "2026-10-27T00:01:20Z", speed = 60.0, power = 8.0)
        )))
        for (series in listOf(projection.speed, projection.power)) {
            assertEquals(listOf(0, 2, 4), series.sourceIndices)
            assertEquals(setOf(2), series.gaps)
        }
        assertEquals(listOf(20f, 40f, 60f), projection.speed.values)
        assertEquals(listOf(4f, -2f, 8f), projection.power.values)
        assertEquals(0f, projection.distance.values.last(), 0f)
    }

    @Test fun speedIsRawPreferredMphOnceAndEachSignalHasItsOwnQualification() {
        val positions = listOf(DrivePosition(date="2026-10-27T00:00:00Z", speed=60.0, power=-4.0, batteryLevel=0),
            DrivePosition(date="2026-10-27T00:00:10Z", speed=60.0, power=Double.MAX_VALUE, batteryLevel=101))
        val all = projectDriveCurves(detail(positions=positions))
        assertEquals(listOf(60f, 60f), all.speed.values)
        assertEquals(-4f, all.power.values.first(), 0f)
        assertTrue(all.power.values.last().isNaN())
        assertEquals(0f, all.battery.values.first(), 0f)
        assertTrue(all.battery.values.last().isNaN())
        assertTrue(all.hasChartData)
        assertEquals(0, projectDriveCurves(detail("", positions)).speed.validCount)
    }
}

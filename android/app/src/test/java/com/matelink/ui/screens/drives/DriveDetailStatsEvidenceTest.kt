package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.DrivePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveDetailStatsEvidenceTest {

    private val unavailableEnergy = DriveDetailEnergyPresentation(
        energyKwh = null,
        efficiencyWhKm = null,
        source = null,
        coverageSeconds = null,
        coverageRatio = null
    )

    @Test
    fun missingDetailFieldsStayUnavailable() {
        val stats = calculateDriveDetailStats(
            DriveDetail(driveId = 1),
            unavailableEnergy
        )

        assertNull(stats.speedMax)
        assertNull(stats.speedAvg)
        assertNull(stats.powerAvg)
        assertNull(stats.elevationGain)
        assertNull(stats.batteryUsed)
        assertNull(stats.distance)
        assertNull(stats.durationMin)
        assertNull(stats.avgSpeedFromDistance)
    }

    @Test
    fun observedZeroAndDerivedValuesArePreserved() {
        val stats = calculateDriveDetailStats(
            DriveDetail(
                driveId = 2,
                durationMin = 10,
                odometerDetails = DriveOdometerDetails(distance = 0.0),
                batteryDetails = DriveBatteryDetails(startBatteryLevel = 50, endBatteryLevel = 50),
                positions = listOf(
                    DrivePosition(speed = 0.0, power = 0.0, batteryLevel = 50, elevation = 10),
                    DrivePosition(speed = 0.0, power = 0.0, batteryLevel = 50, elevation = 10)
                )
            ),
            unavailableEnergy
        )

        assertEquals(0, stats.speedMax)
        assertEquals(0.0, stats.speedAvg ?: error("speed average missing"), 0.0001)
        assertEquals(0.0, stats.powerAvg ?: error("power average missing"), 0.0001)
        assertEquals(0, stats.elevationGain)
        assertEquals(0, stats.batteryUsed)
        assertEquals(0.0, stats.distance ?: error("distance missing"), 0.0001)
        assertEquals(0.0, stats.avgSpeedFromDistance ?: error("average speed missing"), 0.0001)
    }

    @Test
    fun inconsistentBatteryLevelsDoNotBecomeUsage() {
        val stats = calculateDriveDetailStats(
            DriveDetail(
                driveId = 3,
                batteryDetails = DriveBatteryDetails(startBatteryLevel = 40, endBatteryLevel = 45)
            ),
            unavailableEnergy
        )

        assertEquals(40, stats.batteryStart)
        assertEquals(45, stats.batteryEnd)
        assertNull(stats.batteryUsed)
    }

    @Test
    fun forwardedSocSamplesProducePercentagePointsWithoutInventingKwh() {
        for ((start, end, used) in listOf(Triple(61, 61, 0), Triple(74, 73, 1), Triple(0, 0, 0))) {
            val detail = DriveDetail(driveId = 4, positions = listOf(
                DrivePosition(date = "2026-10-04T01:00:00Z", batteryLevel = start),
                DrivePosition(date = "2026-10-04T01:01:00Z", batteryLevel = end)
            ))
            val stats = calculateDriveDetailStats(detail, unavailableEnergy)
            assertEquals(start, stats.batteryStart)
            assertEquals(end, stats.batteryEnd)
            assertEquals(used, stats.batteryUsed)
            assertNull(stats.energy.energyKwh)
            assertNull(stats.energy.efficiencyWhKm)
        }
    }

    @Test
    fun sparseSamplesDoNotTurnOneObservationIntoZeroUsage() {
        for ((start, end) in listOf(0 to null, null to 61, null to null)) {
            val stats = calculateDriveDetailStats(DriveDetail(driveId = 5, positions = listOf(
                DrivePosition(date = "2026-10-04T01:00:00Z", batteryLevel = start),
                DrivePosition(date = "2026-10-04T01:01:00Z", batteryLevel = end)
            )), unavailableEnergy)
            assertEquals(start, stats.batteryStart)
            assertEquals(end, stats.batteryEnd)
            assertNull(stats.batteryUsed)
        }
    }

    @Test
    fun sameInstantCannotProvideTwoEndpointsAndConflictsRemainUnknown() {
        val same = calculateDriveDetailStats(DriveDetail(driveId = 6, positions = listOf(
            DrivePosition(date = "2026-10-04T01:00:00Z", batteryLevel = 0),
            DrivePosition(date = "2026-10-04T09:00:00+08:00", batteryLevel = 0)
        )), unavailableEnergy)
        assertEquals(0, same.batteryStart)
        assertNull(same.batteryEnd)
        assertNull(same.batteryUsed)

        val conflict = calculateDriveDetailStats(DriveDetail(driveId = 7, positions = listOf(
            DrivePosition(date = "2026-10-04T01:01:00Z", batteryLevel = 73),
            DrivePosition(date = "2026-10-04T01:00:00Z", batteryLevel = 74),
            DrivePosition(date = "2026-10-04T09:00:00+08:00", batteryLevel = 75),
            DrivePosition(date = "2026-10-04T01:00:00Z", batteryLevel = 74)
        )), unavailableEnergy)
        assertNull(conflict.batteryStart)
        assertEquals(73, conflict.batteryEnd)
        assertNull(conflict.batteryUsed)
    }

    @Test
    fun timestampOrderBoundsAndExplicitEndpointsRemainDistinct() {
        val ordered = calculateDriveDetailStats(DriveDetail(driveId = 8,
            startDate = "2026-10-04T01:00:00Z", endDate = "2026-10-04T01:01:00Z",
            positions = listOf(
                DrivePosition(date = "2026-10-04T01:01:00Z", batteryLevel = 73),
                DrivePosition(date = "2026-10-04T00:59:00Z", batteryLevel = 90),
                DrivePosition(date = "2026-10-04T01:00:00Z", batteryLevel = 74),
                DrivePosition(date = "2026-02-31T01:00:00Z", batteryLevel = 99)
            )), unavailableEnergy)
        assertEquals(74, ordered.batteryStart)
        assertEquals(73, ordered.batteryEnd)
        assertEquals(1, ordered.batteryUsed)

        val explicit = calculateDriveDetailStats(DriveDetail(driveId = 9,
            batteryDetails = DriveBatteryDetails(startBatteryLevel = 0, endBatteryLevel = 0),
            positions = listOf(DrivePosition(batteryLevel = 74))), unavailableEnergy)
        assertEquals(0, explicit.batteryStart)
        assertEquals(0, explicit.batteryEnd)
        assertEquals(0, explicit.batteryUsed)
    }

    @Test
    fun absentDuplicateDoesNotEraseKnownObservationAtThatInstant() {
        for (duplicates in listOf(listOf(null, 61), listOf(61, null))) {
            val points = duplicates.map { DrivePosition(date = "2026-10-04T01:00:00Z", batteryLevel = it) } +
                DrivePosition(date = "2026-10-04T01:01:00Z", batteryLevel = 61)
            val stats = calculateDriveDetailStats(DriveDetail(driveId = 10, positions = points), unavailableEnergy)
            assertEquals(61, stats.batteryStart)
            assertEquals(61, stats.batteryEnd)
            assertEquals(0, stats.batteryUsed)
        }
    }

    @Test
    fun oneDatedObservationDoesNotSupplyTheMissingEndpoint() {
        val stats = calculateDriveDetailStats(DriveDetail(driveId = 11, positions = listOf(
            DrivePosition(date = "2026-10-04T01:00:00Z", batteryLevel = 0)
        )), unavailableEnergy)
        assertEquals(0, stats.batteryStart)
        assertNull(stats.batteryEnd)
        assertNull(stats.batteryUsed)
    }

    @Test
    fun invalidSocAtBoundaryDoesNotMoveThatBoundaryInward() {
        for ((levels, expected) in listOf(
            listOf(-1, 74, 73) to (null to 73),
            listOf(74, 73, 101) to (74 to null)
        )) {
            val points = levels.mapIndexed { index, level ->
                DrivePosition(date = "2026-10-04T01:0${index}:00Z", batteryLevel = level)
            }
            val stats = calculateDriveDetailStats(DriveDetail(driveId = 12, positions = points), unavailableEnergy)
            assertEquals(expected.first, stats.batteryStart)
            assertEquals(expected.second, stats.batteryEnd)
            assertNull(stats.batteryUsed)
        }
    }
}

package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DrivePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Drive boundaries are recorded from the drive window, while SOC rides on route samples that the
 * collector stamps independently. A boundary therefore has to be described by the nearest real
 * observed sample instead of requiring a nanosecond-exact timestamp match.
 */
class DriveDetailBatteryBoundaryTest {
    private val unavailableEnergy = DriveDetailEnergyPresentation(
        energyKwh = null, efficiencyWhKm = null, source = null,
        coverageSeconds = null, coverageRatio = null
    )

    private fun detail(
        startDate: String? = "2026-10-25T13:13:00Z",
        endDate: String? = "2026-10-25T13:20:59Z",
        positions: List<DrivePosition>,
        batteryDetails: DriveBatteryDetails? = null
    ) = DriveDetail(
        driveId = 1, startDate = startDate, endDate = endDate,
        batteryDetails = batteryDetails, positions = positions
    )

    @Test
    fun boundaryUsesNearestObservedSampleInsteadOfExactTimestampMatch() {
        val stats = calculateDriveDetailStats(
            detail(positions = listOf(
                DrivePosition(date = "2026-10-25T13:13:02Z", batteryLevel = 68),
                DrivePosition(date = "2026-10-25T13:16:00Z", batteryLevel = 67),
                DrivePosition(date = "2026-10-25T13:20:57Z", batteryLevel = 66)
            )), unavailableEnergy
        )

        // Real measurements at the nearest samples, never an interpolated number.
        assertEquals(68, stats.batteryStart)
        assertEquals(66, stats.batteryEnd)
        assertEquals(2, stats.batteryUsed)
    }

    @Test
    fun nearestSampleWinsOnBothSidesOfTheBoundary() {
        val stats = calculateDriveDetailStats(
            detail(positions = listOf(
                // 40s before the window opens, 5s after it opens: the closer one describes the start.
                DrivePosition(date = "2026-10-25T13:12:20Z", batteryLevel = 70),
                DrivePosition(date = "2026-10-25T13:13:05Z", batteryLevel = 69),
                DrivePosition(date = "2026-10-25T13:20:50Z", batteryLevel = 66),
                DrivePosition(date = "2026-10-25T13:21:30Z", batteryLevel = 64)
            )), unavailableEnergy
        )

        assertEquals(69, stats.batteryStart)
        assertEquals(66, stats.batteryEnd)
    }

    @Test
    fun midDriveSampleIsNotMistakenForTheBoundaryValue() {
        val stats = calculateDriveDetailStats(
            detail(positions = listOf(
                // Three minutes into a seven-minute trip: real, but it describes mid-drive SOC.
                DrivePosition(date = "2026-10-25T13:16:00Z", batteryLevel = 80),
                DrivePosition(date = "2026-10-25T13:17:00Z", batteryLevel = 60)
            )), unavailableEnergy
        )

        // The tolerance is capped at a tenth of the drive, so neither boundary is guessed.
        assertNull(stats.batteryStart)
        assertNull(stats.batteryEnd)
        assertNull(stats.batteryUsed)
    }

    @Test
    fun toleratedWindowWidensForLongerDrives() {
        val stats = calculateDriveDetailStats(
            DriveDetail(
                driveId = 9,
                startDate = "2026-10-25T10:00:00Z",
                endDate = "2026-10-25T11:00:00Z",
                positions = listOf(
                    // Two minutes out is within the 5-minute cap for an hour-long drive.
                    DrivePosition(date = "2026-10-25T09:58:00Z", batteryLevel = 90),
                    DrivePosition(date = "2026-10-25T11:02:00Z", batteryLevel = 74)
                )
            ), unavailableEnergy
        )

        assertEquals(90, stats.batteryStart)
        assertEquals(74, stats.batteryEnd)
        assertEquals(16, stats.batteryUsed)
    }

    @Test
    fun samplesOutsideTheToleranceWindowStayUnobserved() {
        val stats = calculateDriveDetailStats(
            DriveDetail(
                driveId = 9,
                startDate = "2026-10-25T10:00:00Z",
                endDate = "2026-10-25T11:00:00Z",
                positions = listOf(
                    // Nine minutes out on an hour-long drive: past the cap, so still unobserved.
                    DrivePosition(date = "2026-10-25T09:51:00Z", batteryLevel = 80),
                    DrivePosition(date = "2026-10-25T10:30:00Z", batteryLevel = 67),
                    DrivePosition(date = "2026-10-25T11:09:00Z", batteryLevel = 60)
                )
            ), unavailableEnergy
        )

        assertNull(stats.batteryStart)
        assertNull(stats.batteryEnd)
        assertNull(stats.batteryUsed)
    }

    @Test
    fun serverReportedSummaryStillWinsOverSamples() {
        val stats = calculateDriveDetailStats(
            detail(
                positions = listOf(
                    DrivePosition(date = "2026-10-25T13:13:02Z", batteryLevel = 68),
                    DrivePosition(date = "2026-10-25T13:20:57Z", batteryLevel = 66)
                ),
                batteryDetails = DriveBatteryDetails(startBatteryLevel = 71, endBatteryLevel = 65)
            ), unavailableEnergy
        )

        assertEquals(71, stats.batteryStart)
        assertEquals(65, stats.batteryEnd)
        assertEquals(6, stats.batteryUsed)
    }

    @Test
    fun conflictingSamplesOnTheExactBoundaryStayUnknown() {
        val stats = calculateDriveDetailStats(
            detail(positions = listOf(
                DrivePosition(date = "2026-10-25T13:13:00Z", batteryLevel = 68),
                DrivePosition(date = "2026-10-25T13:13:00Z", batteryLevel = 67),
                DrivePosition(date = "2026-10-25T13:20:59Z", batteryLevel = 66)
            )), unavailableEnergy
        )

        // The duplicated boundary instant disagrees with itself, so the start stays unknown rather
        // than silently picking one of two conflicting observations.
        assertNull(stats.batteryStart)
        assertEquals(66, stats.batteryEnd)
    }

    @Test
    fun repeatedAgreeingSamplesOnTheBoundaryAreAccepted() {
        val stats = calculateDriveDetailStats(
            detail(positions = listOf(
                DrivePosition(date = "2026-10-25T13:13:00Z", batteryLevel = 68),
                DrivePosition(date = "2026-10-25T13:13:00Z", batteryLevel = 68),
                DrivePosition(date = "2026-10-25T13:20:59Z", batteryLevel = 66)
            )), unavailableEnergy
        )

        assertEquals(68, stats.batteryStart)
        assertEquals(66, stats.batteryEnd)
    }

    @Test
    fun outOfRangeSampleValuesAreIgnoredForBoundaries() {
        val stats = calculateDriveDetailStats(
            detail(positions = listOf(
                DrivePosition(date = "2026-10-25T13:13:01Z", batteryLevel = 101),
                DrivePosition(date = "2026-10-25T13:20:58Z", batteryLevel = -1)
            )), unavailableEnergy
        )

        assertNull(stats.batteryStart)
        assertNull(stats.batteryEnd)
    }

    @Test
    fun boundaryWithoutSamplesStaysUnavailable() {
        val stats = calculateDriveDetailStats(
            detail(startDate = null, endDate = null,
                positions = listOf(DrivePosition(date = "2026-10-25T13:16:00Z", batteryLevel = 67))),
            unavailableEnergy
        )

        assertNull(stats.batteryStart)
        assertNull(stats.batteryEnd)
    }

    @Test
    fun risingSocDoesNotBecomeNegativeUsage() {
        val stats = calculateDriveDetailStats(
            detail(positions = listOf(
                DrivePosition(date = "2026-10-25T13:13:01Z", batteryLevel = 64),
                DrivePosition(date = "2026-10-25T13:20:58Z", batteryLevel = 68)
            )), unavailableEnergy
        )

        assertEquals(64, stats.batteryStart)
        assertEquals(68, stats.batteryEnd)
        assertNull(stats.batteryUsed)
    }
}

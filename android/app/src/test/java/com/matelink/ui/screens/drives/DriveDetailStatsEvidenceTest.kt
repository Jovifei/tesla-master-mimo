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
                    DrivePosition(speed = 0, power = 0, batteryLevel = 50, elevation = 10),
                    DrivePosition(speed = 0, power = 0, batteryLevel = 50, elevation = 10)
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
}

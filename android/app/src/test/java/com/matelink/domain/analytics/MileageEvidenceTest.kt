package com.matelink.domain.analytics

import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MileageEvidenceTest {

    @Test
    fun missingFieldsAreNotCountedAsObservedZeroes() {
        val result = buildMileageEvidence(
            listOf(
                drive(id = 1),
                drive(id = 2, distanceKm = 0.0, energyKwh = Double.NaN),
                drive(id = 3, distanceKm = 12.0, energyKwh = 0.0, startBattery = 50, endBattery = 50)
            )
        )

        assertEquals(3, result.recordCount)
        assertEquals(1, result.distanceSampleCount)
        assertEquals(1, result.energySampleCount)
        assertEquals(1, result.batterySampleCount)
    }

    @Test
    fun trueZeroEnergyAndBatteryDeltaRemainObserved() {
        val record = drive(id = 1, distanceKm = 1.0, energyKwh = 0.0, startBattery = 50, endBattery = 50)

        assertEquals(1.0, record.observedDistanceKm() ?: error("distance missing"), 0.0)
        assertEquals(0.0, record.observedEnergyKwh() ?: error("energy missing"), 0.0)
        assertEquals(0.0, record.observedBatteryUsagePercent() ?: error("battery missing"), 0.0)
    }

    @Test
    fun negativeBatteryDeltaIsNotPresentedAsEnergyUsed() {
        val record = drive(id = 1, distanceKm = 5.0, energyKwh = 1.0, startBattery = 40, endBattery = 42)

        assertNull(record.observedBatteryUsagePercent())
    }

    private fun drive(
        id: Int,
        distanceKm: Double? = null,
        energyKwh: Double? = null,
        startBattery: Int? = null,
        endBattery: Int? = null
    ) = DriveData(
        driveId = id,
        odometerDetails = distanceKm?.let { DriveOdometerDetails(distance = it) },
        batteryDetails = if (startBattery != null || endBattery != null) {
            DriveBatteryDetails(startBatteryLevel = startBattery, endBatteryLevel = endBattery)
        } else null,
        energyConsumedNet = energyKwh
    )
}

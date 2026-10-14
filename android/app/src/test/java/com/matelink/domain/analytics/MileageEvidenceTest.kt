package com.matelink.domain.analytics

import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MileageEvidenceTest {
    @Test fun signedAndZeroQualifiedEnergyKeepTheirMeaning() {
        val negative = drive(1, 10.0, -1.0)
        val zero = drive(2, 10.0, 0.0)
        assertEquals(-1.0, negative.observedEnergyKwh()!!, 0.0)
        val evidence = buildMileageEvidence(listOf(negative, zero))
        assertEquals(2, evidence.energySampleCount)
        assertEquals(2, evidence.efficiencySampleCount)
        assertEquals(-50.0, evidence.efficiencyWhKm!!, 0.0)
    }

    @Test fun malformedOrExplicitUnknownEnergyNeverFallsBackToRawScalar() {
        val denied = drive(1, 10.0, 999.0).copy(energyContract = EnergyContract(
            netEnergy = EnergyMetric(valueKwh = 999.0, quality = "unknown")))
        assertNull(denied.observedEnergyKwh())
        val evidence = buildMileageEvidence(listOf(denied))
        assertEquals(0, evidence.energySampleCount)
        assertNull(evidence.efficiencyWhKm)
    }

    @Test fun estimatesAreQualifiedAndCountedSeparatelyFromReports() {
        val start = "2026-10-14T01:00:00Z"
        val end = "2026-10-14T01:30:00Z"
        val estimate = drive(1, 10.0, 999.0).copy(startDate = start, endDate = end,
            source = "telemetry_mqtt", energyContract = EnergyContract(netEnergy = EnergyMetric(
                valueKwh = -1.0, method = "drive_power_integral", measurementPoint = "drive_power",
                source = "telemetry_mqtt", quality = "estimated", startDate = start, endDate = end,
                coverageKind = "time", coverageSeconds = 1800.0, coverageRatio = 1.0)))
        assertEquals(-1.0, estimate.observedEnergyKwh()!!, 0.0)
        val evidence = buildMileageEvidence(listOf(estimate, drive(2, 10.0, 1.0)))
        assertEquals(2, evidence.energySampleCount)
        assertEquals(1, evidence.estimatedEnergySampleCount)
        assertEquals(0.0, evidence.efficiencyWhKm!!, 0.0)
    }

    @Test fun unqualifiedEnergyAndUnknownDistanceDoNotDiluteWeightedMileageEfficiency() {
        val evidence = buildMileageEvidence(listOf(drive(1, 10.0, 2.0),
            drive(2, 90.0, null), drive(3, null, 9.0)))
        assertEquals(2, evidence.energySampleCount)
        assertEquals(2, evidence.distanceSampleCount)
        assertEquals(1, evidence.efficiencySampleCount)
        assertEquals(200.0, evidence.efficiencyWhKm!!, 0.0)
    }

    @Test fun imperialReceiptUsesCanonicalKilometresForMileageEvidence() {
        val record = drive(1, 100 / 1.609344, 10.0).copy(lengthUnit = "mi")
        assertEquals(100.0, record.observedDistanceKm()!!, 1e-10)
        assertEquals(100.0, buildMileageEvidence(listOf(record)).efficiencyWhKm!!, 1e-10)
        assertNull(record.copy(lengthUnit = "unknown").observedDistanceKm())
        assertNull(buildMileageEvidence(listOf(record.copy(lengthUnit = "unknown"))).efficiencyWhKm)
    }

    @Test fun quarantinedAndUnqualifiedRecordsCannotSupplyMileageMetrics() {
        for (state in listOf("quarantined", "incomplete", "unknown")) {
            val record = drive(1, 10.0, 5.0).copy(qualityState = state)
            assertNull(record.observedDistanceKm())
            assertNull(record.observedEnergyKwh())
            assertNull(record.observedBatteryUsagePercent())
        }
    }

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
        assertEquals(2, result.distanceSampleCount)
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
        energyConsumedNet = energyKwh,
        qualityState = "observed"
    )
}

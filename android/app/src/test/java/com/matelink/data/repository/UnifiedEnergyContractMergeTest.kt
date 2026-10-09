package com.matelink.data.repository

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Exercises the actual list and Room-prep merger, rather than a detached helper. */
class UnifiedEnergyContractMergeTest {
    private val start = "2026-10-08T00:00:00Z"
    private val end = "2026-10-08T00:30:00Z"

    private fun unknownBattery() = EnergyContract(batteryInput = EnergyMetric(
        method = "session_counter_delta", measurementPoint = "battery_input",
        source = "telemetry_mqtt", quality = "unknown",
        reason = "counter_replay_or_reset", startDate = start, endDate = end
    ))

    @Test fun restoredCachedUnknownBatteryMustNotBecomeRemoteLegacyScalar() {
        val saved = ChargeData(chargeId = 7, startDate = start, endDate = end,
            source = "telemetry_mqtt", qualityState = "observed",
            chargeEnergyAdded = 17.0, energyContract = unknownBattery())
        val remoteWithoutContract = saved.copy(
            chargeEnergyAdded = 23.0, energyContract = null
        )
        val combined = UnifiedHistoryRepository.mergeCharges(
            listOf(remoteWithoutContract), listOf(saved)
        ).single()
        assertNotNull(combined.energyContract)
        assertNull(combined.chargeEnergyAdded)
        assertNull(combined.batteryInputKwh)
    }

    @Test fun newerReportedZeroKeepsFullWindowEvidenceAndDoesNotCopyStaleValue() {
        val saved = ChargeData(chargeId = 8, startDate = start, endDate = end,
            source = "telemetry_mqtt", qualityState = "observed",
            chargeEnergyAdded = 7.0, energyContract = unknownBattery())
        val zero = EnergyMetric(valueKwh = 0.0, method = "session_counter_delta",
            measurementPoint = "battery_input", source = "telemetry_mqtt",
            quality = "reported", startDate = start, endDate = end,
            coverageKind = "endpoints", coverageRatio = 1.0)
        val updated = saved.copy(
            chargeEnergyAdded = 0.0, energyContract = EnergyContract(batteryInput = zero)
        )
        val merged = UnifiedHistoryRepository.mergeCharges(listOf(updated), listOf(saved)).single()
        assertEquals(0.0, merged.chargeEnergyAdded!!, 0.0)
        assertEquals(0.0, merged.batteryInputKwh!!, 0.0)
    }

    @Test fun driveContractUnknownSurvivesCompatibleRemoteMergeAndHidesStaleEnergy() {
        val unknown = EnergyContract(netEnergy = EnergyMetric(
            method = "api_reported_net", measurementPoint = "reported_net",
            source = "telemetry_mqtt", quality = "unknown",
            startDate = start, endDate = end
        ))
        val cached = DriveData(driveId = 9, startDate = start, endDate = end,
            source = "telemetry_mqtt", qualityState = "observed",
            energyConsumedNet = 7.0, consumptionNet = 700.0,
            odometerDetails = DriveOdometerDetails(distance = 10.0),
            energyContract = unknown)
        val withoutProof = cached.copy(energyConsumedNet = 10.0, energyContract = null)
        val merged = UnifiedHistoryRepository.mergeDrives(listOf(withoutProof), listOf(cached)).single()
        assertNotNull(merged.energyContract)
        assertNull(merged.energyConsumedNet)
        assertNull(merged.consumptionNet)
        assertNull(merged.netEnergyKwh)
    }

    @Test fun remoteExplicitUnknownCannotBeOverriddenByPositiveCachedScalar() {
        val cached = ChargeData(chargeId = 10, startDate = start, endDate = end,
            source = "telemetry_mqtt", qualityState = "observed",
            chargeEnergyAdded = 99.0)
        val explicitUnknown = cached.copy(
            energyContract = unknownBattery(), chargeEnergyAdded = null
        )
        val merged = UnifiedHistoryRepository.mergeCharges(
            listOf(explicitUnknown), listOf(cached)
        ).single()
        assertNull(merged.chargeEnergyAdded)
        assertNotNull(merged.energyContract)
    }
}

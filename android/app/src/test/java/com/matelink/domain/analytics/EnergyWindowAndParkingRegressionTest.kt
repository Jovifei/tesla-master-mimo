package com.matelink.domain.analytics

import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.data.api.models.netValueForWindow
import com.matelink.data.api.models.ChargeDetail
import com.matelink.ui.screens.charges.ChargeStatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnergyWindowAndParkingRegressionTest {
    private val start = "2026-10-08T00:00:00Z"
    private val end = "2026-10-08T01:00:00Z"

    private fun nominalEnergy(activity: String?) = EnergyMetric(
        valueKwh = 1.5, quality = "estimated", source = "telemetry_mqtt",
        sourceField = "EnergyRemaining", method = "energy_remaining_delta",
        measurementPoint = "nominal_battery_remaining",
        startDate = start, endDate = end,
        observedStartAt = start, observedEndAt = end,
        coverageKind = "endpoints", coverageRatio = 1.0,
        activityEvidence = activity
    )

    @Test fun nominalBatteryChangeWithoutProofIsNeitherDriveNetNorParkedKwh() {
        val incomplete = nominalEnergy(null)
        assertNull(EnergyContract(netEnergy = incomplete).netValueForWindow(start, end))
        val parked = ParkedDetailData(1, 2, start, end, source = "telemetry_mqtt",
            energyKwh = 44.0, energyContract = EnergyContract(storedChange = incomplete))
        assertNull(parked.qualifiedParkedEnergyKwh)
        assertNull(parked.qualifiedAveragePowerW)
    }

    @Test fun validParkedWindowYieldsEstimatedWNotKw() {
        val verified = nominalEnergy("no_driving_or_charging_during_window")
        val parked = ParkedDetailData(1, 2, start, end, source = "telemetry_mqtt",
            energyContract = EnergyContract(storedChange = verified))
        assertEquals(1.5, parked.qualifiedParkedEnergyKwh!!, 0.00001)
        assertEquals(1500.0, parked.qualifiedAveragePowerW!!, 0.00001)
        assertNull(EnergyContract(netEnergy = verified).netValueForWindow(start, end))
        val archive = parked.copy(source = "teslamate_archive")
        assertNull(archive.qualifiedParkedEnergyKwh)
        assertNull(parked.copy(linkedCharge = com.matelink.data.api.models.LinkedCharge(9)).qualifiedParkedEnergyKwh)
    }

    @Test fun onlyExplicitContinuousDriveMayUseNominalEstimateAsNet() {
        val metric = nominalEnergy("continuous_drive_no_charging_with_valid_endpoints")
        assertEquals(1.5, EnergyContract(netEnergy = metric).netValueForWindow(start, end)!!, 0.0)
    }

    @Test fun legacyChargeEnergyPairsCannotImplyACLossOrEfficiency() {
        val detail = ChargeDetail(chargeId = 1, chargeEnergyAdded = 8.0,
            chargeEnergyUsed = 10.0, chargeType = "ac", startDate = start, endDate = end)
        val stats = ChargeStatsCalculator.calculateStats(detail)
        assertEquals(8.0, stats.energyAdded!!, 0.0)
        assertNull(stats.efficiency)
        val battery = EnergyMetric(8.0, quality = "reported", source = "telemetry_mqtt",
            method = "session_counter_delta", measurementPoint = "battery_input",
            sourceField = "DCChargingEnergyIn", startDate = start, endDate = end,
            observedStartAt = start, observedEndAt = end,
            timeBasis = "collector_received_at", coverageKind = "endpoints", coverageRatio = 1.0)
        val input = battery.copy(valueKwh = 10.0, measurementPoint = "ac_charger_input", sourceField = "ACChargingEnergyIn")
        // Complete counter endpoints alone cannot establish a full AC-only
        // session. Without explicit mode evidence, loss and efficiency stay unknown.
        val unverified = detail.copy(energyContract = EnergyContract(batteryInput = battery, acInput = input))
        assertNull(ChargeStatsCalculator.calculateStats(unverified).efficiency)
        // In a distinct complete source-verified AC-only session, 8/10 = 80%.
        val qualified = unverified.copy(energyContract = EnergyContract(
            batteryInput = battery,
            acInput = input,
            chargeMode = "ac",
            chargeModeEvidence = "observed_boundary_modes_no_conflict"
        ))
        assertEquals(80.0, ChargeStatsCalculator.calculateStats(qualified).efficiency!!, 0.00001)
        assertNull(ChargeStatsCalculator.calculateStats(qualified.copy(chargeType = "dc")).efficiency)
        assertNull(ChargeStatsCalculator.calculateStats(qualified.copy(
            energyContract = qualified.energyContract!!.copy(chargeModeEvidence = null)
        )).efficiency)
    }
}

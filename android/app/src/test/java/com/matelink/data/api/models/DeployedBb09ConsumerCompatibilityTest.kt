package com.matelink.data.api.models

import com.matelink.domain.analytics.*
import com.matelink.ui.screens.charges.ChargeStatsCalculator
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test

/**
 * Source-derived, synthetic bb09fac legacy JSON envelopes, not an authenticated
 * provider response or a natural drive. Decode through actual Moshi models,
 * then through the Room summary codec and displayed charge detail statistics.
 * Deployed bb09fac does not publish the additive energy_contract field.
 */
class DeployedBb09ConsumerCompatibilityTest {
    private val moshi = Moshi.Builder().build()

    @Test fun listEnvelopesRetainObservedZeroAndUnknownThroughRoom() {
        val drives = moshi.adapter(DrivesResponse::class.java).fromJson("""
            {"data":{"drives":[{"drive_id":41,"start_date":"2026-10-08T00:00:00Z",
            "end_date":"2026-10-08T00:10:00Z","source":"teslamate_archive",
            "quality_state":"observed","speed_max":0,"speed_avg":0.0,
            "battery_details":{"start_battery_level":0,"end_battery_level":null},
            "odometer_details":{"odometer_start":100.0,"odometer_end":102.0,
            "odometer_distance":2.0},"energy_consumed_net":null,"consumption_net":null}],
            "meta":{"availability":"collecting","source":"teslamate_archive"}}}
        """.trimIndent())!!.data!!.drives!!
        val drive = drives.single()
        assertEquals(0, drive.speedMax)
        assertEquals(0, drive.startBatteryLevel)
        assertNull(drive.endBatteryLevel)
        assertNull(drive.netEnergyKwh)
        val restored = drive.toSyncSummary(3)!!.toAnalysisDriveData()
        assertNull(restored.netEnergyKwh)
        assertEquals(0, restored.speedMax)
        assertEquals(0, restored.startBatteryLevel)
        assertNull(restored.endBatteryLevel)

        val charges = moshi.adapter(ChargesResponse::class.java).fromJson("""
            {"data":{"charges":[
            {"charge_id":42,"start_date":"2026-10-08T00:00:00Z",
            "end_date":"2026-10-08T00:20:00Z","source":"teslamate_archive",
            "quality_state":"observed","charge_energy_added":null,
            "charge_energy_used":null,
            "battery_details":{"start_battery_level":0,"end_battery_level":null}},
            {"charge_id":43,"start_date":"2026-10-08T01:00:00Z",
            "end_date":"2026-10-08T01:20:00Z","source":"teslamate_archive",
            "charge_energy_added":0.0,"battery_details":{"start_battery_level":0,
            "end_battery_level":0}}],"meta":{"source":"teslamate_archive"}}}
        """.trimIndent())!!.data!!.charges!!
        assertNull(charges.first().batteryInputKwh)
        assertEquals(0.0, charges.last().batteryInputKwh!!, 0.0)
        val unknownCached = charges.first().toSyncSummary(3)!!.toAnalysisChargeData()
        assertNull(unknownCached.batteryInputKwh)
        assertEquals(0, unknownCached.startBatteryLevel)
        assertNull(unknownCached.endBatteryLevel)
        val zeroCached = charges.last().toSyncSummary(3)!!.toAnalysisChargeData()
        assertEquals(0.0, zeroCached.batteryInputKwh!!, 0.0)
    }

    @Test fun bb09DetailEnergyIsEstimatedOnlyWithCoveredArchiveSamples() {
        val detail = moshi.adapter(DriveDetailResponse::class.java).fromJson("""
            {"data":{"drive":{"drive_id":41,"source":"teslamate_archive",
            "start_date":"2026-10-08T00:00:00Z","end_date":"2026-10-08T00:01:00Z",
            "odometer_details":{"odometer_distance":1.0},
            "energy_consumed_net":null,
            "drive_details":[{"date":"2026-10-08T00:00:00Z","power":36.0},
            {"date":"2026-10-08T00:00:10Z","power":36.0}]}}}
        """.trimIndent())!!.data!!.drive!!
        val resolved = detail.resolveDriveEnergy()
        assertNull(resolved.estimate.energyKwh)
        assertNull(resolved.evidence.valueKwh)
        assertEquals("unknown", resolved.evidence.quality)
        assertEquals("drive_power", resolved.evidence.measurementPoint)
        assertEquals(0.1, resolved.evidence.coveredEnergyKwh!!, 0.0000001)
        assertNull(detail.netEnergyKwh)
    }

    @Test fun legacyDetailAndParkingDoNotInventMeterOrParkedEnergy() {
        val charge = moshi.adapter(ChargeDetailResponse::class.java).fromJson("""
            {"data":{"charge":{"charge_id":42,"source":"teslamate_archive",
            "start_date":"2026-10-08T02:00:00Z","end_date":"2026-10-08T02:20:00Z",
            "charge_energy_added":null,"charge_energy_used":null,
            "charge_details":[{"date":"2026-10-08T02:00:00Z",
            "battery_level":0,"charger_details":{"charger_power":0.0}}]}}}
        """.trimIndent())!!.data!!.charge!!
        assertNull(charge.batteryInputKwh)
        assertNull(charge.inputEnergyKwh)
        assertNull(ChargeStatsCalculator.calculateStats(charge).energyAdded)
        assertEquals(0, charge.chargePoints!!.single().batteryLevel)
        assertEquals(0, charge.chargePoints!!.single().chargerPower)

        val parked = moshi.adapter(ParkedDetailResponse::class.java).fromJson("""
            {"data":{"older_drive_id":41,"newer_drive_id":44,
            "start_date":"2026-10-08T00:01:00Z","end_date":"2026-10-08T02:00:00Z",
            "start_battery_level":80,"end_battery_level":79,
            "battery_delta":1,"energy_kwh":null,"average_power_kw":null,
            "source":"teslamate_archive","sample_count":0,"coverage_seconds":0,
            "coverage_ratio":0.0,"linked_charge":null}}
        """.trimIndent())!!.data!!
        assertNull(parked.qualifiedParkedEnergyKwh)
        assertNull(parked.qualifiedAveragePowerW)
    }

    @Test fun additiveUnknownContractMustOverrideLegacyPositiveScalar() {
        val charge = moshi.adapter(ChargesResponse::class.java).fromJson("""
            {"data":{"charges":[{"charge_id":81,
            "start_date":"2026-10-08T00:00:00Z","end_date":"2026-10-08T00:30:00Z",
            "charge_energy_added":12.0,"charge_energy_used":14.0,
            "energy_contract":{"version":1,"battery_input":{"value_kwh":null,
            "unit":"kWh","method":"session_counter_delta",
            "measurement_point":"battery_input","source":"telemetry_mqtt",
            "quality":"unknown","reason":"partial_counter_window",
            "coverage_kind":"endpoints","coverage_ratio":0.5,
            "covered_energy_kwh":1.5}}}]}}
        """.trimIndent())!!.data!!.charges!!.single()
        assertNull(charge.batteryInputKwh)
        assertNull(charge.inputEnergyKwh)
        assertNull(charge.withQualifiedEnergy().chargeEnergyAdded)
    }
}

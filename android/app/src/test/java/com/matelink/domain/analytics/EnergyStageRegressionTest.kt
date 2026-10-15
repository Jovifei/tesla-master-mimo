package com.matelink.domain.analytics

import com.matelink.data.api.models.*
import com.matelink.data.repository.mergeStoredDrive
import com.matelink.data.sync.toSyncSummary
import com.matelink.ui.screens.drives.toQualifiedHistoryMetrics
import com.matelink.ui.screens.drives.historyEnergy
import com.matelink.ui.screens.drives.matchesHistoryWindow
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test

/** Replays the four independently compiled failures through real Moshi/codec/list consumers. */
class EnergyStageRegressionTest {
    private val moshi = Moshi.Builder().build()
    private val start = "2026-10-13T00:00:00Z"
    private val end = "2026-10-13T00:01:00Z"
    private val base = """"drive_id":4,"start_date":"$start","end_date":"$end","source":"teslamate_archive","odometer_details":{"odometer_distance":2.0},"battery_details":{"start_battery_level":80,"end_battery_level":79}"""
    private fun detail(partial: Boolean = false): DriveDetail {
        val points = if (partial) ""","drive_details":[{"date":"$start","power":10.0},{"date":"2026-10-13T00:00:10Z","power":10.0}]""" else ""
        return requireNotNull(moshi.adapter(DriveDetail::class.java).fromJson("{$base$points}"))
    }
    private fun summary() = requireNotNull(moshi.adapter(DriveData::class.java).fromJson("{$base}"))
        .toSyncSummary(7)!!

    @Test fun partialPowerMustNotInvalidateSocEstimatePersistence() {
        val detail = detail(true)
        val resolved = detail.resolveDriveEnergy(batteryCapacityKwh = 60.0)
        assertEquals(0.6, resolved.estimate.energyKwh!!, 1e-12)
        val saved = summary().withResolvedDriveEnergy(detail, resolved)
        assertEquals(0.6, saved.energyConsumed ?: Double.NaN, 1e-12)
    }

    @Test fun listReloadMustPreserveCapacityEstimateSource() {
        val detail = detail()
        val saved = summary().withResolvedDriveEnergy(detail,
            detail.resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD"))
        assertEquals(0.784, saved.energyConsumed!!, 1e-12)
        assertEquals("soc_capacity_estimate", mergeStoredDrive(summary(), saved).toQualifiedHistoryMetrics().source)
    }

    @Test fun backgroundResolutionMustNotReplaceRefinedCapacity() {
        val detail = detail()
        val saved = summary().withResolvedDriveEnergy(detail,
            detail.resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD"))
        val background = saved.withResolvedDriveEnergy(detail, detail.resolveDriveEnergy())
        assertEquals(0.784, background.energyConsumed ?: Double.NaN, 1e-12)
    }

    @Test fun missingNoChargeEvidenceMustNotQualifyNominalTripEnergy() {
        val raw = """{"version":1,"net_energy":{"value_kwh":2.0,"unit":"kWh","method":"energy_remaining_delta","measurement_point":"nominal_battery_remaining","source":"telemetry_mqtt","source_field":"EnergyRemaining","quality":"estimated","start_date":"$start","end_date":"$end","coverage_kind":"endpoints","coverage_ratio":1.0}}"""
        val contract = moshi.adapter(EnergyContract::class.java).fromJson(raw)
        assertNull(contract.netValueForWindow(start, end))
    }

    private fun nominal(value: Double = 2.0) = EnergyMetric(valueKwh = value,
        method = "energy_remaining_delta", measurementPoint = "nominal_battery_remaining",
        source = "telemetry_mqtt", sourceField = "EnergyRemaining", quality = "estimated",
        startDate = start, endDate = end, observedStartAt = start, observedEndAt = end,
        timeBasis = "collector_received_at", coverageKind = "endpoints", coverageRatio = 1.0,
        activityEvidence = "continuous_drive_no_charging_with_valid_endpoints")

    @Test fun malformedNominalEvidenceCannotAcquireDriveNetEnergy() {
        val good = nominal()
        val bad = listOf(good.copy(sourceField = "BatteryLevel"), good.copy(source = "local_import"),
            good.copy(timeBasis = "source_report"), good.copy(activityEvidence = "charging_during_window"),
            good.copy(observedStartAt = null), good.copy(observedEndAt = "invalid"),
            good.copy(observedStartAt = "2026-10-12T23:59:59Z"),
            good.copy(observedEndAt = "2026-10-13T00:01:01Z"),
            good.copy(observedStartAt = end, observedEndAt = start),
            good.copy(observedStartAt = "2026-10-13T00:00:31Z"),
            good.copy(observedEndAt = "2026-10-13T00:00:54Z", observedStartAt = "2026-10-13T00:00:01Z"),
            good.copy(measurementPoint = "battery_energy_remaining"))
        bad.forEach { assertNull("bad nominal evidence: $it", EnergyContract(netEnergy = it).netValueForWindow(start, end)) }
    }

    @Test fun realNominalZeroAndSignedIncreaseRemainExplicitEstimates() {
        listOf(0.0, -0.4, 2.0).forEach { value ->
            val contract = EnergyContract(netEnergy = nominal(value))
            assertEquals(value, contract.netValueForWindow(start, end)!!, 1e-12)
            val drive = DriveData(4, startDate = start, endDate = end, source = "telemetry_mqtt",
                odometerDetails = DriveOdometerDetails(distance = 2.0), energyContract = contract)
            val row = drive.toSyncSummary(7)!!
            assertEquals("energy_remaining_delta", row.energySource)
            assertEquals("energy_remaining_delta", mergeStoredDrive(drive.toSyncSummary(7)!!, row).energySource)
        }
    }

    @Test fun explicitUnknownContractCannotFallBackToPowerOrSoc() {
        val unknown = EnergyContract(netEnergy = EnergyMetric(reason = "provider_energy_unsupported"))
        val resolved = detail(true).copy(energyContract = unknown).resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD")
        assertNull(resolved.estimate.energyKwh)
        assertEquals(DriveEnergySource.UNAVAILABLE, resolved.estimate.source)
        assertEquals("provider_energy_unsupported", resolved.evidence.reason)
    }

    @Test fun capacityEvidenceRecordsAssumptionAndCorrectedSoc() {
        val d = detail()
        val resolved = d.resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD")
        val saved = summary().withResolvedDriveEnergy(d, resolved)
        val next = d.copy(batteryDetails = DriveBatteryDetails(80, 78))
        val recomputed = saved.withResolvedDriveEnergy(next, next.resolveDriveEnergy())
        val metric = recomputed.toAnalysisDriveData().energyContract!!.netEnergy!!
        assertEquals(1.568, recomputed.energyConsumed!!, 1e-12)
        assertEquals(78.4, metric.capacityKwh!!, 1e-12)
        assertEquals("model_trim_assumption", metric.capacityBasis)
        assertEquals(1, metric.calculationVersion)
        assertEquals(78, metric.endSoc)
        assertEquals("estimated", metric.quality)
        assertNull(EnergyContract(netEnergy = metric.copy(capacityKwh = null)).netValueForWindow(start, end))
        assertNull(EnergyContract(netEnergy = metric.copy(capacityBasis = "measured_by_guess")).netValueForWindow(start, end))
        val unchangedSoc = d.copy(batteryDetails = DriveBatteryDetails(80, 80))
        assertNull(saved.withResolvedDriveEnergy(unchangedSoc, unchangedSoc.resolveDriveEnergy()).energyConsumed)
    }

    @Test fun listCardCannotBorrowAnotherSourceWindowOrVehicleEstimate() {
        val d = detail()
        val saved = summary().withResolvedDriveEnergy(d, d.resolveDriveEnergy(batteryCapacityKwh = 60.0))
        val drive = saved.toAnalysisDriveData()
        val wrongSource = drive.copy(source = "other_source", energyConsumedNet = null, energyContract = null)
        assertNull(wrongSource.historyEnergy(saved.toQualifiedHistoryMetrics()).energyKwh)
        val wrongWindow = drive.copy(endDate = "2026-10-13T00:02:00Z", energyConsumedNet = null, energyContract = null)
        assertNull(wrongWindow.historyEnergy(saved.toQualifiedHistoryMetrics()).energyKwh)
        assertFalse(saved.copy(carId = 8).matchesHistoryWindow(saved))
        assertFalse(wrongSource.toSyncSummary(7)!!.matchesHistoryWindow(saved))
        assertFalse(wrongWindow.toSyncSummary(7)!!.matchesHistoryWindow(saved))
    }

    @Test fun explicitUnknownCannotFallBackToHistoricalSoc() {
        val drive = detail().let { summary().withResolvedDriveEnergy(it, it.resolveDriveEnergy(batteryCapacityKwh = 60.0)) }.toAnalysisDriveData()
        val unknown = drive.copy(energyConsumedNet = null, energyContract = EnergyContract(
            netEnergy = EnergyMetric(quality = "unknown", reason = "local_detail_scope_or_window_mismatch")))
        assertNull(unknown.historyEnergy(batteryCapacityKwh = 78.4).energyKwh)
        assertNotNull(drive.copy(energyContract = null, energyConsumedNet = null).historyEnergy(batteryCapacityKwh = 60.0).energyKwh)
    }
}

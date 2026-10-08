package com.matelink.data.sync

import com.matelink.data.api.models.*
import com.matelink.domain.analytics.*
import org.junit.Assert.*
import org.junit.Test

class EnergyPersistenceRoundTripTest {
    private val start = "2026-10-08T01:00:00Z"
    private val end = "2026-10-08T01:00:10Z"
    private fun drive() = DriveData(4, startDate = start, endDate = end, source = "teslamate_archive",
        batteryDetails = DriveBatteryDetails(0, null), odometerDetails = DriveOdometerDetails(distance = 1.0),
        energyConsumedNet = 4.0)
    @Test fun detailEnergyReplacesTheOldJsonAndScalarsTogether() {
        val old = drive().toSyncSummary(90)!!
        val detail = drive().copy(energyConsumedNet = -0.2).asCachedDetail()
        val next = old.withResolvedDriveEnergy(detail, detail.resolveDriveEnergy())
        val decoded = next.toAnalysisDriveData()
        assertEquals(-0.2, next.energyConsumed!!, 0.0)
        assertEquals(-0.2, decoded.netEnergyKwh!!, 0.0)
        assertEquals(-200.0, decoded.efficiencyWhKm!!, 1e-12)
        assertEquals(0, decoded.startBatteryLevel)
        assertNull(decoded.endBatteryLevel)
        assertEquals("api_reported_net", decoded.energyContract!!.netEnergy!!.method)
    }
    @Test fun oldPowerEstimateCannotBePromotedToAnApiReportWhenDetailHasNoEnergy() {
        val old = drive().toSyncSummary(90)!!.copy(energySource = "power_samples")
        val detail = drive().copy(energyConsumedNet = null).asCachedDetail()
        val next = old.withResolvedDriveEnergy(detail, detail.resolveDriveEnergy())
        assertNull(next.energyConsumed)
        assertNull(next.toAnalysisDriveData().netEnergyKwh)
    }
    @Test fun idAndSourceMismatchesCannotOverwriteAnExistingRow() {
        val old = drive().toSyncSummary(90)!!
        listOf(drive().copy(driveId = 5), drive().copy(source = "telemetry_mqtt")).forEach { wrong ->
            val detail = wrong.asCachedDetail()
            try { old.withResolvedDriveEnergy(detail, detail.resolveDriveEnergy()); fail("mismatch accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun wholeWindowZeroRemainsAReportedZeroInCache() {
        val summary = drive().copy(energyConsumedNet = 0.0).toSyncSummary(90)!!
        assertEquals("api", summary.energySource)
        assertEquals(0.0, summary.toAnalysisDriveData().netEnergyKwh!!, 0.0)
    }
    @Test fun unknownChargeEnergyAndValidZeroSocSurviveScalarPlaceholders() {
        val row = ChargeData(9, startDate = start, endDate = end,
            batteryDetails = ChargeBatteryDetails(0, null), chargeEnergyAdded = null).toSyncSummary(90)!!
        assertEquals(0.0, row.energyAdded, 0.0)
        assertNull(row.toAnalysisChargeData().batteryInputKwh)
        assertEquals(0, row.toAnalysisChargeData().startBatteryLevel)
        assertNull(row.toAnalysisChargeData().endBatteryLevel)
    }
    @Test fun driveNetEnergyIsNeverUploadedAsChargingEnergy() {
        val row = drive().toSyncSummary(90)!!
        assertNull(row.toImportSession("drive")!!.energyAdded)
    }
}

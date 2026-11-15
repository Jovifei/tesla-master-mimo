package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.DriveEnergySource
import com.matelink.domain.analytics.asCachedDetail
import com.matelink.domain.analytics.resolveDriveEnergy
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.analytics.withResolvedDriveEnergy
import org.junit.Assert.*
import org.junit.Test

class DriveListEnergyConsumerTest {
    private fun drive(energy: Double?) = DriveData(4,
        startDate = "2026-10-12T00:00:00Z", endDate = "2026-10-12T00:01:00Z",
        source = "teslamate_archive", odometerDetails = DriveOdometerDetails(distance = 2.0),
        energyConsumedNet = energy)

    @Test fun listCardShowsTotalNetEnergyAlongsideEfficiency() {
        val drive = drive(0.5)
        val card = drive.historyEnergy(drive.toSyncSummary(-7)!!.toQualifiedHistoryMetrics())
        assertEquals(0.5, card.energyKwh ?: Double.NaN, 1e-12)
        assertEquals(250.0, card.efficiencyWhKm ?: Double.NaN, 1e-12)
    }

    @Test fun listCardPreservesSignedNetEnergyAndEfficiency() {
        val drive = drive(-0.5)
        val card = drive.historyEnergy(drive.toSyncSummary(-7)!!.toQualifiedHistoryMetrics())
        assertEquals(-0.5, card.energyKwh ?: Double.NaN, 1e-12)
        assertEquals(-250.0, card.efficiencyWhKm ?: Double.NaN, 1e-12)
    }

    @Test fun listCardDistinguishesRealZeroFromMissingEnergy() {
        assertEquals(0.0, drive(0.0).historyEnergy().energyKwh ?: Double.NaN, 0.0)
        assertNull(drive(null).historyEnergy().energyKwh)
    }

    @Test fun detailSavedToRoomRestoresSocCapacityEstimateInListAndOfflineReload() {
        val initialDrive = DriveData(
            driveId = 101,
            startDate = "2026-10-12T08:00:00Z",
            endDate = "2026-10-12T08:30:00Z",
            source = "telemetry_mqtt",
            odometerDetails = DriveOdometerDetails(distance = 20.0),
            batteryDetails = com.matelink.data.api.models.DriveBatteryDetails(startBatteryLevel = 80, endBatteryLevel = 75),
            energyConsumedNet = null
        )
        val initialSummary = initialDrive.toSyncSummary(carId = 1)!!

        val detail = com.matelink.data.api.models.DriveDetail(
            driveId = 101,
            startDate = "2026-10-12T08:00:00Z",
            endDate = "2026-10-12T08:30:00Z",
            source = "telemetry_mqtt",
            odometerDetails = DriveOdometerDetails(distance = 20.0),
            batteryDetails = com.matelink.data.api.models.DriveBatteryDetails(startBatteryLevel = 80, endBatteryLevel = 75),
            energyConsumedNet = null
        )

        // 1. Detail resolves energy using vehicle capacity (78.4 kWh for Long Range)
        val resolved = detail.resolveDriveEnergy(batteryCapacityKwh = 78.4)
        assertEquals(DriveEnergySource.SOC_CAPACITY_ESTIMATE, resolved.estimate.source)
        assertEquals(3.92, resolved.estimate.energyKwh ?: Double.NaN, 1e-12)

        // 2. Room database enrichment with withResolvedDriveEnergy
        val enrichedSummary = initialSummary.withResolvedDriveEnergy(detail, resolved)

        // 3. Offline list reload parses summary into analysis drive
        val reloadedDrive = enrichedSummary.toAnalysisDriveData()
        val reloadedMetrics = enrichedSummary.toQualifiedHistoryMetrics()

        assertEquals(3.92, reloadedDrive.netEnergyKwh ?: Double.NaN, 1e-12)
        assertEquals(196.0, reloadedDrive.efficiencyWhKm ?: Double.NaN, 1e-12)
        assertEquals("soc_capacity_estimate", reloadedMetrics.source)

        // 4. UI presentation on DrivesScreen card
        val card = reloadedDrive.historyEnergy(reloadedMetrics)
        assertEquals(3.92, card.energyKwh ?: Double.NaN, 1e-12)
        assertEquals(196.0, card.efficiencyWhKm ?: Double.NaN, 1e-12)

        // 5. Offline detail reopen (cached.toAnalysisDriveData().asCachedDetail().resolveDriveEnergy())
        val offlineDetail = reloadedDrive.asCachedDetail().resolveDriveEnergy()
        assertEquals(DriveEnergySource.SOC_CAPACITY_ESTIMATE, offlineDetail.estimate.source)
        assertEquals(3.92, offlineDetail.estimate.energyKwh ?: Double.NaN, 1e-12)
        assertEquals(196.0, offlineDetail.estimate.efficiencyWhKm ?: Double.NaN, 1e-12)
    }

    @Test fun listCardDirectlyEstimatesEnergyFromSocWhenUnenriched() {
        val driveWithSocChange = DriveData(
            driveId = 102,
            startDate = "2026-10-12T21:10:00+08:00",
            endDate = "2026-10-12T21:20:00+08:00",
            source = "telemetry_mqtt",
            odometerDetails = DriveOdometerDetails(distance = 3.3),
            batteryDetails = com.matelink.data.api.models.DriveBatteryDetails(startBatteryLevel = 65, endBatteryLevel = 64),
            durationMin = 10,
            energyConsumedNet = null
        )
        // Resolves via Tier 4 directly on list card using 60.0 kWh standard capacity
        val card = driveWithSocChange.historyEnergy(metrics = null, batteryCapacityKwh = 60.0)
        assertEquals(0.60, card.energyKwh ?: Double.NaN, 1e-12)
        assertEquals(181.818, card.efficiencyWhKm ?: Double.NaN, 1e-2)
        assertEquals(DriveDetailEnergySource.SOC_CAPACITY_ESTIMATE, card.source)
    }

    @Test fun unchangedIntegerSocWithoutEnergyEvidenceRemainsUnknown() {
        val shortTrip = DriveData(
            driveId = 103,
            startDate = "2026-10-13T08:49:00+08:00",
            endDate = "2026-10-13T08:52:00+08:00",
            source = "telemetry_mqtt",
            odometerDetails = DriveOdometerDetails(distance = 0.8),
            batteryDetails = com.matelink.data.api.models.DriveBatteryDetails(startBatteryLevel = 60, endBatteryLevel = 60),
            durationMin = 3,
            energyConsumedNet = null
        )
        val card = shortTrip.historyEnergy(metrics = null, batteryCapacityKwh = 60.0)
        assertNull(card.energyKwh)
        assertNull(card.efficiencyWhKm)
        assertNull(card.source)
    }
}

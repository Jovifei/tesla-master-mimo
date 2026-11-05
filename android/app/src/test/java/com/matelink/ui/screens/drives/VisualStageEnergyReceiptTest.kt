package com.matelink.ui.screens.drives

import com.matelink.data.api.models.*
import com.matelink.domain.analytics.*
import org.junit.Assert.*
import org.junit.Test

/** Actual list/detail consumers. Synthetic records, no Owner data or provider requests. */
class VisualStageEnergyReceiptTest {
    private fun row(source: String) = DriveData(81,
        startDate = "2026-11-01T01:00:00Z", endDate = "2026-11-01T01:10:00Z",
        source = source, sourceInstanceId = "synthetic-A", sourceId = "receipt-81",
        qualityState = "observed", energyConsumedNet = 8.0,
        odometerDetails = DriveOdometerDetails(distance = 2.0), lengthUnit = "km")

    @Test fun uncontractedFleetScalarCannotBecomeAListOrDetailMeasurement() {
        for (source in listOf("telemetry_mqtt", "fleet_api")) {
            val raw = row(source)
            assertNull(raw.historyEnergy().energyKwh)
            assertNull(raw.asCachedDetail().resolveDriveEnergy().estimate.energyKwh)
            assertEquals(8.0, raw.energyConsumedNet!!, 0.0)
        }
    }

    @Test fun explicitUnmeasuredContractNeverResurrectsOldScalarWithoutIndependentEvidence() {
        for (source in listOf("telemetry_mqtt", "fleet_api")) {
            val raw = row(source)
            val unknown = raw.copy(energyContract = EnergyContract(netEnergy = EnergyMetric(
                source = source, quality = "unknown", reason = "no_verified_driving_net_energy",
                startDate = raw.startDate, endDate = raw.endDate)))
            assertNull(unknown.historyEnergy().energyKwh)
            assertNull(unknown.asCachedDetail().resolveDriveEnergy().estimate.energyKwh)
        }
    }

    @Test fun conflictedRecordIdentityCannotAcquireAnEstimatedValueEither() {
        val conflicted = row("telemetry_mqtt").copy(sourceRecordId = "different-receipt",
            batteryDetails = DriveBatteryDetails(80, 79))
        assertTrue(conflicted.sourceRecordIdentity.conflicted)
        assertNull(conflicted.historyEnergy(model = "Y", trimBadging = "Long Range AWD").energyKwh)
        val detail = conflicted.asCachedDetail().resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD")
        assertNull(detail.estimate.energyKwh)
        assertEquals("source_record_conflict", detail.evidence.reason)
    }

    @Test fun independentSocEstimateIsStillLabeledAndNeverUsesOldScalar() {
        val raw = row("telemetry_mqtt").copy(batteryDetails = DriveBatteryDetails(80, 79))
        val detail = raw.asCachedDetail().resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD")
        assertEquals(0.784, detail.estimate.energyKwh!!, 1e-12)
        assertEquals("estimated", detail.evidence.quality)
        assertEquals("soc_capacity_estimate", detail.evidence.method)
        assertEquals(0.784, raw.historyEnergy(model = "Y", trimBadging = "Long Range AWD").energyKwh!!, 1e-12)
        assertEquals(8.0, raw.energyConsumedNet!!, 0.0)
    }

    @Test fun qualifiedLegacyZeroStillDisplaysAsZero() {
        val zero = row("teslamate_archive").copy(energyConsumedNet = 0.0)
        assertEquals(0.0, zero.historyEnergy().energyKwh!!, 0.0)
        assertEquals(0.0, zero.asCachedDetail().resolveDriveEnergy().estimate.energyKwh!!, 0.0)
    }
}

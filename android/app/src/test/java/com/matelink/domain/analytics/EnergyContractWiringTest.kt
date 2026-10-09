package com.matelink.domain.analytics

import com.matelink.data.api.models.*
import org.junit.Assert.*
import org.junit.Test

class EnergyContractWiringTest {
    private val start = "2026-10-08T01:00:00Z"
    private val end = "2026-10-08T01:00:10Z"
    private fun metric(value: Double?) = EnergyMetric(valueKwh = value, method = "api_reported_net",
        measurementPoint = "reported_net", source = "teslamate_archive", quality = "reported", startDate = start, endDate = end)
    private fun drive(contract: EnergyContract?) = DriveData(7, startDate = start, endDate = end,
        odometerDetails = DriveOdometerDetails(distance = 10.0), energyConsumedNet = 99.0,
        consumptionNet = 9999.0, energyContract = contract)

    @Test fun apiRoomJsonAndCachedDetailPreserveSignedValuesAndProvenance() {
        listOf(0.0, -0.5, 2.0).forEach { energy ->
            val input = drive(EnergyContract(netEnergy = metric(energy)))
            val decoded = HistorySummaryEvidenceCodec.decodeDrive(HistorySummaryEvidenceCodec.encode(input))!!
            assertEquals(input.energyContract, decoded.energyContract)
            assertEquals(energy, decoded.netEnergyKwh!!, 0.0)
            assertEquals(energy / 10.0 * 1000, decoded.efficiencyWhKm!!, 1e-12)
            val resolved = decoded.asCachedDetail().resolveDriveEnergy()
            assertEquals(energy, resolved.estimate.energyKwh!!, 0.0)
            assertEquals("reported_net", resolved.evidence.measurementPoint)
            assertEquals("reported", resolved.evidence.quality)
        }
    }
    @Test fun explicitUnknownOrNewVersionCannotResurrectAnOldNumericScalar() {
        listOf(EnergyContract(netEnergy = metric(null)), EnergyContract(version = 2, netEnergy = metric(1.0)),
            EnergyContract(netEnergy = metric(1.0).copy(quality = "legacy_unverified"))).forEach { contract ->
            val data = drive(contract)
            assertNull(data.netEnergyKwh)
            assertNull(data.efficiencyWhKm)
            assertNull(data.asCachedDetail().resolveDriveEnergy().estimate.energyKwh)
            assertNull(data.withQualifiedEnergy().energyConsumedNet)
        }
    }
    @Test fun wrongUnitsMeasurementPointOrWindowAreRejected() {
        listOf(metric(1.0).copy(unit = "Wh"), metric(1.0).copy(measurementPoint = "lifetime_discharge"),
            metric(1.0).copy(startDate = "2026-10-08T00:00:00Z"), metric(1.0).copy(source = ""),
            metric(Double.NaN)).forEach { bad -> assertNull(drive(EnergyContract(netEnergy = bad)).netEnergyKwh) }
    }
    @Test fun drivePowerEstimateIsNotRelabeledAsBatteryMeasurementAfterCaching() {
        val raw = DriveDetail(7, startDate = start, endDate = end, source = "teslamate_archive",
            odometerDetails = DriveOdometerDetails(distance = 1.0),
            positions = listOf(DrivePosition(date = start, power = 36.0), DrivePosition(date = end, power = 36.0)))
        val first = raw.resolveDriveEnergy()
        assertEquals("drive_power", first.evidence.measurementPoint)
        assertEquals("estimated", first.evidence.quality)
        val cached = drive(EnergyContract(netEnergy = first.evidence)).asCachedDetail().resolveDriveEnergy()
        assertEquals(DriveEnergySource.POWER_SAMPLES, cached.estimate.source)
        assertEquals(first.evidence, cached.evidence)
    }
    @Test fun inferredPowerEstimateKeepsActualTimestampProvenanceBySource() {
        val points = listOf(DrivePosition(date = start, power = 36.0),
            DrivePosition(date = end, power = 36.0))
        val mqtt = DriveDetail(19, startDate = start, endDate = end,
            source = "telemetry_mqtt", positions = points)
        val mqttEnergy = mqtt.resolveDriveEnergy()
        assertEquals(DriveEnergySource.POWER_SAMPLES, mqttEnergy.estimate.source)
        assertEquals(0.1, mqttEnergy.estimate.energyKwh!!, 1e-12)
        assertEquals("collector_received_at", mqttEnergy.evidence.timeBasis)
        assertEquals("estimated", mqttEnergy.evidence.quality)
        assertEquals("telemetry_mqtt", mqttEnergy.evidence.source)
        val archive = mqtt.copy(source = "teslamate_archive").resolveDriveEnergy()
        assertEquals("source_sample_time", archive.evidence.timeBasis)
        assertEquals("estimated", archive.evidence.quality)
        val untagged = mqtt.copy(source = null).resolveDriveEnergy()
        assertEquals("route_timestamp_unverified", untagged.evidence.timeBasis)
    }
    @Test fun partialSamplesRemainDiagnosticNotWholeDriveConsumption() {
        val detail = DriveDetail(7, startDate = start, endDate = "2026-10-08T01:01:00Z",
            positions = listOf(DrivePosition(date = start, power = 36.0), DrivePosition(date = end, power = 36.0)))
        val result = detail.resolveDriveEnergy()
        assertNull(result.estimate.energyKwh)
        assertNull(result.evidence.valueKwh)
        assertEquals(0.1, result.evidence.coveredEnergyKwh!!, 1e-12)
        assertEquals("unknown", result.evidence.quality)
    }
}

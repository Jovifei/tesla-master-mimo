package com.matelink.data.repository

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.local.dao.persistChargeRowsWithoutEvidenceLoss
import com.matelink.data.local.dao.persistDriveRowsWithoutEvidenceLoss
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.ui.screens.drives.toQualifiedHistoryMetrics
import com.matelink.domain.analytics.withDetailEvidence
import com.matelink.domain.analytics.withResolvedDriveEnergy
import com.matelink.domain.analytics.resolveDriveEnergy
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.analytics.toRawAnalysisChargeData
import com.matelink.domain.analytics.toRawAnalysisDriveData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Calls the same I/O-ported read/merge/upsert functions used by the actual
 * Room @Transaction methods. Storage is isolated in memory; no owner rows.
 * All fixtures synthetic, never natural Tesla Fleet or authentic API evidence.
 */
class RawHistoryEvidencePersistenceTest {
    private val start = "2026-10-08T01:00:00Z"
    private val end = "2026-10-08T01:30:00Z"

    private fun rawFleet(id: Int = 7) = DriveData(
        driveId = id, startDate = start, endDate = end,
        source = "telemetry_mqtt", qualityState = "observed",
        qualityReason = "telemetry_evidence",
        odometerDetails = DriveOdometerDetails(distance = 10.0),
        energyConsumedNet = 8.0, consumptionNet = 800.0,
        // Synthetic raw formatting may be unsuitable for display, yet the
        // original source envelope must remain byte-recoverable.
        startAddress = "synthetic 1°N", speedMax = 50
    )

    @Test fun actualSameIdDriveUpsertRetainsRawEightAcrossWeakAndOfflineRecovery() = runBlocking {
        val raw = rawFleet()
        val original = raw.toSyncSummary(30)!!.copy(
            // Pre-qualification historical scalar columns can contain 8.
            energyConsumed = 8.0, efficiency = 800.0, energySource = "api"
        )
        val originalJson = requireNotNull(original.apiEvidence)
        assertEquals(8.0, HistorySummaryEvidenceCodec.decodeDrive(originalJson)!!.energyConsumedNet!!, 0.0)
        val rows = mutableMapOf((30 to 7) to original)
        suspend fun save(input: List<DriveSummary>) = persistDriveRowsWithoutEvidenceLoss(
            input, { car, id -> rows[car to id] },
            { row -> rows[row.carId to row.driveId] = row }
        )
        val weak = raw.copy(energyConsumedNet = null, consumptionNet = null,
            qualityState = "incomplete", qualityReason = "local_import_unverified",
            source = "local_import").toSyncSummary(30)!!
        save(listOf(weak))
        val stored = rows[30 to 7]!!
        assertEquals(1, rows.size)
        assertNull(stored.energyConsumed) // Numeric analysis column is not a source receipt.
        assertNull(stored.efficiency)
        assertEquals(originalJson, stored.apiEvidence) // Raw bytes remain recoverable.
        assertEquals(8.0, stored.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        assertEquals("synthetic 1°N", stored.toRawAnalysisDriveData().startAddress)
        assertNull(stored.toAnalysisDriveData().startAddress)
        assertEquals(800.0, stored.toRawAnalysisDriveData().consumptionNet!!, 0.0)
        assertNull(stored.toAnalysisDriveData().netEnergyKwh)
        assertNull(stored.toQualifiedHistoryMetrics().energyKwh)
        assertNull(stored.toQualifiedHistoryMetrics().source)
        assertEquals("telemetry_mqtt", stored.toAnalysisDriveData().source)
        assertEquals("observed", stored.qualityState)
        assertEquals(50, stored.speedMax)
        val offline = UnifiedHistoryRepository.mergeDrives(
            emptyList(), listOf(stored.toRawAnalysisDriveData())
        ).single()
        assertNull(offline.energyConsumedNet)
        assertEquals("telemetry_mqtt", offline.source)
        assertNull(offline.startAddress) // raw source formatting stays in Room only
        // Replaying a weak row must not alter the original JSON or create rows.
        save(listOf(weak))
        assertEquals(1, rows.size)
        assertEquals(originalJson, rows[30 to 7]!!.apiEvidence)
        assertNull(rows[30 to 7]!!.energyConsumed)
    }

    @Test fun sameIdWeakObservedRemotePreservesRawAndDoesNotLaunderNumericEnergy() = runBlocking {
        val raw = rawFleet()
        val first = raw.toSyncSummary(30)!!
        val rows = mutableMapOf((30 to 7) to first)
        val incomplete = raw.copy(energyConsumedNet = null, consumptionNet = null,
            speedMax = 51).toSyncSummary(30)!!
        persistDriveRowsWithoutEvidenceLoss(
            listOf(incomplete), { c, id -> rows[c to id] },
            { row -> rows[row.carId to row.driveId] = row })
        val stored = rows[30 to 7]!!
        assertEquals(1, rows.size)
        assertNull(stored.energyConsumed)
        assertEquals(8.0, stored.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        assertNull(stored.toAnalysisDriveData().netEnergyKwh)
        assertEquals("observed", stored.qualityState)
        assertEquals("telemetry_mqtt", stored.toRawAnalysisDriveData().source)
        assertEquals(51, stored.speedMax)
    }

    @Test fun sameNumericDriveIdCannotMergeAcrossCarsAndValidSignedProofSurvives() = runBlocking {
        fun verified(value: Double) = rawFleet().copy(
            energyConsumedNet = value,
            energyContract = EnergyContract(netEnergy = EnergyMetric(
                valueKwh = value, source = "telemetry_mqtt",
                method = "drive_power_integral", measurementPoint = "drive_power",
                quality = "estimated", timeBasis = "collector_received_at",
                startDate = start, endDate = end,
                coverageKind = "time", coverageRatio = 1.0,
                coverageSeconds = 1800.0
            ))
        )
        val rows = mutableMapOf<Pair<Int, Int>, DriveSummary>()
        val input = listOf(
            verified(0.0).toSyncSummary(30)!!,
            verified(-0.5).toSyncSummary(31)!!
        )
        persistDriveRowsWithoutEvidenceLoss(
            input, { c, id -> rows[c to id] },
            { row -> rows[row.carId to row.driveId] = row })
        assertEquals(2, rows.size)
        assertEquals(0.0, rows[30 to 7]!!.toAnalysisDriveData().netEnergyKwh!!, 0.0)
        assertEquals(-0.5, rows[31 to 7]!!.toAnalysisDriveData().netEnergyKwh!!, 0.0)
        assertEquals(-0.5, rows[31 to 7]!!.toQualifiedHistoryMetrics().energyKwh!!, 0.0)
        assertEquals("power_samples", rows[31 to 7]!!.toQualifiedHistoryMetrics().source)
        assertEquals("power_samples", rows[31 to 7]!!.energySource)
        assertEquals(-0.5, rows[31 to 7]!!.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
    }

    @Test fun corruptOrFutureEnvelopeNeverMakesLegacyScalarTrusted() {
        val future = """{"_matelink_local_evidence_version":2,"raw_json":"ignored"}"""
        val malformed = """{"_matelink_local_evidence_version":1,"raw_json":42}"""
        for (encoded in listOf(future, malformed)) {
            val cachedDrive = rawFleet().toSyncSummary(30)!!.copy(
                apiEvidence = encoded, energyConsumed = 8.0,
                efficiency = 800.0, energySource = "api")
            assertNull(cachedDrive.toAnalysisDriveData().netEnergyKwh)
            assertNull(cachedDrive.toRawAnalysisDriveData().energyConsumedNet)
            val cachedCharge = ChargeData(9, startDate = start, endDate = end,
                source = "telemetry_mqtt", chargeEnergyAdded = 12.0)
                .toSyncSummary(30)!!.copy(apiEvidence = encoded, energyAdded = 12.0)
            assertNull(cachedCharge.toAnalysisChargeData().batteryInputKwh)
            assertNull(cachedCharge.toRawAnalysisChargeData().chargeEnergyAdded)
        }
    }

    @Test fun sameIdChargeUpsertKeepsUnqualifiedRawPositiveOrZeroButNotDisplayed() = runBlocking {
        for (rawValue in listOf(0.0, 12.0)) {
            val raw = ChargeData(9, startDate = start, endDate = end,
                source = "telemetry_mqtt", qualityState = "observed",
                chargeEnergyAdded = rawValue, chargeEnergyUsed = rawValue)
            val original = raw.toSyncSummary(30)!!.copy(energyAdded = rawValue)
            val bytes = original.apiEvidence
            val rows = mutableMapOf((30 to 9) to original)
            val weak = raw.copy(chargeEnergyAdded = null, chargeEnergyUsed = null,
                qualityState = "incomplete", source = "local_import").toSyncSummary(30)!!
            persistChargeRowsWithoutEvidenceLoss(
                listOf(weak), { c, id -> rows[c to id] },
                { row -> rows[row.carId to row.chargeId] = row })
            val stored = rows[30 to 9]!!
            assertEquals(1, rows.size)
            assertEquals(bytes, stored.apiEvidence)
            assertEquals(rawValue, stored.toRawAnalysisChargeData().chargeEnergyAdded!!, 0.0)
            assertNull(stored.toAnalysisChargeData().batteryInputKwh)
            assertNull(stored.toAnalysisChargeData().inputEnergyKwh)
            assertEquals("telemetry_mqtt", stored.toAnalysisChargeData().source)
        }
    }

    @Test fun missingDetailKeepsPriorProvenWindowButExplicitUnknownRevokesDisplay() {
        val source = rawFleet()
        val proof = EnergyContract(netEnergy = EnergyMetric(
            valueKwh = 8.0, source = "telemetry_mqtt",
            method = "drive_power_integral", measurementPoint = "drive_power",
            quality = "estimated", timeBasis = "collector_received_at",
            startDate = start, endDate = end, coverageKind = "time",
            coverageRatio = 1.0, coverageSeconds = 1800.0
        ))
        val first = source.copy(energyContract = proof).toSyncSummary(30)!!
        val missing = DriveDetail(7, startDate = start, endDate = end,
            source = "telemetry_mqtt", positions = emptyList())
        val kept = first.withResolvedDriveEnergy(missing, missing.resolveDriveEnergy())
        assertEquals(8.0, kept.toAnalysisDriveData().netEnergyKwh!!, 0.0)
        assertEquals(8.0, kept.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        assertEquals("power_samples", kept.energySource)
        val unknown = missing.copy(energyContract = EnergyContract(netEnergy =
            EnergyMetric(quality = "unknown", source = "telemetry_mqtt",
                method = "drive_power_integral", measurementPoint = "drive_power",
                startDate = start, endDate = end)))
        val rejected = kept.withResolvedDriveEnergy(unknown, unknown.resolveDriveEnergy())
        assertNull(rejected.toAnalysisDriveData().netEnergyKwh)
        assertEquals(8.0, rejected.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        val shifted = missing.copy(endDate = "2026-10-08T01:31:00Z")
        assertNull(first.withResolvedDriveEnergy(
            shifted, shifted.resolveDriveEnergy()).toAnalysisDriveData().netEnergyKwh)
    }

    @Test fun detailEnrichmentUnknownPreservesHistoricalRawDriveJson() {
        val first = rawFleet().toSyncSummary(30)!!
        val oldRaw = first.toRawAnalysisDriveData()
        val details = DriveDetail(driveId = 7, startDate = start, endDate = end,
            source = "telemetry_mqtt", energyConsumedNet = null, positions = emptyList())
        val enriched = first.withResolvedDriveEnergy(details, details.resolveDriveEnergy())
        assertNull(enriched.energyConsumed)
        assertNull(enriched.toAnalysisDriveData().netEnergyKwh)
        assertEquals(8.0, enriched.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        assertEquals("synthetic 1°N", enriched.toRawAnalysisDriveData().startAddress)
        assertNull(enriched.toAnalysisDriveData().startAddress)
        assertEquals("", enriched.startAddress) // Only presentation column is cleaned
        assertEquals(oldRaw.source, enriched.toRawAnalysisDriveData().source)
        assertNull(enriched.toRawAnalysisDriveData().energyContract)
        assertEquals("unknown",
            HistorySummaryEvidenceCodec.detailContract(enriched.apiEvidence)?.netEnergy?.quality)
        assertEquals(first.apiEvidence, HistorySummaryEvidenceCodec.sourceJson(enriched.apiEvidence))
    }

    @Test fun detailEnrichmentUnknownPreservesHistoricalRawChargeJson() {
        val raw = ChargeData(9, startDate = start, endDate = end,
            source = "telemetry_mqtt", qualityState = "observed",
            chargeEnergyAdded = 12.0, chargeEnergyUsed = 13.0,
            cost = -5.0)
        val first = raw.toSyncSummary(30)!!
        val detail = ChargeDetail(chargeId = 9, source = "telemetry_mqtt",
            startDate = start, endDate = end,
            chargeEnergyAdded = null, chargeEnergyUsed = null)
        val enriched = first.withDetailEvidence(detail)
        assertEquals(12.0, enriched.toRawAnalysisChargeData().chargeEnergyAdded!!, 0.0)
        assertEquals(13.0, enriched.toRawAnalysisChargeData().chargeEnergyUsed!!, 0.0)
        assertEquals(-5.0, enriched.toRawAnalysisChargeData().cost!!, 0.0)
        assertNull(enriched.cost) // Raw negative cost is not a verified charge price.
        assertNull(enriched.toAnalysisChargeData().batteryInputKwh)
        assertNull(enriched.toAnalysisChargeData().inputEnergyKwh)
        assertEquals("telemetry_mqtt", enriched.toRawAnalysisChargeData().source)
    }

    @Test fun detailPowerIntegralKeepsOpaqueRawEightAndPersistsIndependentEstimatedOne() = runBlocking {
        // Exact 10-second, 360 kW signed power samples yield 1 kWh. This is
        // synthetic source physics, never provider-reported battery kWh.
        val tinyEnd = "2026-10-08T01:00:10Z"
        val originalData = rawFleet().copy(endDate = tinyEnd)
        val initial = originalData.toSyncSummary(30)!!
        val originalBytes = requireNotNull(initial.apiEvidence).dropLast(1) +
            ""","opaque_future":{"byte_order":"keep_this_original_value"}}"""
        val stored = initial.copy(apiEvidence = originalBytes)
        val detail = DriveDetail(driveId = 7, startDate = start, endDate = tinyEnd,
            source = "telemetry_mqtt", energyConsumedNet = null,
            odometerDetails = DriveOdometerDetails(distance = 10.0),
            positions = listOf(
                com.matelink.data.api.models.DrivePosition(date = start, power = 360.0),
                com.matelink.data.api.models.DrivePosition(date = tinyEnd, power = 360.0)
            ))
        val computed = detail.resolveDriveEnergy()
        assertEquals(1.0, computed.estimate.energyKwh!!, 1e-12)
        assertEquals("estimated", computed.evidence.quality)
        val enriched = stored.withResolvedDriveEnergy(detail, computed)
        assertEquals(originalBytes, HistorySummaryEvidenceCodec.sourceJson(enriched.apiEvidence))
        assertEquals(8.0, enriched.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        assertEquals(1.0, enriched.toAnalysisDriveData().netEnergyKwh!!, 1e-12)
        assertEquals("power_samples", enriched.energySource)
        assertEquals("estimated",
            HistorySummaryEvidenceCodec.detailContract(enriched.apiEvidence)?.netEnergy?.quality)
        assertNull(HistorySummaryEvidenceCodec.detailRawNet(enriched.apiEvidence))
        val rows = mutableMapOf((30 to 7) to stored)
        suspend fun upsert(row: DriveSummary) = persistDriveRowsWithoutEvidenceLoss(
            listOf(row), { car, id -> rows[car to id] },
            { saved -> rows[saved.carId to saved.driveId] = saved })
        upsert(enriched)
        val saved = rows[30 to 7]!!
        assertEquals(1, rows.size)
        assertEquals(originalBytes, HistorySummaryEvidenceCodec.sourceJson(saved.apiEvidence))
        assertEquals(8.0, saved.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        assertEquals(1.0, saved.toAnalysisDriveData().netEnergyKwh!!, 1e-12)
        // Replaying a weaker old list payload cannot replace the derived
        // sidecar or manufacture a reported energy measurement.
        upsert(originalData.copy(energyConsumedNet = null,
            source = "local_import", qualityState = "incomplete").toSyncSummary(30)!!)
        assertEquals(1.0, rows[30 to 7]!!.toAnalysisDriveData().netEnergyKwh!!, 1e-12)
        assertEquals(originalBytes, HistorySummaryEvidenceCodec.sourceJson(rows[30 to 7]!!.apiEvidence))
        assertEquals(8.0, rows[30 to 7]!!.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)

        val explicitUnknown = detail.copy(energyContract = EnergyContract(
            netEnergy = EnergyMetric(quality = "unknown", source = "telemetry_mqtt",
                method = "drive_power_integral", measurementPoint = "drive_power",
                startDate = start, endDate = tinyEnd)
        ))
        val overridden = saved.withResolvedDriveEnergy(
            explicitUnknown, explicitUnknown.resolveDriveEnergy())
        assertNull(overridden.energyConsumed)
        assertNull(overridden.toAnalysisDriveData().netEnergyKwh)
        assertEquals(originalBytes, HistorySummaryEvidenceCodec.sourceJson(overridden.apiEvidence))
    }

    @Test fun chargeDetailContractHasIndependentBatteryProofAndKeepsOpaqueRawScalar() = runBlocking {
        val raw = ChargeData(9, startDate = start, endDate = end,
            source = "telemetry_mqtt", qualityState = "observed",
            chargeEnergyAdded = 12.0, chargeEnergyUsed = 13.0)
        val initial = raw.toSyncSummary(30)!!
        val originalJson = requireNotNull(initial.apiEvidence).dropLast(1) +
            ""","extra_source_field":{"value":"do_not_erase"}}"""
        val stored = initial.copy(apiEvidence = originalJson)
        val confirmed = EnergyContract(batteryInput = EnergyMetric(
            valueKwh = 2.0, source = "telemetry_mqtt",
            method = "session_counter_delta", measurementPoint = "battery_input",
            quality = "reported", coverageKind = "endpoints",
            coverageRatio = 1.0, startDate = start, endDate = end))
        val detail = ChargeDetail(9, startDate = start, endDate = end,
            source = "telemetry_mqtt", chargeEnergyAdded = null,
            chargeEnergyUsed = null, energyContract = confirmed)
        val enriched = stored.withDetailEvidence(detail)
        assertEquals(originalJson, HistorySummaryEvidenceCodec.sourceJson(enriched.apiEvidence))
        assertEquals(12.0, enriched.toRawAnalysisChargeData().chargeEnergyAdded!!, 0.0)
        assertEquals(13.0, enriched.toRawAnalysisChargeData().chargeEnergyUsed!!, 0.0)
        assertEquals(2.0, enriched.toAnalysisChargeData().batteryInputKwh!!, 0.0)
        assertEquals(2.0, enriched.energyAdded, 0.0)
        val rows = mutableMapOf((30 to 9) to stored)
        persistChargeRowsWithoutEvidenceLoss(listOf(enriched),
            { car, id -> rows[car to id] },
            { saved -> rows[saved.carId to saved.chargeId] = saved })
        assertEquals(1, rows.size)
        assertEquals(originalJson, HistorySummaryEvidenceCodec.sourceJson(rows[30 to 9]!!.apiEvidence))
        assertEquals(12.0, rows[30 to 9]!!.toRawAnalysisChargeData().chargeEnergyAdded!!, 0.0)
        assertEquals(2.0, rows[30 to 9]!!.toAnalysisChargeData().batteryInputKwh!!, 0.0)
    }

    @Test fun actualCompiledDetailRoundTripRetainsDistanceSocAddressSpeedAndRawBytes() = runBlocking {
        val detailEnd = "2026-10-08T01:00:10Z"
        val raw = rawFleet().copy(endDate = detailEnd,
            startAddress = "old raw", endAddress = "raw end",
            odometerDetails = DriveOdometerDetails(distance = 1.0),
            batteryDetails = null, speedMax = 11, energyConsumedNet = 8.0)
        val row = raw.toSyncSummary(30)!!
        val immutable = requireNotNull(row.apiEvidence).dropLast(1) +
            ""","opaque":{"independent":"retain_verbatim"}}"""
        val original = row.copy(apiEvidence = immutable)
        val detail = DriveDetail(7, startDate = start, endDate = detailEnd,
            source = "telemetry_mqtt", startAddress = "new address",
            endAddress = "new destination", speedMax = 92, speedAvg = 45.0,
            odometerDetails = DriveOdometerDetails(distance = 2.0),
            batteryDetails = com.matelink.data.api.models.DriveBatteryDetails(80, 70),
            positions = listOf(
                com.matelink.data.api.models.DrivePosition(date = start, power = 360.0),
                com.matelink.data.api.models.DrivePosition(date = detailEnd, power = 360.0)
            ))
        val resolved = detail.resolveDriveEnergy()
        assertEquals(1.0, resolved.estimate.energyKwh!!, 1e-12)
        val direct = original.withResolvedDriveEnergy(detail, resolved)
        assertEquals(2.0, direct.distance, 0.0)
        assertEquals(80, direct.startBatteryLevel)
        assertEquals(70, direct.endBatteryLevel)
        assertEquals(500.0, direct.efficiency!!, 1e-12)
        assertEquals(92, direct.speedMax)
        assertEquals(1.0, direct.energyConsumed!!, 1e-12)
        fun assertDisplay(record: DriveSummary) {
            val current = record.toAnalysisDriveData()
            assertEquals(2.0, current.distance!!, 0.0)
            assertEquals(80, current.startBatteryLevel)
            assertEquals(70, current.endBatteryLevel)
            assertEquals("new address", current.startAddress)
            assertEquals("new destination", current.endAddress)
            assertEquals(92, current.speedMax)
            assertEquals(45.0, current.speedAvg!!, 0.0)
            assertEquals(1.0, current.netEnergyKwh!!, 1e-12)
            assertEquals(500.0, current.efficiencyWhKm!!, 1e-12)
            assertEquals(1.0, record.energyConsumed!!, 1e-12)
            assertEquals(2.0, record.distance, 0.0)
            assertEquals(80, record.startBatteryLevel)
            assertEquals(70, record.endBatteryLevel)
            assertEquals(500.0, record.efficiency!!, 1e-12)
            assertEquals(92, record.speedMax)
            assertEquals(immutable, HistorySummaryEvidenceCodec.sourceJson(record.apiEvidence))
            assertEquals(8.0, record.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        }
        assertDisplay(direct)
        val rows = mutableMapOf((30 to 7) to original)
        suspend fun save(record: DriveSummary) =
            persistDriveRowsWithoutEvidenceLoss(
                listOf(record), { car, id -> rows[car to id] },
                { value -> rows[value.carId to value.driveId] = value })
        save(direct)
        assertDisplay(rows[30 to 7]!!)
        // Weak same-ID refresh has no new provenance and cannot roll the
        // qualified DETAIL metadata back to raw 1 km / unknown SOC.
        val weak = raw.copy(energyConsumedNet = null,
            source = "local_import", qualityState = "incomplete",
            startAddress = null).toSyncSummary(30)!!
        save(weak)
        save(weak)
        assertEquals(1, rows.size)
        assertDisplay(rows[30 to 7]!!)
        val misplaced = rows[30 to 7]!!.copy(carId = 31)
        assertNull(misplaced.toAnalysisDriveData().netEnergyKwh)
        assertEquals(1.0, misplaced.toAnalysisDriveData().distance!!, 0.0)
        val movedWindow = rows[30 to 7]!!.copy(endDate = "2026-10-08T01:00:11Z")
        assertNull(movedWindow.toAnalysisDriveData().netEnergyKwh)
        assertEquals(1.0, movedWindow.toAnalysisDriveData().distance!!, 0.0)
        assertEquals(8.0, misplaced.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        val wrongSource = detail.copy(source = "local_import")
        try {
            direct.withResolvedDriveEnergy(wrongSource, wrongSource.resolveDriveEnergy())
            fail("cross-source detail accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun chargeDetailCurrentSocAddressAndQualifiedZeroSurviveSameIdRoomUpsert() = runBlocking {
        val raw = ChargeData(9, startDate = start, endDate = end,
            source = "telemetry_mqtt", qualityState = "observed",
            address = "old charge", batteryDetails = null,
            chargeEnergyAdded = 12.0)
        val initial = raw.toSyncSummary(30)!!
        val original = requireNotNull(initial.apiEvidence).dropLast(1) +
            ""","opaque":{"charger":"keep_original"}}"""
        val row = initial.copy(apiEvidence = original)
        val zero = EnergyContract(batteryInput = EnergyMetric(
            valueKwh = 0.0, source = "telemetry_mqtt", quality = "reported",
            method = "session_counter_delta", measurementPoint = "battery_input",
            coverageKind = "endpoints", coverageRatio = 1.0,
            startDate = start, endDate = end
        ))
        val detail = ChargeDetail(9, startDate = start, endDate = end,
            source = "telemetry_mqtt", address = "new charge",
            batteryDetails = com.matelink.data.api.models.ChargeBatteryDetails(80, 90),
            odometer = 123.0, latitude = 2.0, longitude = 3.0,
            energyContract = zero, cost = 0.0)
        val enriched = row.withDetailEvidence(detail)
        val saved = mergeStoredCharge(enriched, row)
        val restored = saved.toAnalysisChargeData()
        assertEquals("new charge", restored.address)
        assertEquals(80, restored.startBatteryLevel)
        assertEquals(90, restored.endBatteryLevel)
        assertEquals(123.0, restored.odometer!!, 0.0)
        assertEquals(2.0, restored.latitude!!, 0.0)
        assertEquals(3.0, restored.longitude!!, 0.0)
        assertEquals(0.0, restored.batteryInputKwh!!, 0.0)
        assertEquals(0.0, restored.cost!!, 0.0)
        assertEquals(80, saved.startBatteryLevel)
        assertEquals(90, saved.endBatteryLevel)
        assertEquals("new charge", saved.address)
        assertEquals(0.0, saved.energyAdded, 0.0)
        assertEquals(original, HistorySummaryEvidenceCodec.sourceJson(saved.apiEvidence))
        assertEquals(12.0, saved.toRawAnalysisChargeData().chargeEnergyAdded!!, 0.0)
        val stale = raw.copy(source = "local_import",
            chargeEnergyAdded = null).toSyncSummary(30)!!
        val again = mergeStoredCharge(stale, saved)
        assertEquals("new charge", again.toAnalysisChargeData().address)
        assertEquals(80, again.toAnalysisChargeData().startBatteryLevel)
        assertEquals(0.0, again.toAnalysisChargeData().batteryInputKwh!!, 0.0)
        assertEquals(original, HistorySummaryEvidenceCodec.sourceJson(again.apiEvidence))
        val copiedCar = saved.copy(carId = 31)
        assertNull(copiedCar.toAnalysisChargeData().batteryInputKwh)
        assertEquals("old charge", copiedCar.toAnalysisChargeData().address)
        val moved = saved.copy(endDate = "2026-10-08T01:31:00Z")
        assertNull(moved.toAnalysisChargeData().batteryInputKwh)
        val invalid = detail.copy(source = "local_import")
        try { saved.withDetailEvidence(invalid); fail("cross-source charge detail") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun forgedLocalSnapshotWithInvalidRawJsonCannotQualifyBatteryEnergy() {
        val old = rawFleet().toSyncSummary(30)!!
        val valid = EnergyContract(netEnergy = EnergyMetric(
            valueKwh = 1.0, source = "telemetry_mqtt",
            method = "drive_power_integral", measurementPoint = "drive_power",
            quality = "estimated", startDate = start, endDate = end,
            timeBasis = "collector_received_at",
            coverageKind = "time", coverageRatio = 1.0,
            coverageSeconds = 1800.0
        ))
        val detail = DriveDetail(7, startDate = start, endDate = end,
            source = "telemetry_mqtt", energyContract = valid)
        val good = old.withResolvedDriveEnergy(detail, detail.resolveDriveEnergy())
        assertEquals(1.0, good.toAnalysisDriveData().netEnergyKwh!!, 0.0)
        val wrapped = requireNotNull(good.apiEvidence)
        val rawValue = requireNotNull(HistorySummaryEvidenceCodec.sourceJson(wrapped))
        val escaped = com.squareup.moshi.Moshi.Builder().build()
            .adapter(String::class.java).toJson(rawValue)
        val invalidRaw = com.squareup.moshi.Moshi.Builder().build()
            .adapter(String::class.java).toJson("not-json")
        val damaged = wrapped.replace(escaped, invalidRaw)
        assertTrue(damaged != wrapped)
        val tampered = good.copy(apiEvidence = damaged)
        assertNull(tampered.toAnalysisDriveData().netEnergyKwh)
        assertNull(tampered.toRawAnalysisDriveData().energyConsumedNet)
    }

    @Test fun detailMetadataMayImproveWithoutManufacturingUnknownFleetEnergy() {
        val endShort = "2026-10-08T01:00:10Z"
        val raw = rawFleet().copy(endDate = endShort,
            odometerDetails = DriveOdometerDetails(distance = 1.0),
            batteryDetails = null, energyConsumedNet = 8.0)
        val original = raw.toSyncSummary(30)!!
        val detail = DriveDetail(7, startDate = start, endDate = endShort,
            source = "telemetry_mqtt",
            startAddress = "new safe address",
            odometerDetails = DriveOdometerDetails(distance = 2.0),
            batteryDetails = com.matelink.data.api.models.DriveBatteryDetails(0, null),
            speedMax = 90, positions = emptyList())
        val updated = original.withResolvedDriveEnergy(detail, detail.resolveDriveEnergy())
        assertNull(updated.energyConsumed)
        assertEquals(2.0, updated.distance, 0.0)
        assertEquals(0, updated.startBatteryLevel)
        val actual = updated.toAnalysisDriveData()
        assertEquals(2.0, actual.distance!!, 0.0)
        assertEquals(0, actual.startBatteryLevel)
        assertNull(actual.endBatteryLevel)
        assertEquals("new safe address", actual.startAddress)
        assertEquals(90, actual.speedMax)
        assertNull(actual.netEnergyKwh)
        assertNull(actual.efficiencyWhKm)
        assertEquals(8.0, updated.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        assertEquals(original.apiEvidence,
            HistorySummaryEvidenceCodec.sourceJson(updated.apiEvidence))
        val replay = mergeStoredDrive(raw.copy(energyConsumedNet = null,
            source = "local_import", qualityState = "incomplete").toSyncSummary(30)!!,
            updated)
        assertEquals(2.0, replay.toAnalysisDriveData().distance!!, 0.0)
        assertEquals(0, replay.toAnalysisDriveData().startBatteryLevel)
        assertNull(replay.toAnalysisDriveData().endBatteryLevel)
        assertNull(replay.toAnalysisDriveData().netEnergyKwh)
        assertEquals(original.apiEvidence,
            HistorySummaryEvidenceCodec.sourceJson(replay.apiEvidence))
    }

    @Test fun explicitUnknownContractDoesNotEraseOlderRawScalarOrClaimItAsVerified() = runBlocking {
        val original = rawFleet().toSyncSummary(30)!!
        val rows = mutableMapOf((30 to 7) to original)
        val unknown = rawFleet().copy(energyConsumedNet = null,
            energyContract = EnergyContract(netEnergy = EnergyMetric(
                quality = "unknown", method = "api_reported_net",
                measurementPoint = "reported_net", source = "telemetry_mqtt",
                startDate = start, endDate = end
            ))).toSyncSummary(30)!!
        persistDriveRowsWithoutEvidenceLoss(
            listOf(unknown), { c, id -> rows[c to id] },
            { row -> rows[row.carId to row.driveId] = row })
        val saved = rows[30 to 7]!!
        assertNull(saved.toAnalysisDriveData().netEnergyKwh)
        assertEquals("unknown", saved.toRawAnalysisDriveData().energyContract?.netEnergy?.quality)
        assertEquals(8.0, saved.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        assertEquals(1, rows.size)
    }
}

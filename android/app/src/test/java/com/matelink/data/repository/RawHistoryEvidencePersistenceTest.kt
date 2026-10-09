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
        speedMax = 50
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
        assertEquals(800.0, stored.toRawAnalysisDriveData().consumptionNet!!, 0.0)
        assertNull(stored.toAnalysisDriveData().netEnergyKwh)
        assertEquals("telemetry_mqtt", stored.toAnalysisDriveData().source)
        assertEquals("observed", stored.qualityState)
        assertEquals(50, stored.speedMax)
        val offline = UnifiedHistoryRepository.mergeDrives(
            emptyList(), listOf(stored.toRawAnalysisDriveData())
        ).single()
        assertNull(offline.energyConsumedNet)
        assertEquals("telemetry_mqtt", offline.source)
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
        assertEquals("power_samples", rows[31 to 7]!!.energySource)
        assertEquals(-0.5, rows[31 to 7]!!.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
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

    @Test fun detailEnrichmentUnknownPreservesHistoricalRawDriveJson() {
        val first = rawFleet().toSyncSummary(30)!!
        val oldRaw = first.toRawAnalysisDriveData()
        val details = DriveDetail(driveId = 7, startDate = start, endDate = end,
            source = "telemetry_mqtt", energyConsumedNet = null, positions = emptyList())
        val enriched = first.withResolvedDriveEnergy(details, details.resolveDriveEnergy())
        assertNull(enriched.energyConsumed)
        assertNull(enriched.toAnalysisDriveData().netEnergyKwh)
        assertEquals(8.0, enriched.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        assertEquals(oldRaw.source, enriched.toRawAnalysisDriveData().source)
        assertEquals("unknown", enriched.toRawAnalysisDriveData().energyContract?.netEnergy?.quality)
    }

    @Test fun detailEnrichmentUnknownPreservesHistoricalRawChargeJson() {
        val raw = ChargeData(9, startDate = start, endDate = end,
            source = "telemetry_mqtt", qualityState = "observed",
            chargeEnergyAdded = 12.0, chargeEnergyUsed = 13.0)
        val first = raw.toSyncSummary(30)!!
        val detail = ChargeDetail(chargeId = 9, source = "telemetry_mqtt",
            startDate = start, endDate = end,
            chargeEnergyAdded = null, chargeEnergyUsed = null)
        val enriched = first.withDetailEvidence(detail)
        assertEquals(12.0, enriched.toRawAnalysisChargeData().chargeEnergyAdded!!, 0.0)
        assertEquals(13.0, enriched.toRawAnalysisChargeData().chargeEnergyUsed!!, 0.0)
        assertNull(enriched.toAnalysisChargeData().batteryInputKwh)
        assertNull(enriched.toAnalysisChargeData().inputEnergyKwh)
        assertEquals("telemetry_mqtt", enriched.toRawAnalysisChargeData().source)
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

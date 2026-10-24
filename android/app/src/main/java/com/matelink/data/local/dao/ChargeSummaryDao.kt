package com.matelink.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.matelink.data.local.HistorySourceReceiptArchive
import com.matelink.data.local.entity.HistoryDetailReceipt
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.data.repository.mergeStoredCharge
import com.matelink.data.api.models.ChargeDetail
import com.matelink.domain.analytics.acceptsDetail
import com.matelink.domain.analytics.withDetailEvidence
import com.matelink.data.local.entity.ChargeSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface ChargeSummaryDao {
    @Query("SELECT COUNT(*) FROM charges_summary WHERE carId = :carId AND qualityState != 'quarantined'")
    suspend fun countDiagnosticHistory(carId: Int): Int

    @Query("SELECT COUNT(*) FROM charges_summary WHERE carId = :carId AND qualityState IN ('observed', 'derived') AND energyAdded BETWEEN 0 AND 1.0e100 AND apiEvidence IS NOT NULL AND apiEvidence != ''")
    suspend fun countDiagnosticEnergy(carId: Int): Int


    /** Serialize read/merge/upsert without deleting older records or detail aggregates. */
    @Transaction
    suspend fun upsertPreservingEvidence(rows: List<ChargeSummary>) {
        persistChargeRowsWithoutEvidenceLoss(rows, ::getAnyForPersistence, ::upsert) { previous ->
            val original = HistorySourceReceiptArchive.capture(previous)
            if (original != null) archivePrior(original)
        }
    }

    @Transaction
    suspend fun mergeDetail(carId: Int, detail: ChargeDetail, ensureCurrent: suspend () -> Unit): Boolean {
        ensureCurrent()
        val newest = get(carId, detail.chargeId) ?: return false
        if (!newest.acceptsDetail(detail)) return false
        upsert(newest.withDetailEvidence(detail))
        ensureCurrent()
        return true
    }

    /** Retain a missing detail as visible unknown; rollback a concurrent identity change. */
    @Transaction
    suspend fun markDetailTerminal404(carId: Int, id: Int, expectedEvidence: String?, ensureCurrent: suspend () -> Unit) {
        ensureCurrent()
        val newest = get(carId, id) ?: return
        if (newest.apiEvidence != expectedEvidence) return
        upsert(newest.copy(apiEvidence = HistorySummaryEvidenceCodec.markDetailTerminal404(newest.apiEvidence, carId)))
        ensureCurrent()
    }

    /** No overwrite: collisions remain independent read-only historical receipts. */
    @Query("SELECT COUNT(*) FROM history_detail_receipts WHERE receiptId = :receiptId")
    suspend fun archiveExists(receiptId: String): Int

    @Query("SELECT COALESCE(SUM(encodedByteCount), 0) FROM history_detail_receipts WHERE state='archived_source_conflict'")
    suspend fun archiveUsedBytes(): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConflictArchive(receipt: HistoryDetailReceipt)

    suspend fun archivePrior(receipt: HistoryDetailReceipt) {
        if (archiveExists(receipt.receiptId) > 0) return
        val maxBytes = 64L * 1024 * 1024
        val used = archiveUsedBytes()
        check(receipt.encodedByteCount >= 0L && used <= maxBytes &&
            receipt.encodedByteCount <= maxBytes - used) {
            "history_conflict_archive_budget_exceeded"
        }
        insertConflictArchive(receipt)
    }

    // === CRUD Operations ===

    @Upsert
    suspend fun upsertAll(charges: List<ChargeSummary>)

    @Upsert
    suspend fun upsert(charge: ChargeSummary)

    @Query("""
        INSERT OR IGNORE INTO charges_summary (
            chargeId, carId, startDate, endDate, durationMin, address, latitude, longitude,
            energyAdded, energyUsed, cost, startBatteryLevel, endBatteryLevel,
            outsideTempAvg, odometer, apiEvidence, qualityState, qualityReason
        )
        SELECT chargeId, :targetCarId, startDate, endDate, durationMin, address, latitude, longitude,
            energyAdded, energyUsed, cost, startBatteryLevel, endBatteryLevel,
            outsideTempAvg, odometer, apiEvidence, qualityState, qualityReason
        FROM charges_summary WHERE carId = :legacyCarId
    """)
    suspend fun copyFromLegacy(legacyCarId: Int, targetCarId: Int)

    @Query("SELECT * FROM charges_summary WHERE chargeId = :chargeId AND carId = :carId AND qualityState != 'quarantined'")
    suspend fun get(carId: Int, chargeId: Int): ChargeSummary?

    /** Preserve quarantined raw receipts too; the presentation get() intentionally hides them. */
    @Query("SELECT * FROM charges_summary WHERE carId = :carId AND chargeId = :id LIMIT 1")
    suspend fun getAnyForPersistence(carId: Int, id: Int): ChargeSummary?

    @Query("SELECT * FROM charges_summary WHERE carId = :carId AND qualityState != 'quarantined' ORDER BY startDate DESC")
    fun observeAll(carId: Int): Flow<List<ChargeSummary>>

    @Query("SELECT * FROM charges_summary WHERE carId = :carId AND qualityState != 'quarantined' ORDER BY startDate ASC")
    suspend fun getAllForCar(carId: Int): List<ChargeSummary>

    @Query("SELECT MAX(chargeId) FROM charges_summary WHERE carId = :carId")
    suspend fun getMaxChargeId(carId: Int): Int?

    @Query("SELECT MAX(odometer) FROM charges_summary WHERE carId = :carId AND qualityState != 'quarantined' AND odometer >= 0")
    suspend fun getMaxNonNegativeOdometer(carId: Int): Double?

    @Query("DELETE FROM charges_summary WHERE carId = :carId")
    suspend fun deleteAllForCar(carId: Int)

    @Query("DELETE FROM charges_summary WHERE carId = :carId AND chargeId = :chargeId")
    suspend fun deleteChargeById(carId: Int, chargeId: Int)

    // === Quick Stats Queries ===

    // Total count
    @Query("SELECT COUNT(*) FROM charges_summary WHERE carId = :carId AND qualityState IN ('observed', 'derived')")
    suspend fun count(carId: Int): Int

    // Flow-based count for real-time progress updates
    @Query("SELECT COUNT(*) FROM charges_summary WHERE carId = :carId AND qualityState IN ('observed', 'derived')")
    fun observeCount(carId: Int): kotlinx.coroutines.flow.Flow<Int>

    @Query("""
        SELECT COUNT(*) FROM charges_summary
        WHERE carId = :carId
        AND qualityState IN ('observed', 'derived')
        AND startDate >= :startDate AND startDate < :endDate
    """)
    suspend fun countInRange(carId: Int, startDate: String, endDate: String): Int

    @Query("""
        SELECT * FROM charges_summary
        WHERE carId = :carId
        AND qualityState IN ('observed', 'derived')
        AND startDate >= :startDate AND startDate < :endDate
        ORDER BY startDate ASC
    """)
    suspend fun getChargesInRange(carId: Int, startDate: String, endDate: String): List<ChargeSummary>

    /** Bounded history read for local-time year projection, including trusted legacy rows. */
    @Query("""
        SELECT * FROM charges_summary
        WHERE carId = :carId AND qualityState != 'quarantined'
        AND startDate >= :startDate AND startDate < :endDate
        ORDER BY startDate ASC
    """)
    suspend fun getHistoryChargesInRange(carId: Int, startDate: String, endDate: String): List<ChargeSummary>

    // Total energy added
    @Query("SELECT COALESCE(SUM(energyAdded), 0) FROM charges_summary WHERE carId = :carId AND qualityState IN ('observed', 'derived')")
    suspend fun sumEnergyAdded(carId: Int): Double

    @Query("""
        SELECT COALESCE(SUM(energyAdded), 0) FROM charges_summary
        WHERE carId = :carId
        AND qualityState IN ('observed', 'derived')
        AND startDate >= :startDate AND startDate < :endDate
    """)
    suspend fun sumEnergyAddedInRange(carId: Int, startDate: String, endDate: String): Double

    // Total cost
    @Query("SELECT COALESCE(SUM(cost), 0) FROM charges_summary WHERE carId = :carId AND qualityState IN ('observed', 'derived')")
    suspend fun sumCost(carId: Int): Double

    /** Number of charge records with an observed cost; zero is a valid value. */
    @Query("SELECT COUNT(cost) FROM charges_summary WHERE carId = :carId AND qualityState IN ('observed', 'derived')")
    suspend fun countWithCost(carId: Int): Int

    @Query("""
        SELECT COALESCE(SUM(cost), 0) FROM charges_summary
        WHERE carId = :carId
        AND qualityState IN ('observed', 'derived')
        AND startDate >= :startDate AND startDate < :endDate
    """)
    suspend fun sumCostInRange(carId: Int, startDate: String, endDate: String): Double

    @Query("""
        SELECT COUNT(cost) FROM charges_summary
        WHERE carId = :carId
        AND qualityState IN ('observed', 'derived')
        AND startDate >= :startDate AND startDate < :endDate
    """)
    suspend fun countWithCostInRange(carId: Int, startDate: String, endDate: String): Int

    // Average cost per kWh
    @Query("""
        SELECT COALESCE(SUM(cost) / NULLIF(SUM(energyAdded), 0), 0)
        FROM charges_summary WHERE carId = :carId AND qualityState IN ('observed', 'derived') AND cost IS NOT NULL
    """)
    suspend fun avgCostPerKwh(carId: Int): Double

    // Average cost per kWh in range
    @Query("""
        SELECT COALESCE(SUM(cost) / NULLIF(SUM(energyAdded), 0), 0)
        FROM charges_summary 
        WHERE carId = :carId 
        AND qualityState IN ('observed', 'derived')
        AND startDate >= :startDate 
        AND startDate < :endDate
        AND cost IS NOT NULL
    """)
    suspend fun avgCostPerKwhInRange(carId: Int, startDate: String, endDate: String): Double

    // Biggest single charge (by energy added)
    @Query("""
        SELECT * FROM charges_summary
        WHERE carId = :carId
        AND qualityState IN ('observed', 'derived')
        ORDER BY energyAdded DESC LIMIT 1
    """)
    suspend fun biggestCharge(carId: Int): ChargeSummary?

    @Query("""
        SELECT * FROM charges_summary
        WHERE carId = :carId
        AND qualityState IN ('observed', 'derived')
        AND startDate >= :startDate AND startDate < :endDate
        ORDER BY energyAdded DESC LIMIT 1
    """)
    suspend fun biggestChargeInRange(carId: Int, startDate: String, endDate: String): ChargeSummary?

    // Most expensive single charge
    @Query("""
        SELECT * FROM charges_summary
        WHERE carId = :carId AND qualityState IN ('observed', 'derived') AND cost IS NOT NULL
        ORDER BY cost DESC LIMIT 1
    """)
    suspend fun mostExpensiveCharge(carId: Int): ChargeSummary?

    @Query("""
        SELECT * FROM charges_summary
        WHERE carId = :carId AND qualityState IN ('observed', 'derived') AND cost IS NOT NULL
        AND startDate >= :startDate AND startDate < :endDate
        ORDER BY cost DESC LIMIT 1
    """)
    suspend fun mostExpensiveChargeInRange(carId: Int, startDate: String, endDate: String): ChargeSummary?

    // Most expensive per kWh charge
    @Query("""
        SELECT * FROM charges_summary
        WHERE carId = :carId AND qualityState IN ('observed', 'derived') AND cost IS NOT NULL AND energyAdded > 0
        ORDER BY (cost / energyAdded) DESC LIMIT 1
    """)
    suspend fun mostExpensivePerKwhCharge(carId: Int): ChargeSummary?

    @Query("""
        SELECT * FROM charges_summary
        WHERE carId = :carId AND qualityState IN ('observed', 'derived') AND cost IS NOT NULL AND energyAdded > 0
        AND startDate >= :startDate AND startDate < :endDate
        ORDER BY (cost / energyAdded) DESC LIMIT 1
    """)
    suspend fun mostExpensivePerKwhChargeInRange(carId: Int, startDate: String, endDate: String): ChargeSummary?

    // Average charge duration
    @Query("SELECT AVG(durationMin) FROM charges_summary WHERE carId = :carId AND qualityState IN ('observed', 'derived')")
    suspend fun avgDuration(carId: Int): Double?

    // First charge date
    @Query("SELECT MIN(startDate) FROM charges_summary WHERE carId = :carId AND qualityState IN ('observed', 'derived')")
    suspend fun firstChargeDate(carId: Int): String?

    // === Queries for Detail Sync ===

    // Get charge IDs that need detail processing
    @Query("""
        SELECT c.chargeId FROM charges_summary c
        LEFT JOIN charge_detail_aggregates a ON c.carId = a.carId AND c.chargeId = a.chargeId
        WHERE c.carId = :carId
        AND c.qualityState != 'quarantined' AND c.qualityReason NOT LIKE 'detail_terminal_404%'
        AND COALESCE(c.apiEvidence,'') NOT LIKE ('{"_matelink_detail_terminal_404_car_id":' || c.carId || ',"_matelink_local_evidence_version":1,%')
        AND (a.chargeId IS NULL OR a.schemaVersion < :currentVersion)
        ORDER BY c.chargeId
    """)
    suspend fun getUnprocessedChargeIds(carId: Int, currentVersion: Int): List<Int>

    @Query("""
        SELECT c.chargeId FROM charges_summary c
        LEFT JOIN charge_detail_aggregates a ON c.carId = a.carId AND c.chargeId = a.chargeId
        WHERE c.carId = :carId AND c.qualityState != 'quarantined'
        AND c.qualityReason NOT LIKE 'detail_terminal_404%'
        AND COALESCE(c.apiEvidence,'') NOT LIKE ('{"_matelink_detail_terminal_404_car_id":' || c.carId || ',"_matelink_local_evidence_version":1,%')
        AND (a.chargeId IS NULL OR a.schemaVersion < :currentVersion)
        ORDER BY c.chargeId LIMIT :limit
    """)
    suspend fun getUnprocessedChargeBatchIds(carId: Int, currentVersion: Int, limit: Int): List<Int>

    // Count unprocessed charges
    @Query("""
        SELECT COUNT(*) FROM charges_summary c
        LEFT JOIN charge_detail_aggregates a ON c.carId = a.carId AND c.chargeId = a.chargeId
        WHERE c.carId = :carId
        AND c.qualityState != 'quarantined' AND c.qualityReason NOT LIKE 'detail_terminal_404%'
        AND COALESCE(c.apiEvidence,'') NOT LIKE ('{"_matelink_detail_terminal_404_car_id":' || c.carId || ',"_matelink_local_evidence_version":1,%')
        AND (a.chargeId IS NULL OR a.schemaVersion < :currentVersion)
    """)
    suspend fun countUnprocessedCharges(carId: Int, currentVersion: Int): Int

    // === Year List for Filter ===

    @Query("""
        SELECT DISTINCT CAST(strftime('%Y', startDate) AS INTEGER) as year
        FROM charges_summary
        WHERE carId = :carId
        AND qualityState != 'quarantined'
        ORDER BY year DESC
    """)
    suspend fun getYears(carId: Int): List<Int>

    // === Range Records Queries ===

    /**
     * Find the maximum distance traveled between two consecutive charges.
     * Sums actual logged drives between charges (not odometer diff, which can include unlogged drives).
     */
    @Query("""
        SELECT
            prev.chargeId as fromChargeId,
            curr.chargeId as toChargeId,
            COALESCE((
                SELECT SUM(d.distance)
                FROM drives_summary d
                WHERE d.carId = curr.carId
                  AND d.qualityState IN ('observed', 'derived')
                  AND d.startDate > prev.endDate
                  AND d.startDate < curr.startDate
            ), 0) as distance,
            prev.startDate as fromDate,
            curr.startDate as toDate
        FROM charges_summary curr
        INNER JOIN charges_summary prev ON prev.carId = curr.carId
            AND prev.startDate = (
                SELECT MAX(p.startDate)
                FROM charges_summary p
                WHERE p.carId = curr.carId AND p.startDate < curr.startDate
            )
        WHERE curr.carId = :carId
            AND curr.qualityState IN ('observed', 'derived')
            AND prev.qualityState IN ('observed', 'derived')
        ORDER BY distance DESC
        LIMIT 1
    """)
    suspend fun maxDistanceBetweenCharges(carId: Int): MaxDistanceBetweenChargesResult?

    /**
     * Find the maximum distance traveled between two consecutive charges within a date range.
     * Both charges must be within the range.
     * Sums actual logged drives between charges (not odometer diff, which can include unlogged drives).
     */
    @Query("""
        SELECT
            prev.chargeId as fromChargeId,
            curr.chargeId as toChargeId,
            COALESCE((
                SELECT SUM(d.distance)
                FROM drives_summary d
                WHERE d.carId = curr.carId
                  AND d.qualityState IN ('observed', 'derived')
                  AND d.startDate > prev.endDate
                  AND d.startDate < curr.startDate
            ), 0) as distance,
            prev.startDate as fromDate,
            curr.startDate as toDate
        FROM charges_summary curr
        INNER JOIN charges_summary prev ON prev.carId = curr.carId
            AND prev.startDate = (
                SELECT MAX(p.startDate)
                FROM charges_summary p
                WHERE p.carId = curr.carId AND p.startDate < curr.startDate
            )
        WHERE curr.carId = :carId
            AND curr.qualityState IN ('observed', 'derived')
            AND prev.qualityState IN ('observed', 'derived')
            AND prev.startDate >= :startDate
            AND curr.startDate < :endDate
        ORDER BY distance DESC
        LIMIT 1
    """)
    suspend fun maxDistanceBetweenChargesInRange(
        carId: Int,
        startDate: String,
        endDate: String
    ): MaxDistanceBetweenChargesResult?

    /**
     * Find the longest gap (in days) between two consecutive charges.
     */
    @Query("""
        SELECT
            prev.chargeId as fromChargeId,
            curr.chargeId as toChargeId,
            CAST(julianday(curr.startDate) - julianday(prev.endDate) AS REAL) as gapDays,
            prev.startDate as fromDate,
            curr.startDate as toDate
        FROM charges_summary curr
        INNER JOIN charges_summary prev ON prev.carId = curr.carId
            AND prev.startDate = (
                SELECT MAX(p.startDate)
                FROM charges_summary p
                WHERE p.carId = curr.carId AND p.startDate < curr.startDate
            )
        WHERE curr.carId = :carId
            AND curr.qualityState IN ('observed', 'derived')
            AND prev.qualityState IN ('observed', 'derived')
        ORDER BY gapDays DESC
        LIMIT 1
    """)
    suspend fun longestGapBetweenCharges(carId: Int): GapBetweenChargesResult?

    @Query("""
        SELECT
            prev.chargeId as fromChargeId,
            curr.chargeId as toChargeId,
            CAST(julianday(curr.startDate) - julianday(prev.endDate) AS REAL) as gapDays,
            prev.startDate as fromDate,
            curr.startDate as toDate
        FROM charges_summary curr
        INNER JOIN charges_summary prev ON prev.carId = curr.carId
            AND prev.startDate = (
                SELECT MAX(p.startDate)
                FROM charges_summary p
                WHERE p.carId = curr.carId AND p.startDate < curr.startDate
            )
        WHERE curr.carId = :carId
            AND curr.qualityState IN ('observed', 'derived')
            AND prev.qualityState IN ('observed', 'derived')
            AND prev.startDate >= :startDate
            AND curr.startDate < :endDate
        ORDER BY gapDays DESC
        LIMIT 1
    """)
    suspend fun longestGapBetweenChargesInRange(
        carId: Int,
        startDate: String,
        endDate: String
    ): GapBetweenChargesResult?

    /**
     * Find the charge with the biggest battery gain (endBatteryLevel - startBatteryLevel).
     */
    @Query("""
        SELECT * FROM charges_summary
        WHERE carId = :carId
        AND qualityState IN ('observed', 'derived')
        ORDER BY (endBatteryLevel - startBatteryLevel) DESC
        LIMIT 1
    """)
    suspend fun biggestBatteryGainCharge(carId: Int): ChargeSummary?

    @Query("""
        SELECT * FROM charges_summary
        WHERE carId = :carId
        AND qualityState IN ('observed', 'derived')
        AND startDate >= :startDate AND startDate < :endDate
        ORDER BY (endBatteryLevel - startBatteryLevel) DESC
        LIMIT 1
    """)
    suspend fun biggestBatteryGainChargeInRange(
        carId: Int,
        startDate: String,
        endDate: String
    ): ChargeSummary?

}

/**
 * Result of max distance between charges query.
 */
data class MaxDistanceBetweenChargesResult(
    val fromChargeId: Int,
    val toChargeId: Int,
    val distance: Double,
    val fromDate: String,
    val toDate: String
)

/**
 * Result of longest gap between charges query.
 */
data class GapBetweenChargesResult(
    val fromChargeId: Int,
    val toChargeId: Int,
    val gapDays: Double,
    val fromDate: String,
    val toDate: String
)

data class MonthlyChargeAggregation(
    val month: Int,
    /** Null means the month has records but no qualified battery-energy sample. */
    val totalEnergy: Double?,
    val chargeCount: Int,
    val energySampleCount: Int,
    /** Source-reported prices only; the annual cost headline resolves manual overrides separately. */
    val totalCost: Double? = null,
    val costSampleCount: Int = 0
)

/**
 * This is the actual Room @Transaction read/merge/upsert orchestration, with
 * isolated I/O ports for deterministic same-ID storage tests. No deletes,
 * backfills or cross-vehicle numeric-ID fallbacks.
 */
internal suspend fun persistChargeRowsWithoutEvidenceLoss(
    rows: List<ChargeSummary>,
    get: suspend (Int, Int) -> ChargeSummary?,
    upsert: suspend (ChargeSummary) -> Unit,
    archive: suspend (ChargeSummary) -> Unit = {}
) {
    rows.forEach { incoming ->
        val prior = get(incoming.carId, incoming.chargeId)
        if (prior != null && HistorySourceReceiptArchive.replaced(prior, incoming)) archive(prior)
        val merged = mergeStoredCharge(incoming, prior)
        val marked = merged.copy(apiEvidence = HistorySummaryEvidenceCodec.carryDetailTerminal404(
            merged.apiEvidence, prior?.apiEvidence, incoming.carId))
        // Legacy terminal rows lost their original qualification in older code. Do not guess it back.
        upsert(if (prior?.qualityReason?.startsWith("detail_terminal_404") == true) {
            val sameReceipt = HistorySummaryEvidenceCodec.sourceJson(incoming.apiEvidence) ==
                HistorySummaryEvidenceCodec.sourceJson(prior.apiEvidence)
            marked.copy(qualityState = prior.qualityState,
                qualityReason = if (sameReceipt) prior.qualityReason else incoming.qualityReason)
        } else marked)
    }
}

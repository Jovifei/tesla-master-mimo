package com.matelink.data.local

import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.HistoryDetailReceipt
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.toRawAnalysisChargeData
import com.matelink.domain.analytics.toRawAnalysisDriveData
import com.matelink.data.repository.sha256Framed
import com.matelink.util.parseIsoInstant

/**
 * Immutable private cold receipt for a same-numeric-ID source/session replacement.
 * This uses the existing v23 receipt table under a DIFFERENT kind and identity key;
 * it is never eligible as detail points, a live Fleet event, or a current summary.
 * Original raw envelope bytes are retained without guessing any missing UID.
 */
internal object HistorySourceReceiptArchive {
    const val CHARGE_KIND = "archived_charge_source"
    const val DRIVE_KIND = "archived_drive_source"

    private fun equivalentWindow(aStart: String?, aEnd: String?, bStart: String?, bEnd: String?): Boolean {
        val a = parseIsoInstant(aStart)
        val b = parseIsoInstant(aEnd)
        val c = parseIsoInstant(bStart)
        val d = parseIsoInstant(bEnd)
        return a != null && b != null && a == c && b == d
    }

    fun replaced(old: ChargeSummary, new: ChargeSummary): Boolean {
        if (old.carId != new.carId || old.chargeId != new.chargeId) return false
        if (old.qualityState == "quarantined" && old.apiEvidence != new.apiEvidence) return true
        val a = old.toRawAnalysisChargeData()
        val b = new.toRawAnalysisChargeData()
        return a.source != b.source || a.sourceInstanceId?.trim() != b.sourceInstanceId?.trim() ||
            a.sourceRecordIdentity != b.sourceRecordIdentity ||
            !equivalentWindow(a.startDate, a.endDate, b.startDate, b.endDate)
    }

    fun replaced(old: DriveSummary, new: DriveSummary): Boolean {
        if (old.carId != new.carId || old.driveId != new.driveId) return false
        if (old.qualityState == "quarantined" && old.apiEvidence != new.apiEvidence) return true
        val a = old.toRawAnalysisDriveData()
        val b = new.toRawAnalysisDriveData()
        return a.source != b.source || a.sourceInstanceId?.trim() != b.sourceInstanceId?.trim() ||
            a.sourceRecordIdentity != b.sourceRecordIdentity ||
            !equivalentWindow(a.startDate, a.endDate, b.startDate, b.endDate)
    }

    fun capture(old: ChargeSummary): HistoryDetailReceipt? {
        val raw = old.toRawAnalysisChargeData()
        return receipt(old.carId, old.chargeId, CHARGE_KIND,
            raw.source, raw.sourceInstanceId, raw.sourceRecordIdentity.value,
            raw.startDate, raw.endDate, old.apiEvidence)
    }

    fun capture(old: DriveSummary): HistoryDetailReceipt? {
        val raw = old.toRawAnalysisDriveData()
        return receipt(old.carId, old.driveId, DRIVE_KIND,
            raw.source, raw.sourceInstanceId, raw.sourceRecordIdentity.value,
            raw.startDate, raw.endDate, old.apiEvidence)
    }

    private fun receipt(
        localCar: Int, recordId: Int, kind: String, source: String?,
        instance: String?, sourceRecord: String?, start: String?, end: String?,
        original: String?
    ): HistoryDetailReceipt? {
        // A source switch must never silently destroy the only old summary
        // when an old schema has no raw receipt. Roll back its enclosing Room txn.
        check(!original.isNullOrBlank()) { "history_conflict_original_receipt_missing" }
        val bytes = original.toByteArray(Charsets.UTF_8)
        check(bytes.size <= 512 * 1024) { "history_conflict_receipt_oversize" }
        // Input framed to eliminate ambiguous string concatenation and keep accounts isolated.
        val identity = sha256Framed("source-conflict-v1".toByteArray(Charsets.UTF_8), listOf(
            localCar.toString(), kind, recordId.toString(), source.orEmpty(),
            instance.orEmpty(), sourceRecord.orEmpty(),
            start.orEmpty(), end.orEmpty(), original
        ).map { it.toByteArray(Charsets.UTF_8) })
        val fingerprint = sha256Framed("local-history-namespace".toByteArray(Charsets.UTF_8),
            listOf(localCar.toString().toByteArray(Charsets.UTF_8)))
        return HistoryDetailReceipt(
            receiptId = identity, identityKey = identity, localHistoryCarId = localCar,
            scopeFingerprint = fingerprint, kind = kind, recordId = recordId,
            sourceKey = source.orEmpty(), sourceInstanceKey = instance.orEmpty(),
            sourceInstanceVerified = !instance.isNullOrBlank(),
            sourceIdKey = sourceRecord.orEmpty(), sourceIdVerified = !sourceRecord.isNullOrBlank(),
            startInstant = parseIsoInstant(start)?.toString() ?: "unverified",
            endInstant = parseIsoInstant(end)?.toString() ?: "unverified",
            localRevision = 1, state = "archived_source_conflict",
            responseComplete = false, providerTotalVerified = false, providerPointCount = null,
            pointCount = 0, nextCursor = null,
            contentSha256 = sha256Framed(bytes, emptyList()), encodedByteCount = bytes.size.toLong(),
            codecVersion = 0, headerJson = original, lengthUnit = null,
            receivedAtEpochMs = System.currentTimeMillis()
        )
    }
}

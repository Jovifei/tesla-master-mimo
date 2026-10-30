package com.matelink.data.repository

import androidx.room.withTransaction
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.resolveSourceRecordIdentity
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.HistoryDetailReceipt
import com.matelink.domain.analytics.ResolvedDriveEnergy
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.toRawAnalysisDriveData
import com.matelink.util.parseIsoInstant
import kotlinx.coroutines.CancellationException

data class PersistedHistoryDetail<T>(val summary: T, val receipt: HistoryDetailReceipt?)

class HistoryDetailPointSaveRejected(val reason: String) : IllegalStateException(reason)

internal enum class HistoryDetailDiagStage {
    SUMMARY_UNAVAILABLE, SUMMARY_MISSING, SUMMARY_QUARANTINED, SUMMARY_DIAGNOSTIC_READ_FAILED,
    SUMMARY_IDENTITY_REJECTED, DETAIL_IDENTITY_REJECTED, SUMMARY_MERGE_UNAVAILABLE
}

internal enum class HistoryDetailDiagFlag {
    ROW_CONTEXT_MATCHES, CONTEXT_IDENTITY_PRESENT, RECORD_ID_VALID,
    RAW_EVIDENCE_PRESENT, RAW_EVIDENCE_DECODABLE, EVIDENCE_ID_MATCHES, RAW_ID_MATCHES,
    ROW_START_VALID, ROW_END_VALID, ROW_WINDOW_VALID, RAW_START_VALID, RAW_END_VALID,
    RAW_ROW_START_MATCHES, RAW_ROW_END_MATCHES, RAW_SOURCE_PRESENT, RAW_SOURCE_INSTANCE_PRESENT,
    RAW_SOURCE_ID_CONFLICT, DETAIL_ID_MATCHES, DETAIL_SOURCE_MATCHES, DETAIL_INSTANCE_MATCHES,
    DETAIL_RECORD_CONFLICT, DETAIL_RECORD_MATCHES, DETAIL_START_VALID, DETAIL_END_VALID,
    DETAIL_START_MATCHES, DETAIL_END_MATCHES, DETAIL_HAS_SAMPLES, DETAIL_HAS_MULTIPLE_SAMPLES
}

/** Only enums and booleans may enter this temporary device diagnostic. */
internal data class HistoryDetailDiag(
    val stage: HistoryDetailDiagStage,
    val flags: Map<HistoryDetailDiagFlag, Boolean> = emptyMap()
) {
    fun line(): String = "stage=${stage.name}" + flags.entries.sortedBy { it.key.ordinal }
        .joinToString(separator = "", transform = { " ${it.key.name}=${it.value}" })
}

internal fun emitHistoryDetailDiag(
    write: (String, String) -> Unit,
    diagnostic: () -> HistoryDetailDiag
) {
    try { write("HistoryDetailDiag", diagnostic().line()) }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { /* Diagnostics must not change a rejected persistence result, including JVM Log stubs. */ }
}

private fun rejectedSummaryDiagnostic(row: DriveSummary, context: VehicleContext): HistoryDetailDiag {
    val decoded = row.apiEvidence?.let(HistorySummaryEvidenceCodec::decodeDrive)
    val raw = row.toRawAnalysisDriveData()
    val rowStart = parseIsoInstant(row.startDate)
    val rowEnd = parseIsoInstant(row.endDate)
    val rawStart = parseIsoInstant(raw.startDate)
    val rawEnd = parseIsoInstant(raw.endDate)
    return HistoryDetailDiag(HistoryDetailDiagStage.SUMMARY_IDENTITY_REJECTED, mapOf(
        HistoryDetailDiagFlag.ROW_CONTEXT_MATCHES to (row.carId == context.localHistoryCarId),
        HistoryDetailDiagFlag.CONTEXT_IDENTITY_PRESENT to context.stableIdentity.isNotBlank(),
        HistoryDetailDiagFlag.RECORD_ID_VALID to (row.driveId >= 0),
        HistoryDetailDiagFlag.RAW_EVIDENCE_PRESENT to !row.apiEvidence.isNullOrBlank(),
        HistoryDetailDiagFlag.RAW_EVIDENCE_DECODABLE to (decoded != null),
        HistoryDetailDiagFlag.EVIDENCE_ID_MATCHES to (decoded?.driveId == row.driveId),
        HistoryDetailDiagFlag.RAW_ID_MATCHES to (raw.driveId == row.driveId),
        HistoryDetailDiagFlag.ROW_START_VALID to (rowStart != null),
        HistoryDetailDiagFlag.ROW_END_VALID to (rowEnd != null),
        HistoryDetailDiagFlag.ROW_WINDOW_VALID to (rowStart != null && rowEnd != null && !rowEnd.isBefore(rowStart)),
        HistoryDetailDiagFlag.RAW_START_VALID to (rawStart != null),
        HistoryDetailDiagFlag.RAW_END_VALID to (rawEnd != null),
        HistoryDetailDiagFlag.RAW_ROW_START_MATCHES to (rawStart != null && rawStart == rowStart),
        HistoryDetailDiagFlag.RAW_ROW_END_MATCHES to (rawEnd != null && rawEnd == rowEnd),
        HistoryDetailDiagFlag.RAW_SOURCE_PRESENT to !raw.source.isNullOrBlank(),
        HistoryDetailDiagFlag.RAW_SOURCE_INSTANCE_PRESENT to !raw.sourceInstanceId.isNullOrBlank(),
        HistoryDetailDiagFlag.RAW_SOURCE_ID_CONFLICT to raw.sourceRecordIdentity.conflicted
    ))
}

private fun rejectedDetailDiagnostic(identity: HistoryDetailIdentity, detail: DriveDetail): HistoryDetailDiag {
    val record = resolveSourceRecordIdentity(detail.sourceId, detail.sourceRecordId)
    val start = parseIsoInstant(detail.startDate)
    val end = parseIsoInstant(detail.endDate)
    return HistoryDetailDiag(HistoryDetailDiagStage.DETAIL_IDENTITY_REJECTED, mapOf(
        HistoryDetailDiagFlag.DETAIL_ID_MATCHES to (identity.recordId == detail.driveId),
        HistoryDetailDiagFlag.DETAIL_SOURCE_MATCHES to (identity.sourceKey == detail.source?.trim().orEmpty()),
        HistoryDetailDiagFlag.DETAIL_INSTANCE_MATCHES to (identity.sourceInstanceKey == detail.sourceInstanceId?.trim().orEmpty()),
        HistoryDetailDiagFlag.DETAIL_RECORD_CONFLICT to record.conflicted,
        HistoryDetailDiagFlag.DETAIL_RECORD_MATCHES to (identity.sourceIdKey == record.value?.trim().orEmpty()),
        HistoryDetailDiagFlag.DETAIL_START_VALID to (start != null),
        HistoryDetailDiagFlag.DETAIL_END_VALID to (end != null),
        HistoryDetailDiagFlag.DETAIL_START_MATCHES to (start?.toString() == identity.startInstant),
        HistoryDetailDiagFlag.DETAIL_END_MATCHES to (end?.toString() == identity.endInstant)
    ))
}

/** A detail must match the current saved list receipt before either projection or points are written. */
suspend fun persistVerifiedChargeDetail(
    database: StatsDatabase,
    pointStore: HistoryDetailRepository,
    context: VehicleContext,
    detail: ChargeDetail,
    ensureCurrent: suspend () -> Unit
): PersistedHistoryDetail<ChargeSummary>? {
    var rejectedIdentity: HistoryDetailIdentity? = null
    try {
        return database.withTransaction {
    ensureCurrent()
    val dao = database.chargeSummaryDao()
    val before = dao.get(context.localHistoryCarId, detail.chargeId) ?: return@withTransaction null
    val identity = before.verifiedDetailIdentity(context) ?: return@withTransaction null
    rejectedIdentity = identity
    if (!identity.matches(detail.chargeId, detail.source, detail.sourceInstanceId,
            detail.startDate, detail.endDate, detail.sourceId, detail.sourceRecordId)) return@withTransaction null
    if (!dao.mergeDetail(context.localHistoryCarId, detail, ensureCurrent)) return@withTransaction null
    if (detail.chargePoints == null) {
        // A qualified session scalar does NOT imply that individual points were supplied.
        // Preserve the scalar and an explicit missing-point reason independently.
        try { pointStore.recordFailure(identity, "charge_points_not_provided") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* The scalar is still valid; a missing marker is not an invented trace. */ }
        ensureCurrent()
        return@withTransaction PersistedHistoryDetail(
            checkNotNull(dao.get(context.localHistoryCarId, detail.chargeId)), null)
    }
    val points = pointStore.saveCharge(identity, detail, ensureCurrent = ensureCurrent)
    if (points is HistoryDetailWriteResult.Rejected) throw HistoryDetailPointSaveRejected(points.reason)
    ensureCurrent()
    val saved = checkNotNull(dao.get(context.localHistoryCarId, detail.chargeId))
    PersistedHistoryDetail(saved, (points as HistoryDetailWriteResult.Stored).receipt)
        }
    } catch (e: HistoryDetailPointSaveRejected) {
        // The outer transaction rolled back both the summary merge and point write.
        // Record only a bounded reason after rollback; an older complete receipt survives.
        rejectedIdentity?.let { identity ->
            try { pointStore.recordFailure(identity, e.reason) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* A full disk may also reject the failure marker. */ }
        }
        throw e
    }
}

suspend fun persistVerifiedDriveDetail(
    database: StatsDatabase,
    pointStore: HistoryDetailRepository,
    context: VehicleContext,
    detail: DriveDetail,
    proposed: ResolvedDriveEnergy? = null,
    onDiagnostic: ((String) -> Unit)? = null,
    ensureCurrent: suspend () -> Unit
): PersistedHistoryDetail<DriveSummary>? {
    fun report(diagnostic: () -> HistoryDetailDiag) {
        val callback = onDiagnostic ?: return
        emitHistoryDetailDiag(write = { _, line -> callback(line) }) {
            val event = diagnostic()
            event.copy(flags = event.flags + mapOf(
                // These describe only this received detail, never provider collection completeness.
                HistoryDetailDiagFlag.DETAIL_HAS_SAMPLES to !detail.positions.isNullOrEmpty(),
                HistoryDetailDiagFlag.DETAIL_HAS_MULTIPLE_SAMPLES to ((detail.positions?.size ?: 0) > 1)
            ))
        }
    }
    var rejectedIdentity: HistoryDetailIdentity? = null
    try {
        return database.withTransaction {
    ensureCurrent()
    val before = database.driveSummaryDao().get(context.localHistoryCarId, detail.driveId)
        ?: run {
            if (onDiagnostic != null) {
                val stage = try {
                    val any = database.driveSummaryDao().getAnyForPersistence(context.localHistoryCarId, detail.driveId)
                    when {
                        any == null -> HistoryDetailDiagStage.SUMMARY_MISSING
                        any.qualityState == "quarantined" -> HistoryDetailDiagStage.SUMMARY_QUARANTINED
                        else -> HistoryDetailDiagStage.SUMMARY_UNAVAILABLE
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { HistoryDetailDiagStage.SUMMARY_DIAGNOSTIC_READ_FAILED }
                report { HistoryDetailDiag(stage) }
            }
            return@withTransaction null
        }
    val identity = before.verifiedDetailIdentity(context) ?: run {
        report { rejectedSummaryDiagnostic(before, context) }
        return@withTransaction null
    }
    rejectedIdentity = identity
    if (!identity.matches(detail.driveId, detail.source, detail.sourceInstanceId,
            detail.startDate, detail.endDate, detail.sourceId, detail.sourceRecordId)) {
        report { rejectedDetailDiagnostic(identity, detail) }
        return@withTransaction null
    }
    val saved = database.driveSummaryDao().mergeResolvedDetail(context.localHistoryCarId,
        detail, proposed, ensureCurrent) ?: run {
        report { HistoryDetailDiag(HistoryDetailDiagStage.SUMMARY_MERGE_UNAVAILABLE) }
        return@withTransaction null
    }
    if (detail.positions == null) {
        try { pointStore.recordFailure(identity, "drive_points_not_provided") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* No curve proof; session energy remains independently qualified. */ }
        ensureCurrent()
        return@withTransaction PersistedHistoryDetail(saved, null)
    }
    val points = pointStore.saveDrive(identity, detail, ensureCurrent = ensureCurrent)
    if (points is HistoryDetailWriteResult.Rejected) throw HistoryDetailPointSaveRejected(points.reason)
    ensureCurrent()
    PersistedHistoryDetail(saved, (points as HistoryDetailWriteResult.Stored).receipt)
        }
    } catch (e: HistoryDetailPointSaveRejected) {
        rejectedIdentity?.let { identity ->
            try { pointStore.recordFailure(identity, e.reason) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* The prior receipt is still intact. */ }
        }
        throw e
    }
}

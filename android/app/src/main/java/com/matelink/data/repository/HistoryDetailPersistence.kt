package com.matelink.data.repository

import androidx.room.withTransaction
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.HistoryDetailReceipt
import com.matelink.domain.analytics.ResolvedDriveEnergy
import kotlinx.coroutines.CancellationException

data class PersistedHistoryDetail<T>(val summary: T, val receipt: HistoryDetailReceipt)

class HistoryDetailPointSaveRejected(val reason: String) : IllegalStateException(reason)

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
    ensureCurrent: suspend () -> Unit
): PersistedHistoryDetail<DriveSummary>? {
    var rejectedIdentity: HistoryDetailIdentity? = null
    try {
        return database.withTransaction {
    ensureCurrent()
    val before = database.driveSummaryDao().get(context.localHistoryCarId, detail.driveId)
        ?: return@withTransaction null
    val identity = before.verifiedDetailIdentity(context) ?: return@withTransaction null
    rejectedIdentity = identity
    if (!identity.matches(detail.driveId, detail.source, detail.sourceInstanceId,
            detail.startDate, detail.endDate, detail.sourceId, detail.sourceRecordId)) return@withTransaction null
    val saved = database.driveSummaryDao().mergeResolvedDetail(context.localHistoryCarId,
        detail, proposed, ensureCurrent) ?: return@withTransaction null
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

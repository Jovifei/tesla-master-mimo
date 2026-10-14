package com.matelink.data.sync

import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.repository.ApiErrorKind
import com.matelink.data.repository.ApiResult
import kotlinx.coroutines.CancellationException

/** Shared across cars for one worker run; retries get a fresh finite budget. */
internal class DriveDetailRequestBudget(private var available: Int = 20) {
    init { require(available in 1..20) }
    val remaining: Int get() = available
    fun consume(): Boolean = if (available == 0) false else { available--; true }
}

internal data class DriveDetailBatchResult(val processed: Int, val pending: Int, val failure: ApiResult.Error? = null) {
    val complete: Boolean get() = failure == null && pending == 0
}

internal sealed interface CarSyncResult {
    object Complete : CarSyncResult
    data class Deferred(val pendingDrives: Int) : CarSyncResult
    data class Failed(val error: ApiResult.Error) : CarSyncResult
}

internal class DriveDetailSyncException(val failure: ApiResult.Error) : Exception("drive_detail_${failure.kind.name.lowercase()}")

internal fun driveDetailFailureWorkerResult(error: ApiResult.Error): ListenableWorker.Result =
    if (error.kind in setOf(ApiErrorKind.AUTH_REQUIRED, ApiErrorKind.CONFIGURATION, ApiErrorKind.INVALID_RESPONSE) ||
        (error.code?.let { it in 400..499 && it != 429 } == true)) ListenableWorker.Result.failure()
    else ListenableWorker.Result.retry()

/** Batch success is truthful only after its continuation has been durably enqueued. */
internal suspend fun finishDataSyncBatch(pendingDrives: Int, scheduleContinuation: suspend () -> Unit): ListenableWorker.Result {
    require(pendingDrives >= 0)
    if (pendingDrives == 0) return ListenableWorker.Result.success()
    scheduleContinuation()
    return ListenableWorker.Result.success(workDataOf("history_pending" to true, "pending_drives" to pendingDrives))
}

/** Actual sync orchestration with bounded SQL selection and first-failure stop. */
internal suspend fun runDriveDetailBatch(
    budget: DriveDetailRequestBudget,
    selectIds: suspend (Int) -> List<Int>,
    fetch: suspend (Int) -> ApiResult<DriveDetail>,
    persist: suspend (DriveDetail) -> Boolean,
    pendingCount: suspend () -> Int,
    ensureCurrent: suspend () -> Unit,
    markComplete: suspend () -> Unit,
    maxRequests: Int = 20,
    terminal404: suspend (Int) -> Unit = {}
): DriveDetailBatchResult {
    var processed = 0
    suspend fun pending(): Int { ensureCurrent(); val count = pendingCount(); ensureCurrent(); return count }
    ensureCurrent()
    val limit = minOf(budget.remaining, maxRequests)
    val ids = selectIds(limit)
    ensureCurrent()
    for (id in ids.take(limit)) {
        ensureCurrent()
        if (!budget.consume()) break
        val failure = try {
            when (val result = fetch(id)) {
                is ApiResult.Error -> { ensureCurrent(); if (result.code == 404) { terminal404(id); ensureCurrent(); processed++; null } else result }
                is ApiResult.Success -> {
                    ensureCurrent()
                    if (result.data.driveId != id || !persist(result.data))
                        ApiResult.Error("drive_detail_write_rejected", kind = ApiErrorKind.INVALID_RESPONSE)
                    else { ensureCurrent(); processed++; null }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { ApiResult.Error("drive_detail_sync_failed") }
        if (failure != null) return DriveDetailBatchResult(processed, pending(), failure)
    }
    val count = pending()
    if (count == 0) { markComplete(); ensureCurrent() }
    return DriveDetailBatchResult(processed, count)
}

/** Fair round-robin across vehicles; each turn consumes at most one drive and one charge. */
internal suspend fun runHistoryDetailBatch(
    carIds: List<Int>, budget: DriveDetailRequestBudget = DriveDetailRequestBudget(),
    syncQuantum: suspend (Int, DriveDetailRequestBudget) -> CarSyncResult
): CarSyncResult {
    val pending = linkedMapOf<Int, Int>()
    do {
        val before = budget.remaining
        for (car in carIds) {
            when (val result = syncQuantum(car, budget)) {
                is CarSyncResult.Failed -> return result
                is CarSyncResult.Deferred -> pending[car] = result.pendingDrives
                CarSyncResult.Complete -> pending.remove(car)
            }
        }
        if (budget.remaining == before) break
    } while (budget.remaining > 0 && pending.isNotEmpty())
    return if (pending.isEmpty()) CarSyncResult.Complete else CarSyncResult.Deferred(pending.values.sum())
}

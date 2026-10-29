package com.matelink.data.sync

import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.dao.SyncStateDao
import com.matelink.data.local.entity.SchemaVersion
import com.matelink.data.local.entity.SyncState
import com.matelink.domain.model.OverallSyncStatus
import com.matelink.domain.model.SyncPhase
import com.matelink.domain.model.SyncProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Process-local ownership only; durable detail cursors remain in Room. */
class SyncRun internal constructor(internal val id: Long)

/**
 * Manages sync state and emits progress updates.
 * This is the single source of truth for sync status across the app.
 */
@Singleton
class SyncManager @Inject constructor(
    private val syncStateDao: SyncStateDao,
    private val driveSummaryDao: DriveSummaryDao,
    private val chargeSummaryDao: ChargeSummaryDao,
    private val aggregateDao: AggregateDao
) {
    private val _syncStatus = MutableStateFlow(OverallSyncStatus.IDLE)
    val syncStatus: StateFlow<OverallSyncStatus> = _syncStatus.asStateFlow()

    private val _carProgress = MutableStateFlow<Map<Int, SyncProgress>>(emptyMap())
    private val runSequence = AtomicLong()
    private val progressLock = Any()
    private val owners = mutableMapOf<Int, Long>()

    internal fun newRun(): SyncRun = SyncRun(runSequence.incrementAndGet())

    internal fun claimCar(run: SyncRun, carId: Int) = synchronized(progressLock) {
        if ((owners[carId] ?: Long.MIN_VALUE) <= run.id) owners[carId] = run.id
    }

    internal fun failRun(run: SyncRun, reason: String) = finishRun(run, SyncPhase.ERROR, reason)

    internal fun cancelRun(run: SyncRun) = finishRun(run, SyncPhase.IDLE, null)

    private fun finishRun(run: SyncRun, phase: SyncPhase, message: String?) = synchronized(progressLock) {
        _carProgress.update { map -> map.mapValues { (car, progress) ->
            if (owners[car] == run.id && progress.phase !in setOf(SyncPhase.COMPLETE, SyncPhase.ERROR, SyncPhase.IDLE))
                progress.copy(phase = phase, message = message) else progress
        } }
        updateOverallStatus()
    }

    private fun canPublish(carId: Int, run: SyncRun?): Boolean = synchronized(progressLock) {
        run == null || owners[carId] == run.id
    }

    /**
     * Get sync progress for a specific car.
     */
    fun getProgressForCar(carId: Int): SyncProgress? = _carProgress.value[carId]

    /**
     * Check if summaries are synced for a car (Quick Stats available).
     */
    suspend fun areSummariesSynced(carId: Int): Boolean {
        return syncStateDao.get(carId)?.summariesSynced == true
    }

    /**
     * Check if details are synced for a car (Deep Stats available).
     */
    suspend fun areDetailsSynced(carId: Int): Boolean {
        return syncStateDao.get(carId)?.detailsSynced == true
    }

    /**
     * Get or create sync state for a car.
     */
    suspend fun getOrCreateSyncState(carId: Int): SyncState {
        return syncStateDao.get(carId) ?: SyncState(carId = carId).also {
            syncStateDao.upsert(it)
        }
    }

    /**
     * Update progress for summary sync phase.
     */
    fun updateSummaryProgress(carId: Int, message: String? = null, run: SyncRun? = null) {
        updateProgress(carId, SyncPhase.SYNCING_SUMMARIES, 0, 1, message, run)
    }

    /**
     * Mark summaries as synced and calculate total items to process.
     */
    suspend fun markSummariesComplete(carId: Int, run: SyncRun? = null) {
        if (!canPublish(carId, run)) return
        syncStateDao.markSummariesSynced(carId, System.currentTimeMillis())

        // Calculate total items to process for detail sync
        val unprocessedDrives = driveSummaryDao.countUnprocessedDrives(carId, SchemaVersion.DRIVE_ENERGY)
        val unprocessedCharges = chargeSummaryDao.countUnprocessedCharges(carId, SchemaVersion.CURRENT)

        val state = syncStateDao.get(carId)
        val hasItemsToProcess = unprocessedDrives > 0 || unprocessedCharges > 0
        if (state != null) {
            syncStateDao.upsert(
                state.copy(
                    totalDrivesToProcess = unprocessedDrives,
                    totalChargesToProcess = unprocessedCharges,
                    drivesProcessed = 0,
                    chargesProcessed = 0,
                    // Reset detailsSynced if there are items to process (e.g., schema change)
                    detailsSynced = if (hasItemsToProcess) false else state.detailsSynced
                )
            )
        }

        // If nothing to process, mark as complete
        if (!hasItemsToProcess) {
            markSyncComplete(carId, run)
        } else {
            updateProgress(
                carId,
                SyncPhase.SYNCING_DRIVE_DETAILS,
                0,
                unprocessedDrives + unprocessedCharges,
                run = run
            )
        }
    }

    /**
     * Update progress for drive detail sync.
     */
    suspend fun updateDriveDetailProgress(carId: Int, driveId: Int, run: SyncRun? = null) {
        if (!canPublish(carId, run)) return
        syncStateDao.updateDriveDetailProgress(carId, driveId)

        val state = syncStateDao.get(carId) ?: return
        val total = state.totalDrivesToProcess + state.totalChargesToProcess
        val current = state.drivesProcessed + 1

        updateProgress(carId, SyncPhase.SYNCING_DRIVE_DETAILS, current, total, run = run)
    }

    /**
     * Mark drive details as complete and start charge details.
     */
    suspend fun markDriveDetailsComplete(carId: Int, run: SyncRun? = null) {
        if (!canPublish(carId, run)) return
        val state = syncStateDao.get(carId) ?: return
        val total = state.totalDrivesToProcess + state.totalChargesToProcess

        if (state.totalChargesToProcess > 0) {
            updateProgress(
                carId,
                SyncPhase.SYNCING_CHARGE_DETAILS,
                state.drivesProcessed,
                total,
                run = run
            )
        } else {
            markSyncComplete(carId, run)
        }
    }

    /**
     * Update progress for charge detail sync.
     */
    suspend fun updateChargeDetailProgress(carId: Int, chargeId: Int, run: SyncRun? = null) {
        if (!canPublish(carId, run)) return
        syncStateDao.updateChargeDetailProgress(carId, chargeId)

        val state = syncStateDao.get(carId) ?: return
        val total = state.totalDrivesToProcess + state.totalChargesToProcess
        val current = state.drivesProcessed + state.chargesProcessed + 1

        updateProgress(carId, SyncPhase.SYNCING_CHARGE_DETAILS, current, total, run = run)
    }

    /**
     * Mark sync as complete for a car.
     */
    suspend fun markSyncComplete(carId: Int, run: SyncRun? = null) {
        if (!canPublish(carId, run)) return
        syncStateDao.markDetailsSynced(carId)
        updateProgress(carId, SyncPhase.COMPLETE, 1, 1, run = run)
        updateOverallStatus()
    }

    /**
     * Mark sync as failed with error.
     */
    fun markSyncError(carId: Int, message: String) {
        updateProgress(carId, SyncPhase.ERROR, 0, 0, message)
        updateOverallStatus()
    }

    /**
     * Reset sync state for a car (keeps cached data, only retries unprocessed items).
     */
    suspend fun resetSync(carId: Int) {
        syncStateDao.resetForResync(carId)
        updateProgress(carId, SyncPhase.IDLE, 0, 0)
        updateOverallStatus()
    }

    /**
     * Full reset for a car: deletes ALL cached data (drives, charges, aggregates)
     * and resets sync state. This forces a complete resync from the API.
     */
    suspend fun fullResetSync(carId: Int) {
        // Delete all cached data
        driveSummaryDao.deleteAllForCar(carId)
        chargeSummaryDao.deleteAllForCar(carId)
        aggregateDao.deleteDriveAggregatesForCar(carId)
        aggregateDao.deleteChargeAggregatesForCar(carId)

        // Reset sync state
        syncStateDao.resetForResync(carId)

        updateProgress(carId, SyncPhase.IDLE, 0, 0)
        updateOverallStatus()
    }

    private fun updateProgress(
        carId: Int,
        phase: SyncPhase,
        current: Int,
        total: Int,
        message: String? = null,
        run: SyncRun? = null
    ) = synchronized(progressLock) {
        if (!canPublish(carId, run)) return@synchronized
        val progress = SyncProgress(
            carId = carId,
            phase = phase,
            currentItem = current,
            totalItems = total,
            message = message
        )

        _carProgress.update { map ->
            map + (carId to progress)
        }

        updateOverallStatus()
    }

    private fun updateOverallStatus() {
        val progresses = _carProgress.value
        val isAnySyncing = progresses.values.any {
            it.phase != SyncPhase.COMPLETE &&
            it.phase != SyncPhase.ERROR &&
            it.phase != SyncPhase.IDLE
        }
        val allComplete = progresses.isNotEmpty() && progresses.values.all {
            it.phase == SyncPhase.COMPLETE
        }

        _syncStatus.value = OverallSyncStatus(
            carProgresses = progresses,
            isAnySyncing = isAnySyncing,
            allComplete = allComplete
        )
    }
}

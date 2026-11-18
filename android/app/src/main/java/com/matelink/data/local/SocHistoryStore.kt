package com.matelink.data.local

import com.matelink.data.local.dao.SocSampleDao
import com.matelink.data.local.entity.SocSample
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-only hourly SOC history fed by the already scheduled status polls.
 * No new vehicle wake-up is introduced: callers record a status they already
 * fetched. The newest reading in an hour wins; missing hours remain missing.
 */
@Singleton
class SocHistoryStore @Inject constructor(
    private val dao: SocSampleDao
) {
    companion object {
        const val RETENTION_DAYS = 30L
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
    }

    /** Returns true when the store accepted a new/updated point. */
    suspend fun record(
        carId: Int,
        socPercent: Int?,
        observedAtEpochMs: Long = System.currentTimeMillis(),
        source: String = "status"
    ): Boolean {
        val soc = socPercent?.takeIf { it in 0..100 } ?: return false
        val hour = Math.floorDiv(observedAtEpochMs, HOUR_MS) * HOUR_MS
        val sample = SocSample(carId, hour, observedAtEpochMs, soc, source)
        val inserted = dao.insertIfAbsent(sample) != -1L
        if (!inserted) {
            dao.updateIfNewer(carId, hour, observedAtEpochMs, soc, source)
        }
        dao.pruneBefore(carId, observedAtEpochMs - RETENTION_DAYS * DAY_MS)
        return true
    }

    fun observeSince(carId: Int, sinceEpochMs: Long): Flow<List<SocSample>> =
        dao.observeSince(carId, sinceEpochMs)

    fun observeRetentionWindow(carId: Int, nowEpochMs: Long = System.currentTimeMillis()): Flow<List<SocSample>> =
        observeSince(carId, nowEpochMs - RETENTION_DAYS * DAY_MS)
}
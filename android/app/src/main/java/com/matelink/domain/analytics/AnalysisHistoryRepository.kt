package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.local.VehicleContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

data class AnalysisHistorySnapshot(
    val drives: List<DriveData>,
    val charges: List<ChargeData>,
    val fetchedAt: Instant,
    val coverage: HistoryCoverage,
    val freshness: HistoryFreshness = HistoryFreshness.FRESH,
    val staleReason: String? = null,
    val context: VehicleContext? = null
)

internal fun buildPersistedHistorySnapshot(
    drives: List<com.matelink.data.local.entity.DriveSummary>,
    charges: List<com.matelink.data.local.entity.ChargeSummary>,
    fetchedAt: Instant,
    reason: String
): AnalysisHistorySnapshot? {
    if (drives.isEmpty() && charges.isEmpty()) return null
    return AnalysisHistorySnapshot(
        drives = drives.map { it.toAnalysisDriveData() },
        charges = charges.map { it.toAnalysisChargeData() },
        fetchedAt = fetchedAt,
        coverage = HistoryCoverage(
            driveCount = drives.size,
            chargeCount = charges.size
        ),
        freshness = HistoryFreshness.STALE,
        staleReason = reason
    )
}

internal class AnalysisHistorySnapshotCache {
    private val snapshots = ConcurrentHashMap<String, AnalysisHistorySnapshot>()

    fun put(stableIdentity: String, snapshot: AnalysisHistorySnapshot) {
        snapshots[stableIdentity] = snapshot.copy(
            freshness = HistoryFreshness.FRESH,
            staleReason = null
        )
    }

    fun stale(stableIdentity: String, reason: String): AnalysisHistorySnapshot? =
        snapshots[stableIdentity]?.copy(
            freshness = HistoryFreshness.STALE,
            staleReason = reason
        )
}

@Singleton
class AnalysisHistoryRepository @Inject constructor(
    private val historyRepository: UnifiedHistoryRepository
) {
    private val cache = AnalysisHistorySnapshotCache()

    suspend fun load(carId: Int): ApiResult<AnalysisHistorySnapshot> {
        return when (val result = historyRepository.load(carId)) {
            is ApiResult.Success -> {
                val history = result.data
                val snapshot = AnalysisHistorySnapshot(
                    drives = history.drives,
                    charges = history.charges,
                    fetchedAt = history.fetchedAt,
                    coverage = HistoryCoverage(
                        driveCount = history.drives.size,
                        chargeCount = history.charges.size,
                        reason = classifyEmptyHistory(
                            driveCount = history.drives.size,
                            chargeCount = history.charges.size
                        )
                    ),
                    freshness = if (history.drivesFromRemote || history.chargesFromRemote) {
                        HistoryFreshness.FRESH
                    } else {
                        HistoryFreshness.STALE
                    },
                    staleReason = if (history.drivesFromRemote || history.chargesFromRemote) null else "remote unavailable",
                    context = history.context
                )
                cache.put(history.context.stableIdentity, snapshot)
                ApiResult.Success(snapshot)
            }
            is ApiResult.Error -> result
        }
    }
}

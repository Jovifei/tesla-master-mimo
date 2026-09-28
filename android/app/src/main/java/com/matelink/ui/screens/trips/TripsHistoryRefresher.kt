package com.matelink.ui.screens.trips

import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.data.repository.UnifiedHistory
import com.matelink.domain.model.Trip

internal data class TripsRefreshResult(
    val localHistoryCarId: Int?,
    val trips: List<Trip>,
    val dcChargeIds: Set<Int>,
    val historySyncWarning: String?
)

/** Refreshes remote history before deriving trips from the vehicle-scoped cache. */
internal class TripsHistoryRefresher(
    private val refreshHistory: suspend (Int) -> ApiResult<UnifiedHistory>,
    private val resolveCachedLocalId: suspend (Int) -> Int?,
    private val loadTrips: suspend (Int) -> List<Trip>,
    private val loadDcChargeIds: suspend (Int) -> Set<Int>
) {
    suspend fun refresh(remoteApiCarId: Int): TripsRefreshResult {
        val history = refreshHistory(remoteApiCarId)
        val localHistoryCarId = when (history) {
            is ApiResult.Success -> history.data.context.localHistoryCarId
            is ApiResult.Error -> runCatching { resolveCachedLocalId(remoteApiCarId) }.getOrNull()
        }
        if (localHistoryCarId == null) {
            return TripsRefreshResult(
                localHistoryCarId = null,
                trips = emptyList(),
                dcChargeIds = emptySet(),
                historySyncWarning = HISTORY_IDENTITY_UNAVAILABLE
            )
        }
        return TripsRefreshResult(
            localHistoryCarId = localHistoryCarId,
            trips = loadTrips(localHistoryCarId),
            dcChargeIds = loadDcChargeIds(localHistoryCarId),
            historySyncWarning = when (history) {
                is ApiResult.Success -> history.data.drivesSyncError ?: history.data.chargesSyncError
                is ApiResult.Error -> history.message
            }
        )
    }
}

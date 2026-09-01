package com.matelink.ui.screens.vampire

import com.matelink.data.api.models.StandbyWindowData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.data.repository.STANDBY_ENDPOINT_UNAVAILABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyFallbackResolutionTest {
    @Test
    fun identityFailureFromLocalHistoryIsNotHiddenAsCollecting() {
        val result = resolveStandbyFallback(
            direct = ApiResult.Error(
                message = "Standby history endpoint unavailable",
                code = 404,
                details = STANDBY_ENDPOINT_UNAVAILABLE
            ),
            local = ApiResult.Error(
                message = HISTORY_IDENTITY_UNAVAILABLE,
                details = HISTORY_IDENTITY_UNAVAILABLE
            )
        )

        assertTrue(result is ApiResult.Error)
        assertEquals(HISTORY_IDENTITY_UNAVAILABLE, (result as ApiResult.Error).details)
    }

    @Test
    fun missingEndpointWithHealthyEmptyHistoryBecomesCollectingSuccess() {
        val result = resolveStandbyFallback(
            direct = ApiResult.Error(
                message = "Standby history endpoint unavailable",
                code = 404,
                details = STANDBY_ENDPOINT_UNAVAILABLE
            ),
            local = ApiResult.Success(emptyList<StandbyWindowData>())
        )

        assertTrue(result is ApiResult.Success)
        assertTrue((result as ApiResult.Success).data.isEmpty())
    }

    @Test
    fun populatedDirectStandbyHistoryWinsOverAStaleLocalError() {
        val directWindows = listOf(StandbyWindowData("2026-08-30T09:00:00Z", "2026-08-30T18:00:00Z"))
        val result = resolveStandbyFallback(
            direct = ApiResult.Success(directWindows),
            local = ApiResult.Error("local history unavailable")
        )

        assertTrue(result is ApiResult.Success)
        assertEquals(directWindows, (result as ApiResult.Success).data)
    }
}

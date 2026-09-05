package com.matelink.data.repository

import com.matelink.data.api.models.StandbyResponse
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class StandbyResponseContractTest {
    @Test
    fun missingOptionalEndpointIsClassifiedForLocalFallback() {
        val result = standbyResultForResponse(
            Response.error<StandbyResponse>(404, "not found".toResponseBody())
        )

        assertTrue(result is ApiResult.Error)
        result as ApiResult.Error
        assertEquals(404, result.code)
        assertEquals(STANDBY_ENDPOINT_UNAVAILABLE, result.details)
        assertEquals(ApiErrorKind.CONFIGURATION, result.kind)
    }
}

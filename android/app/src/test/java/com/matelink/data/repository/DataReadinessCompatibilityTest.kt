package com.matelink.data.repository

import com.matelink.data.api.models.DataReadinessResponse
import com.matelink.data.api.models.DataReadiness
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class DataReadinessCompatibilityTest {
    @Test
    fun legacyNotFoundBecomesLocalCompatibilityReadiness() {
        val result = dataReadinessResultForResponse(
            response = Response.error<DataReadinessResponse>(404, "not found".toResponseBody()),
            carId = 12,
            allowLegacyCompatibility = true
        )

        assertTrue(result is ApiResult.Success<*>)
        val readiness = (result as ApiResult.Success<DataReadiness>).data
        assertEquals(0, readiness.capabilityVersion)
        assertEquals("self-hosted:car:12", readiness.vehicleUid)
        assertTrue(readiness.items.any { it.key == "live_status" })
    }

    @Test
    fun cloudNotFoundRemainsAnError() {
        val result = dataReadinessResultForResponse(
            response = Response.error<DataReadinessResponse>(404, "not found".toResponseBody()),
            carId = 12,
            allowLegacyCompatibility = false
        )

        assertTrue(result is ApiResult.Error)
        assertEquals(404, (result as ApiResult.Error).code)
    }
}

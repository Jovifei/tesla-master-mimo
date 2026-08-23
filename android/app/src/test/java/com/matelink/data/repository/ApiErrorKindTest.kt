package com.matelink.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiErrorKindTest {
    @Test
    fun mapsAuthenticationAndRateLimitResponses() {
        assertEquals(ApiErrorKind.AUTH_REQUIRED, apiErrorKindFor(401))
        assertEquals(ApiErrorKind.AUTH_REQUIRED, apiErrorKindFor(403))
        assertEquals(ApiErrorKind.RATE_LIMITED, apiErrorKindFor(429))
    }

    @Test
    fun mapsServiceAndNetworkFailures() {
        assertEquals(ApiErrorKind.SERVICE_UNAVAILABLE, apiErrorKindFor(503))
        assertEquals(ApiErrorKind.NETWORK, apiErrorKindFor(null, "Server is temporarily unreachable"))
        assertEquals(ApiErrorKind.NETWORK, apiErrorKindFor(null, "Connection timed out"))
    }

    @Test
    fun errorCarriesStableKindAlongsideLegacyMessageAndCode() {
        val error = ApiResult.Error("Failed to fetch status: 429", 429)

        assertEquals(429, error.code)
        assertEquals(ApiErrorKind.RATE_LIMITED, error.kind)
        assertEquals("Failed to fetch status: 429", error.message)
    }
}

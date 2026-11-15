package com.matelink.data.repository

import com.matelink.data.api.models.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class FleetTelemetryDiagnosticsResponseTest {
    @Test fun notFoundAndProviderBodyNeverBecomeCompatibilityOrRawUiData() {
        val result = fleetTelemetryDiagnosticsResultForResponse(Response.error<FleetTelemetryDiagnosticsResponse>(
            404, "private upstream text".toResponseBody()))
        assertTrue(result is ApiResult.Error)
        assertEquals(404, (result as ApiResult.Error).code)
        assertFalse(result.toString().contains("private upstream text"))
    }

    @Test fun emptyEnvelopeRemainsUnavailable() {
        assertTrue(fleetTelemetryDiagnosticsResultForResponse(Response.success(FleetTelemetryDiagnosticsResponse())) is ApiResult.Error)
    }

    @Test fun successfulPartialEnvelopeRetainsNullableBranchValues() {
        val data = FleetTelemetryDiagnostics(availability = "partial", vehicle = FleetDiagnosticVehicle(paired = false))
        val result = fleetTelemetryDiagnosticsResultForResponse(Response.success(FleetTelemetryDiagnosticsResponse(data)))
        assertEquals(data, (result as ApiResult.Success).data)
        assertNull(result.data.vehicle?.streamingEnabled)
        assertNull(result.data.configuration)
    }
}

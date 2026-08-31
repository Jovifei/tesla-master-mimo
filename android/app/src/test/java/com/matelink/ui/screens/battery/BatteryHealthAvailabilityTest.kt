package com.matelink.ui.screens.battery

import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.api.models.DataReadinessItem
import com.matelink.data.repository.ApiResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BatteryHealthAvailabilityTest {
    @Test
    fun notFoundHealthEndpointIsUnavailableRatherThanUnsupported() {
        assertEquals(
            BatteryHealthAvailability.UNAVAILABLE,
            classifyBatteryHealth(ApiResult.Error("request failed", 404), null)
        )
    }

    @Test
    fun readinessCollectingIsTypedAsCollectingInsteadOfRawTransportText() {
        val readiness = DataReadinessItem(
            key = "battery_health",
            status = "collecting",
            source = "fleet_api"
        )

        assertEquals(
            BatteryHealthAvailability.COLLECTING,
            classifyBatteryHealth(ApiResult.Error("No battery health data returned"), readiness)
        )
    }

    @Test
    fun successfulHealthIsTypedAsAvailable() {
        assertEquals(
            BatteryHealthAvailability.AVAILABLE,
            classifyBatteryHealth(ApiResult.Success(BatteryHealth()), null)
        )
    }
}

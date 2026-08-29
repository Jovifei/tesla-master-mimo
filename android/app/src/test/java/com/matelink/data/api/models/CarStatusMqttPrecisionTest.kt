package com.matelink.data.api.models

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CarStatusMqttPrecisionTest {
    @Test
    fun chargingSnapshotKeepsDecimalValuesAndOptionalScheduledTime() {
        val details = ChargingDetails(
            chargerPower = 48.9,
            chargerVoltage = 240.5,
            chargerActualCurrent = 2.05,
            chargeCurrentRequest = 39.5,
            chargeCurrentRequestMax = 40.0,
            scheduledChargingStartTime = "2026-08-26T12:00:00Z"
        )

        assertEquals(48.9, details.chargerPower ?: -1.0, 0.0)
        assertEquals(240.5, details.chargerVoltage ?: -1.0, 0.0)
        assertEquals(2.05, details.chargerActualCurrent ?: -1.0, 0.0)
        assertEquals("2026-08-26T12:00:00Z", details.scheduledChargingStartTime)
    }

    @Test
    fun jsonParsingKeepsObservedZeroAndFalseWhileLeavingMissingFieldsNull() {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(CarStatusResponse::class.java)
        val parsed = adapter.fromJson(
            """{"data":{"status":{"state":"driving","car_status":{"locked":false},"driving_details":{"speed":0.0,"power":-9.5}}}}"""
        )?.data?.status ?: error("status missing")

        assertFalse(parsed.locked ?: true)
        assertEquals(0.0, parsed.speed ?: -1.0, 0.0)
        assertEquals(-9.5, parsed.power ?: 0.0, 0.0)
        assertNull(parsed.chargingDetails)
    }
}

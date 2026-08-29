package com.matelink.data.api.models

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class ChargePointMqttPrecisionTest {
    @Test
    fun chargePointJsonKeepsDecimalElectricalValues() {
        val adapter = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(ChargeDetailResponse::class.java)
        val detail = adapter.fromJson(
            """{"data":{"charge":{"charge_id":1,"charge_details":[{"charger_details":{"charger_power":48.9,"charger_voltage":240.5,"charger_actual_current":2.05}}]}}}"""
        )?.data?.charge ?: error("charge missing")
        val point = detail.chargePoints?.single() ?: error("charge point missing")

        assertEquals(48.9, point.chargerPowerValue ?: -1.0, 0.0)
        assertEquals(240.5, point.chargerVoltageValue ?: -1.0, 0.0)
        assertEquals(2.05, point.chargerCurrentValue ?: -1.0, 0.0)
    }
}

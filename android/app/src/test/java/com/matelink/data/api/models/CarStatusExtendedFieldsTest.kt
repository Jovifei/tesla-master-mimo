package com.matelink.data.api.models

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for the extended Fleet display fields (design 2026-11-05 §4).
 * The fixture mirrors the jourvolt backend status serializer key-for-key;
 * if the backend renames a key, this test is the tripwire.
 */
class CarStatusExtendedFieldsTest {

    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(CarStatusResponse::class.java)

    @Test
    fun extendedFieldsParseFromBackendShape() {
        val parsed = adapter.fromJson(
            """{"data":{"status":{"state":"online",
              "car_status":{"locked":true,"windows_open":true,"valet_mode":true,
                "speed_limit_mode":{"active":true,"current_limit_kmh":113,"pin_code_required":false},
                "media_info":{"media_state":"Playing","title":"Song","artist":"Singer","album":"Album"}},
              "car_versions":{"version":"2026.1","software_update":{"status":"downloading","download_percent":42,"install_percent":0,"expected_duration_sec":1800}},
              "climate_details":{"is_climate_on":true,"driver_temp_setting":22.5,"passenger_temp_setting":21.0,
                "seat_heater_left":2,"steering_wheel_heater":true,"climate_keeper_mode":"on"},
              "charging_details":{"plugged_in":true,"charging_state":"Charging","conn_charge_cable":"IEC",
                "fast_charger_brand":"Tesla","fast_charger_type":"Supercharger","charge_port_latch":"Engaged",
                "battery_heater_on":false,"scheduled_charging_start_time":"2026-01-01T16:00:00Z",
                "scheduled_departure_time":"2026-01-01T23:30:00Z","minutes_to_full_charge":95,
                "charge_range_added_rated":16.09,"charge_range_added_ideal":17.5}
            }}}"""
        )?.data?.status ?: error("status missing")

        assertEquals(true, parsed.carStatus?.windowsOpen)
        assertEquals(true, parsed.valetMode)
        assertEquals(true, parsed.speedLimitMode?.active)
        assertEquals(113, parsed.speedLimitMode?.currentLimitKmh)
        assertEquals("Song", parsed.mediaInfo?.title)
        assertEquals("Singer", parsed.mediaInfo?.artist)
        assertEquals("downloading", parsed.softwareUpdate?.status)
        assertEquals(42, parsed.softwareUpdate?.downloadPercent)
        assertEquals(22.5, parsed.driverTempSetting ?: -1.0, 0.0)
        assertEquals(2, parsed.climateDetails?.seatHeaterLeft)
        assertEquals(true, parsed.climateDetails?.steeringWheelHeater)
        assertEquals("IEC", parsed.chargingDetails?.connChargeCable)
        assertEquals("Supercharger", parsed.chargingDetails?.fastChargerType)
        assertEquals("2026-01-01T23:30:00Z", parsed.scheduledDepartureTime)
        assertEquals(95, parsed.minutesToFullCharge)
        assertEquals(16.09, parsed.chargingDetails?.chargeRangeAddedRated ?: -1.0, 0.0)
    }

    @Test
    fun extendedFieldsStayNullWhenBackendOmitsThem() {
        val parsed = adapter.fromJson(
            """{"data":{"status":{"state":"asleep","car_status":{"locked":true},
              "climate_details":{"is_climate_on":false},"charging_details":{"plugged_in":false}}}}"""
        )?.data?.status ?: error("status missing")

        assertNull(parsed.valetMode)
        assertNull(parsed.speedLimitMode)
        assertNull(parsed.mediaInfo)
        assertNull(parsed.softwareUpdate)
        assertNull(parsed.driverTempSetting)
        assertNull(parsed.climateDetails?.seatHeaterLeft)
        assertNull(parsed.chargingDetails?.connChargeCable)
        assertNull(parsed.scheduledDepartureTime)
        assertNull(parsed.minutesToFullCharge)
        assertTrue(parsed.carStatus?.windowsOpen == null)
    }
}

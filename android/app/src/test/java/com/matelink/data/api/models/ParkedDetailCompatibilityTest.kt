package com.matelink.data.api.models

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParkedDetailCompatibilityTest {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(ParkedDetailResponse::class.java)

    @Test
    fun derivedIntervalKeepsDistinctEndpointsAndUnknownTelemetry() {
        val detail = adapter.fromJson("""{
            "data": {
                "older_drive_id": 11, "newer_drive_id": 12,
                "start_date": "2026-01-01T08:30:00.123Z",
                "end_date": "2026-01-01T10:00:00.123Z",
                "start_address": "Previous endpoint", "end_address": "Next endpoint",
                "address": null, "source": "drive_history_interval",
                "battery_delta": null, "energy_kwh": null,
                "average_power_kw": null, "peak_power_kw": null,
                "inside_temp_average": null, "outside_temp_average": null
            }
        }""")!!.data!!

        assertEquals("drive_history_interval", detail.source)
        assertEquals("Previous endpoint", detail.startAddress)
        assertEquals("Next endpoint", detail.endAddress)
        assertNull(detail.address)
        assertNull(detail.startBatteryLevel)
        assertNull(detail.endBatteryLevel)
        assertNull(detail.batteryDelta)
        assertNull(detail.energyKwh)
        assertNull(detail.averagePowerKw)
        assertNull(detail.peakPowerKw)
        assertNull(detail.insideTempAverage)
        assertNull(detail.outsideTempAverage)
        assertNull(detail.linkedCharge)
    }

    @Test
    fun legacyJsonDoesNotNeedNewEndpointFields() {
        val detail = adapter.fromJson("""{
            "data": {
                "older_drive_id": 1, "newer_drive_id": 2,
                "start_date": "2026-01-01T08:30:00Z",
                "end_date": "2026-01-01T10:00:00Z",
                "address": "Observed parked place", "source": "database_latest"
            }
        }""")!!.data!!

        assertEquals("Observed parked place", detail.address)
        assertEquals("database_latest", detail.source)
        assertNull(detail.startAddress)
        assertNull(detail.endAddress)
    }

    @Test
    fun legacyParkedDetailCanOmitLinkedCharge() {
        val detail = ParkedDetailData(
            olderDriveId = 1,
            newerDriveId = 2,
            startDate = "2026-08-01T00:00:00Z",
            endDate = "2026-08-01T03:00:00Z",
            source = "teslamate"
        )

        assertNull(detail.linkedCharge)
    }

    @Test
    fun linkedChargeUsesTheServerChargeIdForDirectNavigation() {
        val linked = LinkedCharge(chargeId = 42)

        assertEquals(42, linked.chargeId)
    }
}

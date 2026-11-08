package com.matelink.data.api.models

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test against the jourvolt nearby-charging-sites payload
 * (design 2026-11-05 §5). Mirrors the Go serializer's keys exactly.
 */
class NearbyChargingModelsTest {

    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(NearbyChargingResponse::class.java)

    @Test
    fun parsesBackendShape() {
        val parsed = adapter.fromJson(
            """{"data":{"sites":[
              {"name":"Near SC","type":"supercharger","latitude":30.1,"longitude":120.1,
               "distance_km":3.22,"available_stalls":5,"total_stalls":6,"site_closed":false},
              {"name":"Hotel","type":"destination","latitude":30.2,"longitude":120.15,
               "distance_km":8.05,"available_stalls":null,"total_stalls":null,"site_closed":null}
            ],"meta":{"source":"fleet_api"}}}"""
        ) ?: error("response missing")

        val sites = parsed.data?.sites ?: error("sites missing")
        assertEquals(2, sites.size)
        assertEquals("fleet_api", parsed.data?.meta?.source)
        assertTrue(sites[0].isSupercharger)
        assertEquals("5/6", sites[0].stallsText)
        assertEquals(3.22, sites[0].distanceKm ?: -1.0, 0.0)
        assertNull(sites[1].stallsText)
        assertNull(sites[1].availableStalls)
    }

    @Test
    fun parsesUnsupportedEmptyPayload() {
        val parsed = adapter.fromJson(
            """{"data":{"sites":[],"meta":{"source":"unsupported"}}}"""
        ) ?: error("response missing")
        assertEquals(0, parsed.data?.sites?.size)
        assertEquals("unsupported", parsed.data?.meta?.source)
    }
}

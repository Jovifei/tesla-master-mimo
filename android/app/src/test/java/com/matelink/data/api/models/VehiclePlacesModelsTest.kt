package com.matelink.data.api.models

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test against the jourvolt vehicle-places payloads
 * (design 2026-11-05 §7). Mirrors the Go serializer's keys exactly.
 */
class VehiclePlacesModelsTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Test
    fun parsesListShape() {
        val parsed = moshi.adapter(VehiclePlacesResponse::class.java).fromJson(
            """{"data":{"places":[
              {"id":1,"name":"家","latitude":30.2741,"longitude":120.1551,"radius_m":200},
              {"id":2,"name":"公司","latitude":30.3,"longitude":120.2,"radius_m":350}
            ]}}"""
        ) ?: error("response missing")
        val places = parsed.data?.places ?: error("places missing")
        assertEquals(2, places.size)
        assertEquals(1L, places[0].id)
        assertEquals("家", places[0].name)
        assertEquals(200, places[0].radiusM)
        assertEquals(350, places[1].radiusM)
    }

    @Test
    fun parsesEmptyListAndError() {
        val empty = moshi.adapter(VehiclePlacesResponse::class.java).fromJson(
            """{"data":{"places":[]}}"""
        ) ?: error("response missing")
        assertEquals(0, empty.data?.places?.size)

        val failed = moshi.adapter(VehiclePlacesResponse::class.java).fromJson(
            """{"error":"invalid_place_name"}"""
        ) ?: error("response missing")
        assertEquals("invalid_place_name", failed.error)
        assertNull(failed.data)
    }

    @Test
    fun requestSerializesBackendKeys() {
        val json = moshi.adapter(VehiclePlaceRequest::class.java).toJson(
            VehiclePlaceRequest(name = "家", latitude = 30.2741, longitude = 120.1551, radiusM = 300)
        )
        assertTrue(json.contains("\"name\":\"家\""))
        assertTrue(json.contains("\"radius_m\":300"))

        val created = moshi.adapter(VehiclePlaceResponse::class.java).fromJson(
            """{"data":{"id":7,"name":"家","latitude":30.2741,"longitude":120.1551,"radius_m":300}}"""
        ) ?: error("response missing")
        assertEquals(7L, created.data?.id)

        val deleted = moshi.adapter(VehiclePlaceDeleteResponse::class.java).fromJson(
            """{"data":{"deleted":true}}"""
        ) ?: error("response missing")
        assertEquals(true, deleted.data?.deleted)
    }
}

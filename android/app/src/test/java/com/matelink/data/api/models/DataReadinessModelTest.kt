package com.matelink.data.api.models

import com.matelink.ui.screens.readiness.ReadinessItemStatus
import com.matelink.ui.screens.readiness.readinessItemStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataReadinessModelTest {
    @Test
    fun parsesReadinessEnvelopeAndPreservesUnknownStatusAndNullableObservation() {
        val json = """
            {
              "data": {
                "capability_version": 3,
                "vehicle_uid": "vehicle-abc",
                "items": [
                  {"key":"location","status":"future_state","source":"fleet_api","last_observed_at":null,"message_key":"location_waiting_vehicle","action":"wake_vehicle"}
                ]
              }
            }
        """.trimIndent()

        val response = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(DataReadinessResponse::class.java)
            .fromJson(json)!!

        val item = response.data!!.items.single()
        assertEquals(3, response.data!!.capabilityVersion)
        assertEquals("vehicle-abc", response.data!!.vehicleUid)
        assertEquals("future_state", item.status)
        assertNull(item.lastObservedAt)
        assertEquals(ReadinessItemStatus.UNKNOWN, readinessItemStatus(item.status))
    }

    @Test
    fun knownStatusesMapToLocalizedPresentationCategories() {
        assertEquals(ReadinessItemStatus.AVAILABLE, readinessItemStatus("available"))
        assertEquals(ReadinessItemStatus.COLLECTING, readinessItemStatus("collecting"))
        assertEquals(ReadinessItemStatus.WAITING_VEHICLE, readinessItemStatus("waiting_vehicle"))
        assertEquals(ReadinessItemStatus.UNSUPPORTED, readinessItemStatus("unsupported"))
    }
}

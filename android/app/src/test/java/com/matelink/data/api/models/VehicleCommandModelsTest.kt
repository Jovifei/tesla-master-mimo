package com.matelink.data.api.models

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract test for the vehicle-command payloads (design 2026-11-05 §6). */
class VehicleCommandModelsTest {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun requestSerializesParamsAndConfirm() {
        val adapter = moshi.adapter(VehicleCommandRequest::class.java)
        val json = adapter.toJson(
            VehicleCommandRequest(params = mapOf("percent" to 90), confirm = true)
        )
        assertTrue(json.contains("\"percent\":90"))
        assertTrue(json.contains("\"confirm\":true"))
    }

    @Test
    fun successAndErrorResponsesParse() {
        val adapter = moshi.adapter(VehicleCommandResponse::class.java)
        val ok = adapter.fromJson(
            """{"data":{"command":"lock","result":"unverified","verified":false}}"""
        ) ?: error("response missing")
        assertEquals("unverified", ok.data?.result)
        assertEquals(false, ok.data?.verified)

        val denied = adapter.fromJson(
            """{"error":"scope_missing","missing_scopes":["vehicle_cmds"]}"""
        ) ?: error("response missing")
        assertEquals("scope_missing", denied.error)
        assertEquals(listOf("vehicle_cmds"), denied.missingScopes)
    }
}

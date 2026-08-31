package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TelemetryPairingResponse(
    @Json(name = "data") val data: TelemetryPairingStatus? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class TelemetryPairingStatus(
    @Json(name = "status") val status: String = "unknown",
    @Json(name = "virtual_key_url") val virtualKeyUrl: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "config_synced") val configSynced: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class TelemetryConfigureResponse(
    @Json(name = "data") val data: TelemetryConfigureResult? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class TelemetryConfigureResult(
    @Json(name = "status") val status: String = "waiting_vehicle"
)

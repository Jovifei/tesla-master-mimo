package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DataReadinessResponse(
    @Json(name = "data") val data: DataReadiness? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class DataReadiness(
    @Json(name = "capability_version") val capabilityVersion: Int? = null,
    @Json(name = "vehicle_uid") val vehicleUid: String? = null,
    @Json(name = "items") val items: List<DataReadinessItem> = emptyList()
) {
    fun item(key: String): DataReadinessItem? = items.firstOrNull { it.key == key }
}

@JsonClass(generateAdapter = true)
data class DataReadinessItem(
    @Json(name = "key") val key: String,
    @Json(name = "status") val status: String,
    @Json(name = "source") val source: String,
    @Json(name = "last_observed_at") val lastObservedAt: String? = null,
    @Json(name = "message_key") val messageKey: String? = null,
    @Json(name = "action") val action: String? = null
)

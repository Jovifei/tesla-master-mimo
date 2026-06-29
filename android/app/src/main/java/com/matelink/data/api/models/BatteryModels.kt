package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BatteryHealthResponse(
    @Json(name = "data") val data: BatteryHealthData? = null
)

@JsonClass(generateAdapter = true)
data class BatteryHealthData(
    @Json(name = "battery_health") val batteryHealth: BatteryHealth? = null
)

@JsonClass(generateAdapter = true)
data class BatteryHealth(
    @Json(name = "max_range") val maxRange: Double? = null,
    @Json(name = "current_range") val currentRange: Double? = null,
    @Json(name = "max_capacity") val maxCapacity: Double? = null,
    @Json(name = "current_capacity") val currentCapacity: Double? = null,
    @Json(name = "rated_efficiency") val ratedEfficiency: Double? = null,
    @Json(name = "battery_health_percentage") val batteryHealthPercentage: Double? = null
)

@JsonClass(generateAdapter = true)
data class UpdatesResponse(
    @Json(name = "data") val data: UpdatesResponseData? = null
)

@JsonClass(generateAdapter = true)
data class UpdatesResponseData(
    @Json(name = "updates") val updates: List<UpdateData>? = null
)

@JsonClass(generateAdapter = true)
data class UpdateData(
    @Json(name = "update_id") val id: Int? = null,
    @Json(name = "version") val version: String? = null,
    @Json(name = "start_date") val startDate: String? = null,
    @Json(name = "end_date") val endDate: String? = null
)

@JsonClass(generateAdapter = true)
data class PingResponse(
    @Json(name = "ping") val ping: String? = null
)

package com.matelink.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BatteryHealthResponse(
    val data: BatteryHealthData
)

@JsonClass(generateAdapter = true)
data class BatteryHealthData(
    @Json(name = "car_id") val carId: Int,
    val date: String,
    @Json(name = "battery_level") val batteryLevel: Int,
    @Json(name = "rated_range_km") val ratedRangeKm: Double,
    @Json(name = "ideal_range_km") val idealRangeKm: Double,
    val odometer: Double,
    @Json(name = "outside_temp") val outsideTemp: Double
)

@JsonClass(generateAdapter = true)
data class UpdatesResponse(
    val data: UpdatesData
)

@JsonClass(generateAdapter = true)
data class UpdatesData(
    val updates: List<SoftwareUpdate>
)

@JsonClass(generateAdapter = true)
data class SoftwareUpdate(
    val id: Int,
    val version: String,
    val date: String,
    @Json(name = "install_date") val installDate: String?
)

package com.matelink.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DrivesResponse(
    val data: DrivesData
)

@JsonClass(generateAdapter = true)
data class DrivesData(
    val drives: List<Drive>
)

@JsonClass(generateAdapter = true)
data class DriveDetailResponse(
    val data: Drive
)

@JsonClass(generateAdapter = true)
data class Drive(
    val id: Int,
    @Json(name = "start_date") val startDate: String,
    @Json(name = "end_date") val endDate: String,
    @Json(name = "distance_km") val distanceKm: Double,
    @Json(name = "duration_min") val durationMin: Int,
    @Json(name = "start_address") val startAddress: String?,
    @Json(name = "end_address") val endAddress: String?,
    @Json(name = "start_battery_level") val startBatteryLevel: Int,
    @Json(name = "end_battery_level") val endBatteryLevel: Int,
    @Json(name = "outside_temp_avg") val outsideTempAvg: Double?,
    @Json(name = "speed_max") val speedMax: Double?,
    @Json(name = "power_max") val powerMax: Double?,
    val efficiency: Double?,
    @Json(name = "elevation_gain") val elevationGain: Double?
)

package com.matelink.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CarsResponse(
    val data: CarsData
)

@JsonClass(generateAdapter = true)
data class CarsData(
    val cars: List<Car>
)

@JsonClass(generateAdapter = true)
data class CarResponse(
    val data: CarsData
)

@JsonClass(generateAdapter = true)
data class Car(
    @Json(name = "car_id") val carId: Int,
    val name: String?,
    @Json(name = "car_details") val carDetails: CarDetails,
    @Json(name = "car_exterior") val carExterior: CarExterior,
    @Json(name = "car_settings") val carSettings: CarSettings?,
    @Json(name = "teslamate_stats") val teslamateStats: TeslaMateStats?
)

@JsonClass(generateAdapter = true)
data class CarDetails(
    val eid: Long,
    val vid: Long,
    val vin: String,
    val model: String?,
    @Json(name = "trim_badging") val trimBadging: String?,
    val efficiency: Double?
)

@JsonClass(generateAdapter = true)
data class CarExterior(
    @Json(name = "exterior_color") val exteriorColor: String,
    @Json(name = "spoiler_type") val spoilerType: String,
    @Json(name = "wheel_type") val wheelType: String
)

@JsonClass(generateAdapter = true)
data class CarSettings(
    @Json(name = "suspend_min") val suspendMin: Int,
    @Json(name = "suspend_after_idle_min") val suspendAfterIdleMin: Int,
    @Json(name = "req_not_unlocked") val reqNotUnlocked: Boolean,
    @Json(name = "free_supercharging") val freeSupercharging: Boolean
)

@JsonClass(generateAdapter = true)
data class TeslaMateStats(
    @Json(name = "total_charges") val totalCharges: Int,
    @Json(name = "total_drives") val totalDrives: Int,
    @Json(name = "total_updates") val totalUpdates: Int
)

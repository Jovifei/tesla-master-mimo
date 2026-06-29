package com.matelink.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ChargesResponse(
    val data: ChargesData
)

@JsonClass(generateAdapter = true)
data class ChargesData(
    val charges: List<Charge>
)

@JsonClass(generateAdapter = true)
data class ChargeDetailResponse(
    val data: Charge
)

@JsonClass(generateAdapter = true)
data class Charge(
    val id: Int,
    @Json(name = "start_date") val startDate: String,
    @Json(name = "end_date") val endDate: String,
    @Json(name = "charge_energy_added") val chargeEnergyAdded: Double,
    @Json(name = "start_battery_level") val startBatteryLevel: Int,
    @Json(name = "end_battery_level") val endBatteryLevel: Int,
    @Json(name = "duration_min") val durationMin: Int,
    val cost: Double?,
    val address: String?,
    @Json(name = "charging_type") val chargingType: String, // AC or DC
    @Json(name = "power_max") val powerMax: Double?,
    @Json(name = "outside_temp_avg") val outsideTempAvg: Double?
)

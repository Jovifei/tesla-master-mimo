package com.matelink.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CarStatusResponse(
    val data: CarStatus
)

@JsonClass(generateAdapter = true)
data class CarStatus(
    @Json(name = "car_id") val carId: Int,
    val state: String, // online, offline, asleep, charging, driving
    val since: String,
    val healthy: Boolean,
    val odometer: Double,
    @Json(name = "battery_level") val batteryLevel: Int,
    @Json(name = "usable_battery_level") val usableBatteryLevel: Int,
    @Json(name = "usable_battery_range_km") val usableBatteryRangeKm: Double,
    @Json(name = "ideal_battery_range_km") val idealBatteryRangeKm: Double,
    @Json(name = "charge_limit_soc") val chargeLimitSoc: Int,
    @Json(name = "charger_power") val chargerPower: Double,
    @Json(name = "charge_energy_added") val chargeEnergyAdded: Double,
    @Json(name = "time_to_full_charge") val timeToFullCharge: Double,
    @Json(name = "inside_temp") val insideTemp: Double,
    @Json(name = "outside_temp") val outsideTemp: Double,
    @Json(name = "is_climate_on") val isClimateOn: Boolean,
    val locked: Boolean,
    @Json(name = "sentry_mode") val sentryMode: Boolean,
    @Json(name = "plugged_in") val pluggedIn: Boolean,
    @Json(name = "tire_pressure_front_left") val tirePressureFrontLeft: Double,
    @Json(name = "tire_pressure_front_right") val tirePressureFrontRight: Double,
    @Json(name = "tire_pressure_rear_left") val tirePressureRearLeft: Double,
    @Json(name = "tire_pressure_rear_right") val tirePressureRearRight: Double,
    val latitude: Double,
    val longitude: Double,
    val elevation: Double,
    val speed: Double?,
    val power: Double?,
    val heading: Double?
)

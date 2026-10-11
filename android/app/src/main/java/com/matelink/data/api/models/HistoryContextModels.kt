package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class HistoryContextResponse(@Json(name = "data") val data: HistoryContextData? = null)

/** Authenticated, persisted vehicle identity; never derived from a response URL or VIN. */
@JsonClass(generateAdapter = true)
data class HistoryContextData(
    @Json(name = "capability_version") val capabilityVersion: Int? = null,
    @Json(name = "car_id") val carId: Int? = null,
    @Json(name = "vehicle_uid") val vehicleUid: String? = null
)

internal fun validHistoryVehicleUid(uid: String?): Boolean =
    uid != null && uid.isNotBlank() && uid == uid.trim() && uid.toByteArray(Charsets.UTF_8).size in 1..256

internal fun HistoryContextData.isValidFor(expectedCarId: Int): Boolean =
    capabilityVersion == 1 && expectedCarId > 0 && carId == expectedCarId && validHistoryVehicleUid(vehicleUid)

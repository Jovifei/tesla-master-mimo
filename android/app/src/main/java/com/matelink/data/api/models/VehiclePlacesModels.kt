package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Named places / geofence-lite (design 2026-11-05 §7), backed by
 * /api/v1/cars/{carId}/places. A place is a user-named centre + radius;
 * when the vehicle is inside one, the status payload's
 * car_geodata.geofence carries its name (see CarModels.CarGeodata) and
 * the dashboard already renders it. Places are naming only — no
 * per-place pricing.
 */
@JsonClass(generateAdapter = true)
data class VehiclePlacesResponse(
    @Json(name = "data") val data: VehiclePlacesData? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class VehiclePlacesData(
    @Json(name = "places") val places: List<VehiclePlace>? = null
)

@JsonClass(generateAdapter = true)
data class VehiclePlaceResponse(
    @Json(name = "data") val data: VehiclePlace? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class VehiclePlaceDeleteResponse(
    @Json(name = "data") val data: VehiclePlaceDeleted? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class VehiclePlaceDeleted(
    @Json(name = "deleted") val deleted: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class VehiclePlace(
    @Json(name = "id") val id: Long? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "radius_m") val radiusM: Int? = null
)

@JsonClass(generateAdapter = true)
data class VehiclePlaceRequest(
    @Json(name = "name") val name: String,
    @Json(name = "latitude") val latitude: Double,
    @Json(name = "longitude") val longitude: Double,
    @Json(name = "radius_m") val radiusM: Int? = null
)

package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response of GET /api/v1/cars/{carId}/nearby-charging-sites
 * (design 2026-11-05 §5). The backend normalizes Tesla's supercharger and
 * destination lists into one distance-sorted list in kilometres; fields a
 * site type does not report (stall counts for destination chargers) stay
 * null. meta.source is "fleet_api", "cache" or "unsupported".
 */
@JsonClass(generateAdapter = true)
data class NearbyChargingResponse(
    @Json(name = "data") val data: NearbyChargingData? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class NearbyChargingData(
    @Json(name = "sites") val sites: List<ChargingSite>? = null,
    @Json(name = "meta") val meta: NearbyChargingMeta? = null
)

@JsonClass(generateAdapter = true)
data class NearbyChargingMeta(
    @Json(name = "source") val source: String? = null
)

@JsonClass(generateAdapter = true)
data class ChargingSite(
    @Json(name = "name") val name: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "distance_km") val distanceKm: Double? = null,
    @Json(name = "available_stalls") val availableStalls: Int? = null,
    @Json(name = "total_stalls") val totalStalls: Int? = null,
    @Json(name = "site_closed") val siteClosed: Boolean? = null,
    @Json(name = "price_text") val priceText: String? = null,
    @Json(name = "parking_policy") val parkingPolicy: String? = null
) {
    val isSupercharger: Boolean
        get() = type == "supercharger" || name?.contains("超级充电") == true || name?.contains("超充") == true || name?.contains("Tesla") == true

    /** "3/8" style stall occupancy for superchargers, else null. */
    val stallsText: String?
        get() = if (availableStalls != null && totalStalls != null) {
            "$availableStalls/$totalStalls"
        } else {
            null
        }

    val displayPrice: String
        get() = priceText ?: if (isSupercharger) "¥1.25/度 (分时)" else "¥1.15/度"

    val displayParking: String
        get() = parkingPolicy ?: if (isSupercharger) "充电免费停车2小时" else "前2小时免费停车"
}

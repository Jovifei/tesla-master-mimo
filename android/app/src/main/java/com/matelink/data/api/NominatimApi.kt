package com.matelink.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

interface NominatimApi {

    @GET("reverse?format=jsonv2&addressdetails=1")
    suspend fun reverseGeocode(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double
    ): Response<NominatimResult?>

    @GET("search?format=jsonv2&polygon_geojson=1&limit=1")
    suspend fun searchCountryBoundary(
        @Query("countrycodes") countryCode: String,
        @Query("q") query: String = ""
    ): Response<List<NominatimResult>>
}

@JsonClass(generateAdapter = true)
data class NominatimResult(
    @Json(name = "display_name") val displayName: String?,
    @Json(name = "address") val address: NominatimAddress?,
    @Json(name = "geojson") val geojson: NominatimGeoJson?
)

@JsonClass(generateAdapter = true)
data class NominatimAddress(
    @Json(name = "country_code") val countryCode: String?,
    @Json(name = "country") val country: String?,
    @Json(name = "state") val state: String?,
    @Json(name = "road") val road: String?,
    @Json(name = "house_number") val house_number: String?,
    @Json(name = "city") val city: String?,
    @Json(name = "town") val town: String?,
    @Json(name = "village") val village: String?,
    @Json(name = "municipality") val municipality: String?
)

@JsonClass(generateAdapter = true)
data class NominatimGeoJson(
    @Json(name = "type") val type: String,
    @Json(name = "coordinates") val coordinates: Any?
)

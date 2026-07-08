package com.matelink.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface OpenMeteoApi {

    @GET("v1/archive?hourly=temperature_2m,weathercode")
    suspend fun getHistoricalWeather(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String
    ): Response<OpenMeteoWeatherResponse>
}

@JsonClass(generateAdapter = true)
data class OpenMeteoWeatherResponse(
    @Json(name = "hourly") val hourly: HourlyWeatherData?
)

@JsonClass(generateAdapter = true)
data class HourlyWeatherData(
    @Json(name = "time") val time: List<String>?,
    @Json(name = "temperature_2m") val temperature2m: List<Double>?,
    @Json(name = "weathercode") val weatherCode: List<Int>?
)

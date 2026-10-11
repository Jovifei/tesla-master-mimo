package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TripSummariesUploadRequest(
    @Json(name = "trips") val trips: List<TripSummaryDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TripSummaryDto(
    @Json(name = "source") val source: String,
    @Json(name = "source_id") val sourceId: String,
    @Json(name = "started_at") val startedAt: String,
    @Json(name = "ended_at") val endedAt: String,
    @Json(name = "distance_km") val distanceKm: Double? = null,
    @Json(name = "duration_min") val durationMin: Int? = null,
    @Json(name = "energy_kwh") val energyKwh: Double? = null,
    @Json(name = "cost_yuan") val costYuan: Double? = null,
    @Json(name = "start_address") val startAddress: String? = null,
    @Json(name = "end_address") val endAddress: String? = null,
    @Json(name = "lat_start") val latStart: Double? = null,
    @Json(name = "lon_start") val lonStart: Double? = null,
    @Json(name = "lat_end") val latEnd: Double? = null,
    @Json(name = "lon_end") val lonEnd: Double? = null
)

@JsonClass(generateAdapter = true)
data class ChargeSummariesUploadRequest(
    @Json(name = "charges") val charges: List<ChargeSummaryDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ChargeSummaryDto(
    @Json(name = "source") val source: String,
    @Json(name = "source_id") val sourceId: String,
    @Json(name = "started_at") val startedAt: String,
    @Json(name = "ended_at") val endedAt: String,
    @Json(name = "energy_kwh") val energyKwh: Double? = null,
    @Json(name = "cost_yuan") val costYuan: Double? = null,
    @Json(name = "station_name") val stationName: String? = null,
    @Json(name = "charger_type") val chargerType: String? = null,
    @Json(name = "is_fast") val isFast: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class SummariesUploadResponse(
    @Json(name = "data") val data: SummariesUploadResult? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class SummariesUploadResult(
    @Json(name = "inserted") val inserted: Int = 0,
    @Json(name = "updated") val updated: Int = 0
)

@JsonClass(generateAdapter = true)
data class TripSummariesResponse(
    @Json(name = "data") val data: TripSummariesResult? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class TripSummariesResult(
    @Json(name = "trips") val trips: List<TripSummaryDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ChargeSummariesResponse(
    @Json(name = "data") val data: ChargeSummariesResult? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class ChargeSummariesResult(
    @Json(name = "charges") val charges: List<ChargeSummaryDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class ValidateInviteRequest(
    @Json(name = "code") val code: String
)

@JsonClass(generateAdapter = true)
data class ValidateInviteResponse(
    @Json(name = "valid") val valid: Boolean = false,
    @Json(name = "code") val code: String? = null,
    @Json(name = "remaining") val remaining: Int? = null,
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "error") val error: String? = null
)

package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Payload for uploading previously-collected local history to the cloud.
 * The cloud persists only the latest two calendar days per account.
 */
@JsonClass(generateAdapter = true)
data class HistoryImportRequest(
    @Json(name = "drives") val drives: List<HistoryImportSession> = emptyList(),
    @Json(name = "charges") val charges: List<HistoryImportSession> = emptyList()
)

@JsonClass(generateAdapter = true)
data class HistoryImportSession(
    @Json(name = "session_id") val sessionId: String? = null,
    @Json(name = "started_at") val startedAt: String,
    @Json(name = "ended_at") val endedAt: String,
    @Json(name = "odometer_start") val odometerStart: Double? = null,
    @Json(name = "odometer_end") val odometerEnd: Double? = null,
    @Json(name = "energy_added") val energyAdded: Double? = null,
    @Json(name = "route") val route: List<HistoryImportRoutePoint> = emptyList()
)

@JsonClass(generateAdapter = true)
data class HistoryImportRoutePoint(
    @Json(name = "date") val date: String? = null,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "speed") val speed: Double? = null,
    @Json(name = "power") val power: Double? = null,
    @Json(name = "heading") val heading: Double? = null
)

@JsonClass(generateAdapter = true)
data class HistoryImportResponse(
    @Json(name = "data") val data: HistoryImportResult? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class HistoryImportResult(
    @Json(name = "imported_drives") val importedDrives: Int = 0,
    @Json(name = "imported_charges") val importedCharges: Int = 0,
    @Json(name = "retained_days") val retainedDays: List<String> = emptyList()
)

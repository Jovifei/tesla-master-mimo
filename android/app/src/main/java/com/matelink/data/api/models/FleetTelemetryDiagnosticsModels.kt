package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Sanitized official read results. Missing fields remain unknown on every branch. */
@JsonClass(generateAdapter = true)
data class FleetTelemetryDiagnosticsResponse(
    @Json(name = "data") val data: FleetTelemetryDiagnostics? = null
)

@JsonClass(generateAdapter = true)
data class FleetTelemetryDiagnostics(
    @Json(name = "checked_at") val checkedAt: String? = null,
    @Json(name = "build_sha") val buildSha: String? = null,
    @Json(name = "availability") val availability: String? = null,
    @Json(name = "requests") val requests: FleetDiagnosticRequests? = null,
    @Json(name = "vehicle") val vehicle: FleetDiagnosticVehicle? = null,
    @Json(name = "configuration") val configuration: FleetDiagnosticConfiguration? = null,
    @Json(name = "recent_errors") val recentErrors: FleetDiagnosticRecentErrors? = null
)

@JsonClass(generateAdapter = true)
data class FleetDiagnosticRequests(
    @Json(name = "fleet_status") val fleetStatus: FleetDiagnosticRequest? = null,
    @Json(name = "fleet_telemetry_config") val fleetTelemetryConfig: FleetDiagnosticRequest? = null,
    @Json(name = "fleet_telemetry_errors") val fleetTelemetryErrors: FleetDiagnosticRequest? = null
)

@JsonClass(generateAdapter = true)
data class FleetDiagnosticRequest(
    @Json(name = "status") val status: String? = null,
    @Json(name = "http_status") val httpStatus: Int? = null,
    @Json(name = "error_class") val errorClass: String? = null
)

@JsonClass(generateAdapter = true)
data class FleetDiagnosticVehicle(
    @Json(name = "paired") val paired: Boolean? = null,
    @Json(name = "firmware_version") val firmwareVersion: String? = null,
    @Json(name = "fleet_telemetry_version") val fleetTelemetryVersion: String? = null,
    @Json(name = "streaming_enabled") val streamingEnabled: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class FleetDiagnosticConfiguration(
    @Json(name = "hostname") val hostname: String? = null,
    @Json(name = "port") val port: Int? = null,
    @Json(name = "synced") val synced: Boolean? = null,
    @Json(name = "ca_sha256") val caSha256: String? = null,
    @Json(name = "desired") val desired: FleetDiagnosticDesiredConfiguration? = null,
    @Json(name = "matches_desired") val matchesDesired: FleetDiagnosticConfigurationMatches? = null
)

@JsonClass(generateAdapter = true)
data class FleetDiagnosticDesiredConfiguration(
    @Json(name = "hostname") val hostname: String? = null,
    @Json(name = "port") val port: Int? = null,
    @Json(name = "fields") val fields: Map<String, FleetDiagnosticField>? = null,
    @Json(name = "ca_sha256") val caSha256: String? = null
)

@JsonClass(generateAdapter = true)
data class FleetDiagnosticField(
    @Json(name = "interval_seconds") val intervalSeconds: Int? = null,
    @Json(name = "minimum_delta") val minimumDelta: Double? = null
)

@JsonClass(generateAdapter = true)
data class FleetDiagnosticConfigurationMatches(
    @Json(name = "hostname") val hostname: Boolean? = null,
    @Json(name = "port") val port: Boolean? = null,
    @Json(name = "fields") val fields: Boolean? = null,
    @Json(name = "ca") val ca: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class FleetDiagnosticRecentErrors(
    @Json(name = "counts") val counts: Map<String, Int>? = null,
    @Json(name = "last_at") val lastAt: String? = null
)

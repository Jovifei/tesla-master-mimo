package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AdapterCapabilitiesResponse(
    @Json(name = "data") val data: AdapterCapabilities? = null
)

@JsonClass(generateAdapter = true)
data class AdapterCapabilities(
    @Json(name = "adapter_version") val adapterVersion: String? = null,
    @Json(name = "features") val features: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AdapterSnapshotResponse(
    @Json(name = "data") val data: AdapterSnapshot? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class AdapterSnapshot(
    @Json(name = "status") val status: CarStatus,
    @Json(name = "units") val units: Units = Units(),
    @Json(name = "observed_at") val observedAt: String? = null,
    @Json(name = "source") val source: String,
    @Json(name = "field_sources") val fieldSources: Map<String, String> = emptyMap()
)

/** Optional metadata shared by history, health, and compatibility responses. */
@JsonClass(generateAdapter = true)
data class ApiDataMeta(
    @Json(name = "availability") val availability: String? = null,
    @Json(name = "source") val source: String? = null,
    @Json(name = "observed_at") val observedAt: String? = null,
    @Json(name = "collection_started_at") val collectionStartedAt: String? = null,
    @Json(name = "coverage_percent") val coveragePercent: Double? = null
)

@JsonClass(generateAdapter = true)
data class ParkedDetailResponse(
    @Json(name = "data") val data: ParkedDetailData? = null,
    @Json(name = "error") val error: String? = null
)

@JsonClass(generateAdapter = true)
data class ParkedDetailData(
    @Json(name = "older_drive_id") val olderDriveId: Int,
    @Json(name = "newer_drive_id") val newerDriveId: Int,
    @Json(name = "start_date") val startDate: String,
    @Json(name = "end_date") val endDate: String,
    @Json(name = "address") val address: String? = null,
    @Json(name = "start_battery_level") val startBatteryLevel: Int? = null,
    @Json(name = "end_battery_level") val endBatteryLevel: Int? = null,
    @Json(name = "battery_delta") val batteryDelta: Int? = null,
    @Json(name = "energy_kwh") val energyKwh: Double? = null,
    @Json(name = "average_power_kw") val averagePowerKw: Double? = null,
    @Json(name = "peak_power_kw") val peakPowerKw: Double? = null,
    @Json(name = "inside_temp_average") val insideTempAverage: Double? = null,
    @Json(name = "outside_temp_average") val outsideTempAverage: Double? = null,
    @Json(name = "sample_count") val sampleCount: Int = 0,
    @Json(name = "coverage_seconds") val coverageSeconds: Long = 0,
    @Json(name = "coverage_ratio") val coverageRatio: Double = 0.0,
    @Json(name = "source") val source: String
)

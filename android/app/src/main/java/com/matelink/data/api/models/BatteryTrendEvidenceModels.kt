package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BatteryTrendEvidenceResponse(@Json(name = "data") val data: BatteryTrendEvidencePage? = null)

@JsonClass(generateAdapter = true)
data class BatteryTrendEvidencePage(
    @Json(name = "schema_version") val schemaVersion: Int? = null,
    @Json(name = "units") val units: BatteryTrendEvidenceUnits? = null,
    @Json(name = "snapshot_max_seq") val snapshotMaxSeq: Long? = null,
    @Json(name = "next_cursor") val nextCursor: String? = null,
    @Json(name = "complete") val complete: Boolean? = null,
    @Json(name = "observations") val observations: List<BatteryTrendEvidenceItem>? = null
)

@JsonClass(generateAdapter = true)
data class BatteryTrendEvidenceUnits(
    @Json(name = "rated_range") val ratedRange: String? = null,
    @Json(name = "temperature") val temperature: String? = null,
    @Json(name = "soc") val soc: String? = null
)

@JsonClass(generateAdapter = true)
data class BatteryTrendEvidenceItem(
    @Json(name = "source_instance_id") val sourceInstanceId: String? = null,
    @Json(name = "source_vehicle_id") val sourceVehicleId: String? = null,
    @Json(name = "payload_sha256") val payloadSha256: String? = null,
    @Json(name = "observation") val observation: BatteryTrendObservation? = null
)

@JsonClass(generateAdapter = true)
data class BatteryTrendObservation(
    @Json(name = "source_record_id") val sourceRecordId: String? = null,
    @Json(name = "started_at") val startedAt: String? = null,
    @Json(name = "ended_at") val endedAt: String? = null,
    @Json(name = "start") val start: BatteryTrendBoundary? = null,
    @Json(name = "end") val end: BatteryTrendBoundary? = null,
    @Json(name = "outside_temp_avg_c") val outsideTempAvgC: Double? = null,
    @Json(name = "temperature_source") val temperatureSource: String? = null
)

@JsonClass(generateAdapter = true)
data class BatteryTrendBoundary(
    @Json(name = "soc_percent") val socPercent: Double? = null,
    @Json(name = "soc_position_observed_at") val socPositionObservedAt: String? = null,
    @Json(name = "soc_position_id") val socPositionId: Long? = null,
    @Json(name = "rated_range_km") val ratedRangeKm: Double? = null,
    @Json(name = "rated_range_source") val ratedRangeSource: String? = null,
    @Json(name = "rated_range_observed_at") val ratedRangeObservedAt: String? = null
)

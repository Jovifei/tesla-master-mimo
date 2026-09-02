package com.matelink.domain.telemetry

import java.time.Instant
import java.time.OffsetDateTime

enum class SnapshotFreshness { LIVE, RECENT, HISTORY, UNAVAILABLE }

data class SnapshotEvidence(
    val freshness: SnapshotFreshness,
    val source: String?,
    val observedAt: String?,
    val fieldSources: Map<String, String>,
    val isMixed: Boolean
)

fun snapshotEvidence(
    source: String?,
    observedAt: String?,
    fieldSources: Map<String, String>,
    now: Instant = Instant.now(),
    freshnessSeconds: Long = 120
): SnapshotEvidence {
    val normalizedSource = source?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
    val normalizedFields = fieldSources.mapValues { it.value.trim().lowercase() }
    val observed = parseObservedInstant(observedAt)
    val fresh = observed?.let {
        !it.isAfter(now.plusSeconds(5)) && now.epochSecond - it.epochSecond <= freshnessSeconds
    } == true
    val fieldKinds = normalizedFields.values.filter(String::isNotEmpty).toSet()
    val mixed = fieldKinds.size > 1 || (normalizedSource != null && fieldKinds.any { it != normalizedSource })
    val freshness = when (normalizedSource) {
        "live_mqtt" -> if (fresh && (normalizedFields.isEmpty() || normalizedFields.values.any { it == "live_mqtt" })) SnapshotFreshness.LIVE else SnapshotFreshness.RECENT
        "fleet_api" -> if (observedAt.isNullOrBlank() || fresh) SnapshotFreshness.LIVE else SnapshotFreshness.RECENT
        "mqtt_latest" -> SnapshotFreshness.RECENT
        "database_latest", "teslamate_api", "mock_fixture" -> SnapshotFreshness.HISTORY
        else -> SnapshotFreshness.UNAVAILABLE
    }
    return SnapshotEvidence(freshness, normalizedSource, observedAt, normalizedFields, mixed)
}

fun usableVehicleCoordinates(latitude: Double?, longitude: Double?): Pair<Double, Double>? {
    val lat = latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
    val lon = longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
    if (lat == 0.0 && lon == 0.0) return null
    return lat to lon
}

private fun parseObservedInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
}

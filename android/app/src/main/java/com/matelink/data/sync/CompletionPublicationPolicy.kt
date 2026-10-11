package com.matelink.data.sync

import com.matelink.data.local.CompletedChargeEvent
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import java.time.Instant

/**
 * Notification receipts are not Fleet first-event proof. Only a complete
 * observed source record, with an actual past endpoint, can publish one.
 * Legacy self-hosted API rows may omit source; a cloud import/archive cannot
 * impersonate a new natural Fleet drive or charge.
 */
internal fun eligibleCompletedPublication(
    kind: String, quality: String, rawQuality: String?, rawReason: String?, rawSource: String?,
    connectionSource: HistoryConnectionSource, start: String?, end: String?,
    now: Instant = Instant.now()
): Boolean {
    if (kind !in setOf("drive", "charge")) return false
    if (rawSource in setOf("local_import", "local_history")) return false
    if (connectionSource == HistoryConnectionSource.CLOUD &&
        (rawSource !in setOf("telemetry_mqtt", "fleet_api") || rawQuality == null)) return false
    val observed = quality == "observed" && (rawQuality == null || rawQuality == "observed")
    // A completed Fleet charge may have an unknown/incomplete energy counter:
    // completion is evidenced by the real session boundary, NOT by kWh.
    // Do not loosen drive proof or unverified/unknown-source imports.
    val chargeWithUnknownEnergy = kind == "charge" && quality == "incomplete" &&
        rawQuality == "incomplete" &&
        ((rawSource == "telemetry_mqtt" && rawReason == "partial_or_missing_charge_counter") ||
         (rawSource == "fleet_api" && rawReason == "missing_charge_measurement"))
    if (!observed && !chargeWithUnknownEnergy) return false
    val started = start?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
    val ended = end?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
    return started.isBefore(ended) && !ended.isAfter(now)
}

internal fun DriveSummary.canPublishCompletion(
    source: HistoryConnectionSource, now: Instant = Instant.now()
): Boolean {
    val raw = apiEvidence?.let(HistorySummaryEvidenceCodec::decodeDrive) ?: return false
    return raw.driveId == driveId && eligibleCompletedPublication(
        "drive", qualityState, raw.qualityState, raw.qualityReason, raw.source, source, raw.startDate, raw.endDate, now
    )
}

internal fun ChargeSummary.canPublishCompletion(
    source: HistoryConnectionSource, now: Instant = Instant.now()
): Boolean {
    val raw = apiEvidence?.let(HistorySummaryEvidenceCodec::decodeCharge) ?: return false
    return raw.chargeId == chargeId && eligibleCompletedPublication(
        "charge", qualityState, raw.qualityState, raw.qualityReason, raw.source, source, raw.startDate, raw.endDate, now
    )
}

private fun sameCompletionInstant(eventTime: String, sourceTime: String?): Boolean {
    val event = runCatching { Instant.parse(eventTime) }.getOrNull() ?: return false
    val source = sourceTime?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
    return event == source
}

/** Prevent reusing a pending numeric event for a different source session. */
internal fun CompletedChargeEvent.matchesDrive(summary: DriveSummary): Boolean =
    kind == "drive" && carId == summary.carId && chargeId == summary.driveId &&
        sameCompletionInstant(endDate, summary.apiEvidence
            ?.let(HistorySummaryEvidenceCodec::decodeDrive)?.endDate)

internal fun CompletedChargeEvent.matchesCharge(summary: ChargeSummary): Boolean =
    kind == "charge" && carId == summary.carId && chargeId == summary.chargeId &&
        sameCompletionInstant(endDate, summary.apiEvidence
            ?.let(HistorySummaryEvidenceCodec::decodeCharge)?.endDate)

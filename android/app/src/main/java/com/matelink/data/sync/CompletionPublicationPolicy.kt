package com.matelink.data.sync

import com.matelink.data.local.CompletedChargeEvent
import com.matelink.data.local.CompletionReceipt
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import java.time.Instant

/** Session completion and physical energy qualification are independent claims. */
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
    val chargeWithUnknownEnergy = kind == "charge" && quality == "incomplete" &&
        rawQuality == "incomplete" &&
        ((rawSource == "telemetry_mqtt" && rawReason == "partial_or_missing_charge_counter") ||
         (rawSource == "fleet_api" && rawReason == "missing_charge_measurement"))
    if (!observed && !chargeWithUnknownEnergy) return false
    val started = start?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
    val ended = end?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
    return started.isBefore(ended) && !ended.isAfter(now)
}

internal fun DriveSummary.completionReceipt(
    source: HistoryConnectionSource, now: Instant = Instant.now()
): CompletionReceipt? {
    val raw = apiEvidence?.let(HistorySummaryEvidenceCodec::decodeDrive) ?: return null
    if (raw.driveId != driveId || !eligibleCompletedPublication("drive", qualityState,
            raw.qualityState, raw.qualityReason, raw.source, source, raw.startDate, raw.endDate, now)) return null
    return CompletionReceipt(driveId, requireNotNull(raw.startDate), requireNotNull(raw.endDate), raw.source)
}

internal fun ChargeSummary.completionReceipt(
    source: HistoryConnectionSource, now: Instant = Instant.now()
): CompletionReceipt? {
    val raw = apiEvidence?.let(HistorySummaryEvidenceCodec::decodeCharge) ?: return null
    if (raw.chargeId != chargeId || !eligibleCompletedPublication("charge", qualityState,
            raw.qualityState, raw.qualityReason, raw.source, source, raw.startDate, raw.endDate, now)) return null
    return CompletionReceipt(chargeId, requireNotNull(raw.startDate), requireNotNull(raw.endDate), raw.source)
}

internal fun DriveSummary.canPublishCompletion(source: HistoryConnectionSource, now: Instant = Instant.now()) =
    completionReceipt(source, now) != null
internal fun ChargeSummary.canPublishCompletion(source: HistoryConnectionSource, now: Instant = Instant.now()) =
    completionReceipt(source, now) != null

private fun sameCompletionInstant(a: String?, b: String?): Boolean {
    val first = a?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
    val second = b?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
    return first == second
}

/** ID alone, an old unscoped event or a current display projection proves no receipt. */
internal fun CompletedChargeEvent.matchesDrive(summary: DriveSummary): Boolean {
    val raw = summary.apiEvidence?.let(HistorySummaryEvidenceCodec::decodeDrive) ?: return false
    return kind == "drive" && carId == summary.carId && chargeId == summary.driveId && raw.driveId == chargeId &&
        source == raw.source && sameCompletionInstant(startDate, raw.startDate) && sameCompletionInstant(endDate, raw.endDate)
}
internal fun CompletedChargeEvent.matchesCharge(summary: ChargeSummary): Boolean {
    val raw = summary.apiEvidence?.let(HistorySummaryEvidenceCodec::decodeCharge) ?: return false
    return kind == "charge" && carId == summary.carId && chargeId == summary.chargeId && raw.chargeId == chargeId &&
        source == raw.source && sameCompletionInstant(startDate, raw.startDate) && sameCompletionInstant(endDate, raw.endDate)
}

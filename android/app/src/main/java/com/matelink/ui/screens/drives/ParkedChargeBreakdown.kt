package com.matelink.ui.screens.drives

import com.matelink.data.api.models.LinkedCharge
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.util.parseIsoInstant
import java.time.Duration
import java.time.Instant

internal const val MAX_PARKED_CHARGE_SESSIONS = 100

data class ParkedChargeSession(
    val chargeId: Int,
    val start: Instant,
    val end: Instant?,
    val energyAddedKwh: Double?,
    val startBatteryLevel: Int?,
    val endBatteryLevel: Int?,
    val startSocObservedAt: Instant?,
    val endSocObservedAt: Instant?
) {
    val duration: Duration? get() = end?.takeIf { it.isAfter(start) }?.let { Duration.between(start, it) }
    val energyPerHourKwh: Double? get() {
        val hours = duration?.toMillis()?.toDouble()?.div(3_600_000.0)?.takeIf { it > 0.0 } ?: return null
        return energyAddedKwh?.takeIf { it.isFinite() && it >= 0.0 }?.div(hours)?.takeIf(Double::isFinite)
    }
}

data class ParkedChargeBreakdown(
    val sessions: List<ParkedChargeSession>,
    /** All stored sessions were returned by the bounded server query; physical archive coverage is separate. */
    val storedSessionsComplete: Boolean,
    val recordedChargingDuration: Duration?,
    val outsideRecordedChargingDuration: Duration?,
    val hasPartialOrInvalidSessions: Boolean,
    val nonChargingSocDeltaPoints: Int? = null,
    /** Positive is estimated noncharging SOC drain in percentage points per hour. */
    val nonChargingConsumptionPointsPerHour: Double? = null
)

/** Local receipt projection retains unknown energy as null and never grants it from a raw scalar. */
internal fun ChargeSummary.toParkedLinkedCharge(): LinkedCharge? {
    if (qualityState == "quarantined") return null
    val safe = toAnalysisChargeData()
    return LinkedCharge(
        chargeId = chargeId,
        startDate = startDate,
        endDate = endDate,
        energyAddedKwh = safe.batteryInputKwh,
        startBatteryLevel = safe.startBatteryLevel?.takeIf { it in 0..100 },
        endBatteryLevel = safe.endBatteryLevel?.takeIf { it in 0..100 }
    )
}

/** Joining uses the already verified localHistoryCarId in the caller, never a numeric remote car ID. */
internal fun parkedChargeBreakdown(
    parked: ParkedDetailData,
    localCharges: List<LinkedCharge>
): ParkedChargeBreakdown {
    val parkedStart = parseIsoInstant(parked.startDate)
    val parkedEnd = parseIsoInstant(parked.endDate)
    if (parkedStart == null || parkedEnd == null || !parkedEnd.isAfter(parkedStart))
        return ParkedChargeBreakdown(emptyList(), false, null, null, true)

    val server = parked.linkedCharges ?: listOfNotNull(parked.linkedCharge)
    val serverComplete = parked.linkedCharges != null && parked.chargeSessionsComplete == true
    var invalid = false
    fun normalize(charge: LinkedCharge, fromServer: Boolean): ParkedChargeSession? {
        val start = parseIsoInstant(charge.startDate)
        val end = parseIsoInstant(charge.endDate)
        if (charge.chargeId <= 0 || start == null || (end != null && !end.isAfter(start))) {
            invalid = true
            return null
        }
        if (!start.isBefore(parkedEnd) || !(end ?: parkedEnd).isAfter(parkedStart)) {
            if (fromServer) invalid = true
            return null
        }
        if (end == null || start.isBefore(parkedStart) || end.isAfter(parkedEnd)) invalid = true
        return ParkedChargeSession(
            charge.chargeId, start, end,
            charge.energyAddedKwh?.takeIf { it.isFinite() && it >= 0.0 },
            charge.startBatteryLevel?.takeIf { it in 0..100 },
            charge.endBatteryLevel?.takeIf { it in 0..100 },
            parseIsoInstant(charge.startSocObservedAt),
            parseIsoInstant(charge.endSocObservedAt)
        )
    }

    val serverRows = server.mapNotNull { normalize(it, true) }
    val localRows = localCharges.mapNotNull { normalize(it, false) }
    fun key(row: ParkedChargeSession) = Triple(row.chargeId, row.start, row.end)
    // A session id is unique within the verified history car. If a cached
    // receipt disagrees on the end time, it must not become a second charge.
    // Prefer the scoped server row for display but revoke completeness.
    val byId = linkedMapOf<Int, ParkedChargeSession>()
    for (row in serverRows) {
        if (byId.putIfAbsent(row.chargeId, row) != null) invalid = true
    }
    val missingLocal = localRows.any { row ->
        byId[row.chargeId]?.let { key(it) != key(row) } ?: true
    }
    for (row in localRows) {
        val existing = byId.putIfAbsent(row.chargeId, row)
        if (existing != null && key(existing) != key(row)) invalid = true
    }
    val combined = byId.values
        .sortedWith(compareBy<ParkedChargeSession> { it.start }.thenBy { it.chargeId })
    val capped = combined.take(MAX_PARKED_CHARGE_SESSIONS)
    val complete = serverComplete && !missingLocal && !invalid && combined.size <= MAX_PARKED_CHARGE_SESSIONS

    val intervals = capped.mapNotNull { session ->
        val end = session.end ?: return@mapNotNull null
        val clippedStart = maxOf(session.start, parkedStart)
        val clippedEnd = minOf(end, parkedEnd)
        if (!clippedEnd.isAfter(clippedStart)) null else clippedStart to clippedEnd
    }
    val duration = if (capped.any { it.end == null }) null else unionDuration(intervals)
    val outside = duration?.let { Duration.between(parkedStart, parkedEnd).minus(it) }
    val startSoc = parked.startBatteryLevel?.takeIf { it in 0..100 }
    val endSoc = parked.endBatteryLevel?.takeIf { it in 0..100 }
    val nonChargingDelta = if (complete && capped.isNotEmpty() && startSoc != null && endSoc != null &&
        outside?.let { !it.isZero && !it.isNegative } == true && capped.all(::hasQualifiedSocBoundaries) &&
        capped.zipWithNext().all { (a, b) -> a.end?.isAfter(b.start) == false }) {
        (endSoc - startSoc) - capped.sumOf { it.endBatteryLevel!! - it.startBatteryLevel!! }
    } else null
    val nonChargingRate = nonChargingDelta?.let { delta ->
        val hours = outside!!.toMillis().toDouble() / 3_600_000.0
        (-delta / hours).takeIf(Double::isFinite)
    }
    return ParkedChargeBreakdown(capped, complete, duration, outside,
        invalid || missingLocal || combined.size > MAX_PARKED_CHARGE_SESSIONS,
        nonChargingDelta, nonChargingRate)
}

private fun hasQualifiedSocBoundaries(row: ParkedChargeSession): Boolean {
    val end = row.end ?: return false
    if (row.startBatteryLevel == null || row.endBatteryLevel == null) return false
    val first = row.startSocObservedAt ?: return false
    val last = row.endSocObservedAt ?: return false
    if (!last.isAfter(first)) return false
    fun near(boundary: Instant, observed: Instant) =
        !observed.isBefore(boundary.minusSeconds(300)) && !observed.isAfter(boundary.plusSeconds(300))
    return near(row.start, first) && near(end, last)
}

private fun unionDuration(intervals: List<Pair<Instant, Instant>>): Duration {
    var total = Duration.ZERO
    var active: Pair<Instant, Instant>? = null
    for ((start, end) in intervals.sortedBy { it.first }) {
        val previous = active
        if (previous == null) {
            active = start to end
        } else if (!start.isAfter(previous.second)) {
            active = previous.first to maxOf(previous.second, end)
        } else {
            total = total.plus(Duration.between(previous.first, previous.second))
            active = start to end
        }
    }
    active?.let { total = total.plus(Duration.between(it.first, it.second)) }
    return total
}

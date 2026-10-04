package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveDetail
import java.time.Instant
import java.time.OffsetDateTime

internal data class DriveBatteryEndpoints(val start: Int?, val end: Int?)

/** Select dated boundary records, retaining an unknown SOC at either boundary. */
internal fun observedDriveBatteryEndpoints(detail: DriveDetail): DriveBatteryEndpoints {
    val fallback = DriveBatteryEndpoints(validSoc(detail.startBatteryLevel), validSoc(detail.endBatteryLevel))
    val startBound = sampleInstant(detail.startDate)
    val endBound = sampleInstant(detail.endDate)
    if ((detail.startDate != null && startBound == null) ||
        (detail.endDate != null && endBound == null) ||
        (startBound != null && endBound != null && endBound < startBound)) return fallback

    var first: BatteryBoundary? = null
    var last: BatteryBoundary? = null
    for (point in detail.positions.orEmpty()) {
        val at = sampleInstant(point.date) ?: continue
        if ((startBound != null && at < startBound) || (endBound != null && at > endBound)) continue
        val level = validSoc(point.batteryLevel)
        if (first == null) {
            first = BatteryBoundary(at, level)
            last = BatteryBoundary(at, level)
            continue
        }
        when {
            at < first.at -> first.reset(at, level)
            at == first.at -> first.observe(level)
        }
        val latest = checkNotNull(last)
        when {
            at > latest.at -> latest.reset(at, level)
            at == latest.at -> latest.observe(level)
        }
    }
    val earliest = first
    val latest = last
    return DriveBatteryEndpoints(
        start = earliest?.level ?: fallback.start,
        // A duplicate or lone timestamp cannot establish both endpoints.
        end = if (earliest != null && latest != null && earliest.at != latest.at) {
            latest.level ?: fallback.end
        } else fallback.end
    )
}

private fun validSoc(value: Int?): Int? = value?.takeIf { it in 0..100 }

private fun sampleInstant(value: String?): Instant? = value?.let {
    runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
}

private class BatteryBoundary(var at: Instant, var level: Int?) {
    private var conflicted = false

    fun reset(at: Instant, level: Int?) {
        this.at = at
        this.level = level
        conflicted = false
    }

    fun observe(value: Int?) {
        // Absence does not contradict a real observation at the same instant.
        if (value == null || conflicted) return
        if (level == null) level = value
        else if (level != value) {
            level = null
            conflicted = true
        }
    }
}

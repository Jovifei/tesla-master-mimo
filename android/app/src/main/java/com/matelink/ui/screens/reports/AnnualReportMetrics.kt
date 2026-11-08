package com.matelink.ui.screens.reports

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

private data class ChargeBoundary(
    val start: Instant,
    val end: Instant?,
    val startSoc: Int?,
    val endSoc: Int?
)

private data class DriveWindow(val start: Instant, val end: Instant)

/** A wall time without an offset cannot prove when a historical event occurred. */
private fun observedInstant(value: String?): Instant? =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

fun annualStandbyKwh(
    year: Int,
    charges: List<ChargeData>,
    drives: List<DriveData>,
    batteryCapacityKwh: Double? = null,
    zone: ZoneId = ZoneId.systemDefault()
): Double? {
    val usableCapacity = batteryCapacityKwh?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    // If any charge cannot be placed on the timeline, adjacency cannot be proven.
    val sortedCharges = charges.map { charge ->
        ChargeBoundary(
            start = observedInstant(charge.startDate) ?: return null,
            end = observedInstant(charge.endDate),
            startSoc = charge.startBatteryLevel,
            endSoc = charge.endBatteryLevel
        )
    }.sortedWith(compareBy(ChargeBoundary::start, { it.end }))
    if (sortedCharges.size < 2) return null
    // An unbounded drive cannot prove that the parked interval was drive-free.
    val driveWindows = drives.map { drive ->
        val start = observedInstant(drive.startDate) ?: return null
        val end = observedInstant(drive.endDate) ?: return null
        if (!end.isAfter(start)) return null
        DriveWindow(start, end)
    }
    var total = 0.0
    var count = 0
    sortedCharges.zipWithNext().forEach { (previous, next) ->
        val previousEnd = previous.end ?: return@forEach
        if (!previousEnd.isAfter(previous.start) || !next.start.isAfter(previousEnd)) return@forEach
        if (next.start.atZone(zone).year != year) return@forEach
        val previousLevel = previous.endSoc?.takeIf { it in 0..100 } ?: return@forEach
        val nextLevel = next.startSoc?.takeIf { it in 0..100 } ?: return@forEach
        val drop = previousLevel - nextLevel
        if (drop <= 0) return@forEach
        if (driveWindows.any { it.start.isBefore(next.start) && previousEnd.isBefore(it.end) })
            return@forEach
        total += drop / 100.0 * usableCapacity
        count++
    }
    return total.takeIf { count > 0 && it.isFinite() }
}

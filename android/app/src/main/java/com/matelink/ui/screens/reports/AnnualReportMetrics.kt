package com.matelink.ui.screens.reports

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import java.time.OffsetDateTime

fun annualEffectiveCost(
    carId: Int,
    year: Int,
    charges: List<ChargeData>,
    manualTotals: Map<String, Double>
): Double? {
    val values = charges.filter { it.startDate?.startsWith("$year-") == true }
        .map { charge -> manualTotals["$carId:${charge.chargeId}"] ?: charge.cost }
        .filterNotNull()
        .filter { it.isFinite() && it >= 0.0 }
    return values.takeIf { it.isNotEmpty() }?.sum()
}

fun annualStandbyKwh(
    year: Int,
    charges: List<ChargeData>,
    drives: List<DriveData>,
    batteryCapacityKwh: Double = 75.0
): Double? {
    val sortedCharges = charges.filter { it.startDate != null && it.endDate != null }
        .sortedBy { it.startDate }
    if (sortedCharges.size < 2) return null
    val sortedDrives = drives.filter { it.startDate != null && it.endDate != null }
    var total = 0.0
    var count = 0
    sortedCharges.zipWithNext().forEach { (previous, next) ->
        val previousEnd = previous.endDate ?: return@forEach
        val nextStart = next.startDate ?: return@forEach
        val previousLevel = previous.endBatteryLevel
        val nextLevel = next.startBatteryLevel
        if (!nextStart.startsWith("$year-") || previousLevel == null || nextLevel == null) return@forEach
        val drop = previousLevel - nextLevel
        if (drop <= 0) return@forEach
        val previousTime = runCatching { OffsetDateTime.parse(previousEnd) }.getOrNull() ?: return@forEach
        val nextTime = runCatching { OffsetDateTime.parse(nextStart) }.getOrNull() ?: return@forEach
        if (sortedDrives.any { drive ->
                val driveStart = runCatching { OffsetDateTime.parse(drive.startDate!!) }.getOrNull()
                val driveEnd = runCatching { OffsetDateTime.parse(drive.endDate!!) }.getOrNull()
                driveStart != null && driveEnd != null && driveStart.isBefore(nextTime) && previousTime.isBefore(driveEnd)
            }) return@forEach
        total += drop / 100.0 * batteryCapacityKwh
        count++
    }
    return total.takeIf { count > 0 }
}

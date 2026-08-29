package com.matelink.ui.screens.reports

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.StandbyWindowData
import com.matelink.domain.analytics.chargeTotalOverrideKey
import com.matelink.domain.analytics.resolveChargeCostFromTotal
import java.time.OffsetDateTime

fun annualEffectiveCost(
    carId: Int,
    year: Int,
    charges: List<ChargeData>,
    manualTotals: Map<String, Double>,
    freeSupercharging: Boolean = false,
    dcChargeIds: Set<Int> = emptySet()
): Double? {
    val values = charges
        .filter { belongsToYear(it.startDate, year) }
        .mapNotNull { charge ->
            resolveChargeCostFromTotal(
                manualTotalAmount = manualTotals[
                    chargeTotalOverrideKey(carId, charge.chargeId)
                ],
                freeSupercharging = freeSupercharging,
                isDcCharge = charge.chargeId in dcChargeIds,
                teslaMateCost = charge.cost,
                energyKwh = charge.chargeEnergyAdded
            ).cost
        }
    return values.takeIf(List<Double>::isNotEmpty)?.sum()
}

fun annualStandbyKwh(
    year: Int,
    windows: List<StandbyWindowData>
): Double? {
    val values = windows
        .filter { belongsToYear(it.startDate, year) }
        .mapNotNull { window ->
            window.energyKwh?.takeIf {
                window.coverageRatio.isFinite() &&
                    window.coverageRatio >= 0.8 &&
                    it.isFinite() &&
                    it >= 0.0
            }
        }
    return values.takeIf(List<Double>::isNotEmpty)?.sum()
}

/**
 * Legacy fallback retained for callers that only have charge and drive history.
 * A capacity observation is mandatory because SOC delta alone is not energy.
 */
fun annualStandbyKwh(
    year: Int,
    charges: List<ChargeData>,
    drives: List<DriveData>,
    batteryCapacityKwh: Double? = null
): Double? {
    val usableCapacity = batteryCapacityKwh
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: return null
    val sortedCharges = charges
        .filter { it.startDate != null && it.endDate != null }
        .sortedBy { it.startDate }
    if (sortedCharges.size < 2) return null
    val sortedDrives = drives.filter {
        it.startDate != null && it.endDate != null
    }
    var total = 0.0
    var count = 0
    sortedCharges.zipWithNext().forEach { (previous, next) ->
        val previousEnd = previous.endDate ?: return@forEach
        val nextStart = next.startDate ?: return@forEach
        val previousLevel = previous.endBatteryLevel
        val nextLevel = next.startBatteryLevel
        if (!belongsToYear(nextStart, year) ||
            previousLevel == null ||
            nextLevel == null
        ) {
            return@forEach
        }
        val drop = previousLevel - nextLevel
        if (drop <= 0) return@forEach
        val previousTime = runCatching {
            OffsetDateTime.parse(previousEnd)
        }.getOrNull() ?: return@forEach
        val nextTime = runCatching {
            OffsetDateTime.parse(nextStart)
        }.getOrNull() ?: return@forEach
        if (sortedDrives.any { drive ->
                val driveStart = runCatching {
                    OffsetDateTime.parse(drive.startDate!!)
                }.getOrNull()
                val driveEnd = runCatching {
                    OffsetDateTime.parse(drive.endDate!!)
                }.getOrNull()
                driveStart != null &&
                    driveEnd != null &&
                    driveStart.isBefore(nextTime) &&
                    previousTime.isBefore(driveEnd)
            }
        ) {
            return@forEach
        }
        total += drop / 100.0 * usableCapacity
        count++
    }
    return total.takeIf { count > 0 }
}

private fun belongsToYear(value: String?, year: Int): Boolean {
    if (value.isNullOrBlank()) return false
    return runCatching {
        OffsetDateTime.parse(value).year == year
    }.getOrElse {
        value.startsWith("$year-")
    }
}

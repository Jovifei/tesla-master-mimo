package com.matelink.domain.analytics

import com.matelink.data.api.models.DriveData

/**
 * Evidence counts for the mileage page. A drive record may exist while one or
 * more of its measured fields are absent; those fields must not be rendered as
 * observed zeroes.
 */
data class MileageEvidence(
    val recordCount: Int,
    val distanceSampleCount: Int,
    val energySampleCount: Int,
    val batterySampleCount: Int
) {
    val hasDistance: Boolean get() = distanceSampleCount > 0
    val hasEnergy: Boolean get() = energySampleCount > 0
    val hasBatteryUsage: Boolean get() = batterySampleCount > 0
}

fun buildMileageEvidence(drives: Iterable<DriveData>): MileageEvidence {
    val records = drives.toList()
    return MileageEvidence(
        recordCount = records.size,
        distanceSampleCount = records.count { it.observedDistanceKm() != null },
        energySampleCount = records.count { it.observedEnergyKwh() != null },
        batterySampleCount = records.count { it.observedBatteryUsagePercent() != null }
    )
}

fun DriveData.observedDistanceKm(): Double? =
    distance?.takeIf { it.isFinite() && it > 0.0 }

fun DriveData.observedEnergyKwh(): Double? =
    energyConsumedNet?.takeIf { it.isFinite() && it >= 0.0 }

fun DriveData.observedBatteryUsagePercent(): Double? {
    val start = startBatteryLevel ?: return null
    val end = endBatteryLevel ?: return null
    return (start - end).toDouble().takeIf { it.isFinite() && it >= 0.0 }
}

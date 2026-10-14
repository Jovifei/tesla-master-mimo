package com.matelink.domain.analytics

import com.matelink.data.api.models.DriveData
import com.matelink.domain.history.isAnalysisEligible

/**
 * Evidence counts for the mileage page. A drive record may exist while one or
 * more qualified fields are absent. Estimates stay labeled as estimates;
 * missing fields must not be rendered as known zeroes.
 */
data class MileageEvidence(
    val recordCount: Int,
    val distanceSampleCount: Int,
    val energySampleCount: Int,
    val batterySampleCount: Int,
    val estimatedEnergySampleCount: Int = 0,
    val efficiencyWhKm: Double? = null,
    val efficiencySampleCount: Int = 0
) {
    val hasDistance: Boolean get() = distanceSampleCount > 0
    val hasEnergy: Boolean get() = energySampleCount > 0
    val hasBatteryUsage: Boolean get() = batterySampleCount > 0
}

fun buildMileageEvidence(drives: Iterable<DriveData>): MileageEvidence {
    val records = drives.toList()
    val efficiency = calculateWeightedEfficiency(records.map {
        EfficiencySample(it.observedDistanceKm(), it.observedEnergyKwh())
    })
    return MileageEvidence(
        recordCount = records.size,
        distanceSampleCount = records.count { it.observedDistanceKm() != null },
        energySampleCount = records.count { it.observedEnergyKwh() != null },
        batterySampleCount = records.count { it.observedBatteryUsagePercent() != null },
        estimatedEnergySampleCount = records.count {
            it.observedEnergyKwh() != null && it.energyContract?.netEnergy?.quality == "estimated"
        },
        efficiencyWhKm = efficiency.efficiencyWhKm,
        efficiencySampleCount = efficiency.sampleCount
    )
}

fun DriveData.observedDistanceKm(): Double? =
    distanceKm?.takeIf { isAnalysisEligible(qualityState, qualityReason) && it >= 0.0 }

fun DriveData.observedEnergyKwh(): Double? =
    netEnergyKwh?.takeIf { isAnalysisEligible(qualityState, qualityReason) && it.isFinite() }

fun DriveData.observedBatteryUsagePercent(): Double? {
    if (!isAnalysisEligible(qualityState, qualityReason)) return null
    val start = startBatteryLevel?.takeIf { it in 0..100 } ?: return null
    val end = endBatteryLevel?.takeIf { it in 0..100 } ?: return null
    return (start - end).toDouble().takeIf { it.isFinite() && it >= 0.0 }
}

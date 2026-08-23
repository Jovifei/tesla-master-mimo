package com.matelink.domain.analytics

/** A drive sample used for distance-weighted efficiency aggregation. */
data class EfficiencySample(
    val distanceKm: Double?,
    val energyKwh: Double?
)

data class WeightedEfficiencyResult(
    val efficiencyWhKm: Double?,
    val validDistanceKm: Double,
    val validEnergyKwh: Double,
    val sampleCount: Int,
    val coveragePercent: Double
)

/**
 * Calculates total energy divided by total distance.
 * Invalid, non-positive and sub-kilometre samples are excluded explicitly.
 */
fun calculateWeightedEfficiency(
    samples: List<EfficiencySample>,
    minimumDistanceKm: Double = 1.0
): WeightedEfficiencyResult {
    val valid = samples.filter { sample ->
        val distance = sample.distanceKm
        val energy = sample.energyKwh
        distance != null && energy != null &&
            distance.isFinite() && energy.isFinite() &&
            distance >= minimumDistanceKm && energy > 0.0
    }
    val distance = valid.sumOf { it.distanceKm!! }
    val energy = valid.sumOf { it.energyKwh!! }

    return WeightedEfficiencyResult(
        efficiencyWhKm = (energy.takeIf { it > 0.0 }?.let { it * 1000.0 / distance })
            ?.takeIf { it.isFinite() && distance > 0.0 },
        validDistanceKm = distance,
        validEnergyKwh = energy,
        sampleCount = valid.size,
        coveragePercent = if (samples.isEmpty()) 0.0 else valid.size * 100.0 / samples.size
    )
}

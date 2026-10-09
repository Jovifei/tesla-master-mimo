package com.matelink.domain.analytics

/** A drive sample used for distance-weighted net efficiency aggregation. */
data class EfficiencySample(val distanceKm: Double?, val energyKwh: Double?)

data class WeightedEfficiencyResult(
    val efficiencyWhKm: Double?,
    val validDistanceKm: Double,
    val validEnergyKwh: Double,
    val sampleCount: Int,
    /** Record coverage, not distance or time coverage. */
    val coveragePercent: Double,
    val distanceCoveragePercent: Double? = null,
    val qualityReason: String? = null
)

/** Both sums use exactly the same qualified subset. No mean of per-drive ratios. */
fun calculateWeightedEfficiency(
    samples: List<EfficiencySample>,
    minimumDistanceKm: Double = 0.0
): WeightedEfficiencyResult {
    require(minimumDistanceKm.isFinite() && minimumDistanceKm >= 0.0)
    val valid = samples.filter { sample ->
        val distance = sample.distanceKm
        val energy = sample.energyKwh
        distance != null && distance.isFinite() && distance > 0.0 && distance >= minimumDistanceKm &&
            energy != null && energy.isFinite()
    }
    val distance = valid.sumOf { it.distanceKm!! }
    val energy = valid.sumOf { it.energyKwh!! }
    val allKnownDistance = samples.mapNotNull { it.distanceKm?.takeIf { d -> d.isFinite() && d > 0.0 } }.sum()
    val finiteSums = distance.isFinite() && energy.isFinite()
    return WeightedEfficiencyResult(
        efficiencyWhKm = if (valid.isNotEmpty() && finiteSums) (energy / distance * 1000.0).takeIf(Double::isFinite) else null,
        validDistanceKm = distance,
        validEnergyKwh = energy,
        sampleCount = valid.size,
        coveragePercent = if (samples.isEmpty()) 0.0 else valid.size * 100.0 / samples.size,
        distanceCoveragePercent = if (allKnownDistance.isFinite() && allKnownDistance > 0.0 && distance.isFinite()) distance / allKnownDistance * 100.0 else null,
        qualityReason = when {
            !finiteSums -> "non_finite_aggregate"
            valid.isEmpty() -> "no_qualified_energy_distance_pair"
            valid.size != samples.size -> "partial_record_coverage"
            else -> null
        }
    )
}

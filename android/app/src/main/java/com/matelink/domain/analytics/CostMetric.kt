package com.matelink.domain.analytics

/**
 * Keeps SQL's COALESCE(SUM(...), 0) distinct from an observed zero-cost record.
 * A zero amount is available only when at least one source record has a cost.
 */
fun observedAggregateCostOrNull(totalCost: Double, pricedRecordCount: Int): Double? =
    totalCost.takeIf {
        pricedRecordCount > 0 && it.isFinite() && it >= 0.0
    }

fun observedCostSumOrNull(costs: Iterable<Double?>): Double? {
    val validCosts = costs.mapNotNull { it?.takeIf { value -> value.isFinite() && value >= 0.0 } }
    return observedAggregateCostOrNull(validCosts.sum(), validCosts.size)
}

/**
 * Derives a cost-per-distance value without turning an observed free charge
 * into an unavailable value or a missing cost into a fabricated zero.
 */
fun observedCostPerDistanceOrNull(totalCost: Double?, distanceKm: Double?): Double? {
    val cost = totalCost?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    val distance = distanceKm?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    return (cost / distance * 100.0).takeIf { it.isFinite() && it >= 0.0 }
}

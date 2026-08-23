package com.matelink.domain.analytics

/**
 * Difference between the rated-range drop and odometer distance for one drive.
 * This is a deviation, not a forecast-accuracy score.
 */
fun ratedRangeDeviationPercent(
    ratedRangeDropKm: Double?,
    actualDistanceKm: Double?
): Double? {
    val rated = ratedRangeDropKm?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val actual = actualDistanceKm?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    return (kotlin.math.abs(rated - actual) / rated * 100.0)
        .takeIf { it.isFinite() && it >= 0.0 }
}

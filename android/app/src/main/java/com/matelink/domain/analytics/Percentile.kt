package com.matelink.domain.analytics

import kotlin.math.roundToInt

data class PercentilePosition(
    val percentile: Int,
    val sampleCount: Int,
    val value: Double,
    val min: Double,
    val median: Double,
    val max: Double
)

fun percentilePosition(samples: List<Double>, value: Double): PercentilePosition? {
    val sorted = samples.filter { it.isFinite() }.sorted()
    if (sorted.isEmpty() || !value.isFinite()) return null
    val rank = sorted.count { it <= value }
    val percentile = ((rank - 1).coerceAtLeast(0).toDouble() / (sorted.size - 1).coerceAtLeast(1) * 100.0)
        .roundToInt()
        .coerceIn(0, 100)
    return PercentilePosition(
        percentile = percentile,
        sampleCount = sorted.size,
        value = value,
        min = sorted.first(),
        median = sorted[sorted.lastIndex / 2],
        max = sorted.last()
    )
}

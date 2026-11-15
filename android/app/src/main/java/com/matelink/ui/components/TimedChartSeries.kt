package com.matelink.ui.components

import kotlin.math.abs
import kotlin.math.roundToLong

data class TimedChartSample(val timestampMs: Long?, val value: Double?, val sourceIndex: Int)

/** Values, source indices and elapsed-time coordinates always describe the same observations. */
data class TimedChartSeries(
    val values: List<Float>,
    val timestampsMs: List<Long>,
    val xFractions: List<Float>,
    val sourceIndices: List<Int>,
    /** Input indices before which a line must be broken. Missing values also break lines. */
    val gaps: Set<Int>,
    val domainStartMs: Long?,
    val domainEndMs: Long?
) {
    val validCount: Int get() = values.count(Float::isFinite)
    fun nearestTimestampAtFraction(fraction: Float): Long? = values.indices
        .filter { values[it].isFinite() }
        .minByOrNull { abs(xFractions[it] - fraction.coerceIn(0f, 1f)) }
        ?.let(timestampsMs::get)

    fun axisTimestampAtFraction(fraction: Float): Long? {
        val start = domainStartMs ?: return null
        val end = domainEndMs ?: return null
        return start + ((end.toDouble() - start) * fraction.coerceIn(0f, 1f)).roundToLong()
    }
}

/** Same-time conflicting values become a gap; unknown values never become zero. */
fun projectTimedChartSeries(
    samples: List<TimedChartSample>,
    windowStartMs: Long? = null,
    windowEndMs: Long? = null,
    maxGapMs: Long = 60_000L
): TimedChartSeries {
    require(maxGapMs > 0)
    val timed = samples.filter { sample -> sample.timestampMs?.let {
        (windowStartMs == null || it >= windowStartMs) && (windowEndMs == null || it <= windowEndMs)
    } == true }.groupBy { it.timestampMs!! }.toSortedMap()
    val start = windowStartMs ?: timed.keys.firstOrNull()
    val end = windowEndMs ?: timed.keys.lastOrNull()
    if (start == null || end == null || end < start) return TimedChartSeries(
        emptyList(), emptyList(), emptyList(), emptyList(), emptySet(), start, end)
    val values = mutableListOf<Float>()
    val times = mutableListOf<Long>()
    val sources = mutableListOf<Int>()
    val gaps = mutableSetOf<Int>()
    timed.forEach { (at, sameTime) ->
        val finite = sameTime.mapNotNull { it.value?.takeIf { value -> value.isFinite() && value.toFloat().isFinite() } }.distinct()
        values += finite.singleOrNull()?.toFloat() ?: Float.NaN
        if (times.lastOrNull()?.let { at.toDouble() - it > maxGapMs } == true) gaps += times.size
        times += at
        sources += sameTime.firstOrNull { it.value == finite.singleOrNull() }?.sourceIndex ?: sameTime.first().sourceIndex
    }
    val untimedIndices = samples.filter { it.timestampMs == null }.map { it.sourceIndex }.sorted()
    sources.zipWithNext().forEachIndexed { index, (previous, next) ->
        val low = minOf(previous, next)
        val high = maxOf(previous, next)
        if (high > low) {
            val insertion = untimedIndices.binarySearch(low + 1).let { if (it >= 0) it else -it - 1 }
            if (untimedIndices.getOrNull(insertion)?.let { it < high } == true) gaps += index + 1
        }
    }
    val duration = end.toDouble() - start
    return TimedChartSeries(values, times,
        times.map { if (duration > 0) ((it.toDouble() - start) / duration).toFloat().coerceIn(0f, 1f) else 0f },
        sources, gaps, start, end)
}

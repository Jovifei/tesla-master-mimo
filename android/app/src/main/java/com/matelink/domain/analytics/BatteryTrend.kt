package com.matelink.domain.analytics

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A battery trend sample derived from a historical drive snapshot.
 *
 * The rated range is normalized to a 100% state of charge before it is used
 * in the trend. This keeps a 72% sample comparable with a 94% sample while
 * still requiring the source values to be present and physically plausible.
 */
data class BatteryTrendSample(
    val date: LocalDate,
    val socPercent: Double?,
    val ratedRangeKm: Double?,
    val temperatureC: Double?
)

data class BatteryTrendPoint(
    val date: LocalDate,
    val normalizedRangeKm: Double
)

enum class BatteryTrendSource {
    TREND_ESTIMATE,
    TREND_ONLY,
    UNAVAILABLE
}

data class BatteryTrendEstimate(
    val source: BatteryTrendSource,
    val normalizedCurrentRangeKm: Double?,
    val baselineRangeKm: Double?,
    val degradationPercent: Double?,
    val sampleCount: Int,
    val coverageDays: Long,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val confidencePercent: Int,
    val points: List<BatteryTrendPoint> = emptyList()
)

private data class ValidBatteryTrendSample(
    val date: LocalDate,
    val normalizedRangeKm: Double
)

/**
 * Estimates battery range degradation only from repeated, temperature-
 * constrained rated-range observations.
 *
 * This is intentionally a trend estimate, not a replacement for an observed
 * battery capacity value. A numeric degradation percentage is withheld until
 * an early baseline and a separate recent window both exist.
 */
fun estimateBatteryTrend(
    samples: Iterable<BatteryTrendSample>,
    minimumSamples: Int = MINIMUM_SAMPLES,
    minimumCoverageDays: Long = MINIMUM_COVERAGE_DAYS
): BatteryTrendEstimate {
    val valid = samples
        .mapNotNull { sample ->
            val soc = sample.socPercent?.takeIf { it.isFinite() && it in MIN_SOC..MAX_SOC }
            val range = sample.ratedRangeKm?.takeIf { it.isFinite() && it > 0.0 }
            val temperature = sample.temperatureC?.takeIf {
                it.isFinite() && it in MIN_TEMPERATURE_C..MAX_TEMPERATURE_C
            }
            if (soc == null || range == null || temperature == null) {
                null
            } else {
                ValidBatteryTrendSample(
                    date = sample.date,
                    normalizedRangeKm = range / (soc / 100.0)
                )
            }
        }
        .filter { it.normalizedRangeKm.isFinite() && it.normalizedRangeKm > 0.0 }
        .sortedBy { it.date }

    val firstDate = valid.firstOrNull()?.date
    val lastDate = valid.lastOrNull()?.date
    val coverageDays = if (firstDate != null && lastDate != null) {
        ChronoUnit.DAYS.between(firstDate, lastDate)
    } else {
        0L
    }
    val points = valid
        .groupBy { it.date }
        .mapNotNull { (date, sameDate) ->
            median(sameDate.map { it.normalizedRangeKm })?.let { normalizedRange ->
                BatteryTrendPoint(date = date, normalizedRangeKm = normalizedRange)
            }
        }
        .sortedBy { it.date }

    if (valid.size < minimumSamples || coverageDays < minimumCoverageDays) {
        return BatteryTrendEstimate(
            source = BatteryTrendSource.UNAVAILABLE,
            normalizedCurrentRangeKm = null,
            baselineRangeKm = null,
            degradationPercent = null,
            sampleCount = valid.size,
            coverageDays = coverageDays,
            firstDate = firstDate,
            lastDate = lastDate,
            confidencePercent = 0,
            points = points
        )
    }

    val recentStart = lastDate!!.minusDays(RECENT_WINDOW_DAYS)
    val recent = valid.filter { !it.date.isBefore(recentStart) }
    val currentRange = median(recent.map { it.normalizedRangeKm })

    // The baseline must precede the recent window. Without that separation,
    // comparing two medians would give a false impression of degradation.
    val baselineEnd = firstDate!!.plusDays(BASELINE_WINDOW_DAYS)
    val baseline = valid.filter { !it.date.isAfter(baselineEnd) && it.date.isBefore(recentStart) }
    val baselineRange = median(baseline.map { it.normalizedRangeKm })

    val source = if (baselineRange != null && currentRange != null) {
        BatteryTrendSource.TREND_ESTIMATE
    } else {
        BatteryTrendSource.TREND_ONLY
    }
    val degradation = if (source == BatteryTrendSource.TREND_ESTIMATE) {
        val baselineValue = baselineRange?.takeIf { it > 0.0 }
        val currentValue = currentRange?.takeIf { it.isFinite() && it > 0.0 }
        if (baselineValue != null && currentValue != null) {
            ((baselineValue - currentValue) / baselineValue * 100.0).takeIf { it.isFinite() }
        } else null
    } else null

    return BatteryTrendEstimate(
        source = source,
        normalizedCurrentRangeKm = currentRange,
        baselineRangeKm = baselineRange,
        degradationPercent = degradation,
        sampleCount = valid.size,
        coverageDays = coverageDays,
        firstDate = firstDate,
        lastDate = lastDate,
        confidencePercent = confidence(valid.size, coverageDays, baselineRange != null),
        points = points
    )
}

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}

private fun confidence(sampleCount: Int, coverageDays: Long, hasBaseline: Boolean): Int {
    val sampleScore = (sampleCount * 2).coerceAtMost(24)
    val coverageScore = (coverageDays / 15L).toInt().coerceAtMost(24)
    val baselineScore = if (hasBaseline) 18 else 0
    return (34 + sampleScore + coverageScore + baselineScore).coerceIn(0, 95)
}

private const val MINIMUM_SAMPLES = 10
private const val MINIMUM_COVERAGE_DAYS = 30L
private const val RECENT_WINDOW_DAYS = 30L
private const val BASELINE_WINDOW_DAYS = 30L
private const val MIN_SOC = 70.0
private const val MAX_SOC = 100.0
private const val MIN_TEMPERATURE_C = 15.0
private const val MAX_TEMPERATURE_C = 30.0

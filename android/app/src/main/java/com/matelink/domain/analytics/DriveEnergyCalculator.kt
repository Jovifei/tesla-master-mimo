package com.matelink.domain.analytics

import com.matelink.util.parseIsoInstant
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** Signed power at the source's declared measurement point, in kW. */
data class DrivePowerSample(val timestamp: String?, val powerKw: Double?)

data class DriveEnergyResult(
    /** Integral over covered intervals only; not necessarily the energy of the whole drive. */
    val energyKwh: Double?,
    val coverageSeconds: Long,
    val coverageSecondsExact: Double = coverageSeconds.toDouble(),
    val windowSeconds: Double? = null,
    val complete: Boolean = false,
    val qualityReason: String = "missing_window"
)

/**
 * Trapezoidal integral over sorted, non-overlapping observations. A gap larger
 * than 30 seconds is a missing interval, never a fictitious 30-second sample.
 * Null/conflicting observations are barriers, not points to interpolate across.
 * Explicit window boundaries are required to call the integral complete.
 */
object DriveEnergyCalculator {
    private const val MAX_INTERVAL_SECONDS = 30.0

    fun calculate(
        samples: List<DrivePowerSample>,
        startDate: String? = null,
        endDate: String? = null
    ): DriveEnergyResult {
        val start = startDate?.let(::parseIsoInstant)
        val end = endDate?.let(::parseIsoInstant)
        val requestedWindow = startDate != null || endDate != null
        if (requestedWindow && (start == null || end == null || !end.isAfter(start))) {
            return DriveEnergyResult(null, 0, qualityReason = "invalid_window")
        }
        val windowSeconds = if (start != null && end != null) secondsBetween(start, end) else null
        var invalidTimestamp = false
        val groups = samples.mapNotNull { sample ->
            val time = sample.timestamp?.let(::parseIsoInstant)
            if (time == null) {
                invalidTimestamp = true
                null
            } else time to sample.powerKw?.takeIf(Double::isFinite)
        }.groupBy({ it.first }, { it.second })
        val points = groups.toSortedMap().map { (time, values) ->
            // Identical replays are one point; conflicting values are unknown.
            time to values.firstOrNull()?.takeIf { first -> values.all { it == first } }
        }
        var energy = 0.0
        var covered = 0.0
        var arithmeticFailure = false
        points.zipWithNext().forEach { (left, right) ->
            val p0 = left.second ?: return@forEach
            val p1 = right.second ?: return@forEach
            val interval = secondsBetween(left.first, right.first)
            if (!interval.isFinite() || interval <= 0.0 || interval > MAX_INTERVAL_SECONDS) return@forEach
            val from = if (start != null && start.isAfter(left.first)) start else left.first
            val to = if (end != null && end.isBefore(right.first)) end else right.first
            if (!to.isAfter(from)) return@forEach
            val elapsed = secondsBetween(from, to)
            val a = secondsBetween(left.first, from) / interval
            val b = secondsBetween(left.first, to) / interval
            // The weighted form avoids overflow in p1-p0 and p0+p1.
            val atFrom = p0 * (1.0 - a) + p1 * a
            val atTo = p0 * (1.0 - b) + p1 * b
            val integral = (atFrom / 2.0 + atTo / 2.0) * (elapsed / 3600.0)
            if (!integral.isFinite() || !(energy + integral).isFinite()) {
                arithmeticFailure = true
                return@forEach
            }
            energy += integral
            covered += elapsed
        }
        val complete = windowSeconds != null && covered > 0.0 && !invalidTimestamp && !arithmeticFailure &&
            abs(covered - windowSeconds) <= 0.000001
        val reason = when {
            arithmeticFailure -> "non_finite_integral"
            invalidTimestamp -> "invalid_sample_timestamp"
            windowSeconds == null -> "missing_window"
            covered == 0.0 -> "no_covered_interval"
            !complete -> "incomplete_power_coverage"
            else -> "complete_power_window"
        }
        return DriveEnergyResult(
            energyKwh = energy.takeIf { covered > 0.0 && !arithmeticFailure },
            coverageSeconds = covered.toLong(),
            coverageSecondsExact = covered,
            windowSeconds = windowSeconds,
            complete = complete,
            qualityReason = reason
        )
    }

    private fun secondsBetween(start: Instant, end: Instant): Double {
        val duration = Duration.between(start, end)
        return duration.seconds.toDouble() + duration.nano.toDouble() / 1_000_000_000.0
    }
}

package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DrivePosition
import com.matelink.ui.components.TimedChartSample
import com.matelink.ui.components.TimedChartSeries
import com.matelink.ui.components.projectTimedChartSeries
import com.matelink.util.parseIsoInstant
import com.matelink.util.parseIsoDateTime
import com.matelink.util.formatTime
import java.time.Instant
import java.util.Locale

internal data class DriveCurveProjection(
    val speed: TimedChartSeries, val distance: TimedChartSeries, val power: TimedChartSeries,
    val battery: TimedChartSeries, val elevation: TimedChartSeries
) {
    val hasChartData get() = listOf(speed, distance, power, battery, elevation).any { it.validCount >= 2 }
}

/** All five consumers share the same bounded clock and retain each signal's missing samples. */
internal fun projectDriveCurves(detail: DriveDetail): DriveCurveProjection {
    val positions = detail.positions.orEmpty()
    val start = parseIsoInstant(detail.startDate)?.toEpochMilli()
    val end = parseIsoInstant(detail.endDate)?.toEpochMilli()
    fun series(value: (DrivePosition) -> Double?) = projectTimedChartSeries(positions.mapIndexed { index, point ->
        TimedChartSample(parseIsoInstant(point.date)?.toEpochMilli(), value(point), index)
    }, start, end)
    val speed = series { point -> point.speed?.takeIf { detail.hasKnownLengthUnit && it.isFinite() && it >= 0.0 } }
    return DriveCurveProjection(speed, integratedDistanceCurve(speed),
        series { it.power?.takeIf(Double::isFinite) },
        series { it.batteryLevel?.takeIf { soc -> soc in 0..100 }?.toDouble() },
        series { it.elevation?.toDouble() })
}

internal fun timedDriveCurveLabel(series: TimedChartSeries, fraction: Float, is24Hour: Boolean): String =
    com.matelink.ui.components.nearestChartIndex(series.xFractions, fraction)?.let(series.timestampsMs::get)?.let { at ->
        parseIsoDateTime(Instant.ofEpochMilli(at).toString())?.formatTime(Locale.getDefault(), is24Hour)
    }.orEmpty()

internal fun timedDriveAxisLabels(series: TimedChartSeries, is24Hour: Boolean): List<String> =
    listOf(0f, .25f, .5f, .75f, 1f).map { fraction ->
        series.axisTimestampAtFraction(fraction)?.let { at ->
            parseIsoDateTime(Instant.ofEpochMilli(at).toString())?.formatTime(Locale.getDefault(), is24Hour)
        }.orEmpty()
    }

/** Trapezoidal speed×elapsed time in the receipt's preferred distance unit. */
private fun integratedDistanceCurve(speed: TimedChartSeries): TimedChartSeries {
    var continuous = speed.values.firstOrNull()?.isFinite() == true
    var accumulated = 0.0
    val values = speed.values.indices.map { index ->
        if (index == 0) {
            if (continuous) 0f else Float.NaN
        } else {
            val previous = speed.values[index - 1]
            val current = speed.values[index]
            val elapsed = speed.timestampsMs[index].toDouble() - speed.timestampsMs[index - 1]
            continuous = continuous && previous.isFinite() && current.isFinite() && index !in speed.gaps && elapsed > 0
            if (!continuous) Float.NaN else {
                accumulated += (previous.toDouble() + current) / 2.0 * elapsed / 3_600_000.0
                accumulated.toFloat().takeIf(Float::isFinite) ?: Float.NaN.also { continuous = false }
            }
        }
    }
    return speed.copy(values = values)
}

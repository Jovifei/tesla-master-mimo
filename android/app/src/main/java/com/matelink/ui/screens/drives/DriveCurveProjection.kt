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
    return DriveCurveProjection(speed, integratedDistanceCurve(positions, speed, detail.lengthUnit),
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

private data class GpsFix(
    val at: Long,
    val latitude: Double,
    val longitude: Double,
    val sourceIndex: Int
)

/**
 * Observed GPS fixes give the distance shape even when the sampler gap exceeds
 * the chart's line-break threshold. A 60-second gap is a rendering concern, not
 * proof that the car stopped, so integrating only short intervals undercounted
 * real drives. Missing coordinates still fall back to speed×elapsed.
 */
private fun integratedDistanceCurve(
    positions: List<DrivePosition>,
    speed: TimedChartSeries,
    lengthUnit: String?
): TimedChartSeries {
    val fixes = positions.mapIndexedNotNull { index, position ->
        val at = parseIsoInstant(position.date)?.toEpochMilli() ?: return@mapIndexedNotNull null
        val latitude = position.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 }
            ?: return@mapIndexedNotNull null
        val longitude = position.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 }
            ?: return@mapIndexedNotNull null
        GpsFix(at, latitude, longitude, index)
    }.sortedBy { it.at }

    if (fixes.size >= 2 && fixes.zipWithNext().any { (a, b) -> haversineKilometers(a, b) > 0.0 }) {
        var accumulated = 0.0
        var previous: GpsFix? = null
        val samples = fixes.map { fix ->
            previous?.let { accumulated += haversineKilometers(it, fix) }
            previous = fix
            val value = if (lengthUnit?.trim()?.equals("mi", ignoreCase = true) == true) {
                accumulated / 1.609344
            } else {
                accumulated
            }
            TimedChartSample(fix.at, value, fix.sourceIndex)
        }
        return projectTimedChartSeries(
            samples,
            speed.domainStartMs,
            speed.domainEndMs,
            maxGapMs = Long.MAX_VALUE / 4
        )
    }

    // Trapezoidal speed×elapsed time in the receipt's preferred distance unit.
    var accumulated = 0.0
    val values = speed.values.indices.map { index ->
        if (index == 0) {
            if (speed.values.firstOrNull()?.isFinite() == true) 0f else Float.NaN
        } else {
            val previous = speed.values[index - 1]
            val current = speed.values[index]
            val elapsed = speed.timestampsMs[index].toDouble() - speed.timestampsMs[index - 1]
            if (previous.isFinite() && current.isFinite() && elapsed > 0) {
                accumulated += (previous.toDouble() + current) / 2.0 * elapsed / 3_600_000.0
            }
            if (current.isFinite()) {
                accumulated.toFloat().takeIf(Float::isFinite) ?: Float.NaN
            } else {
                Float.NaN
            }
        }
    }
    return speed.copy(values = values)
}

private fun haversineKilometers(a: GpsFix, b: GpsFix): Double {
    val earthRadiusKm = 6371.0088
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val deltaLat = lat2 - lat1
    val deltaLon = Math.toRadians(b.longitude - a.longitude)
    val h = kotlin.math.sin(deltaLat / 2).let { it * it } +
        kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
        kotlin.math.sin(deltaLon / 2).let { it * it }
    return 2 * earthRadiusKm * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
}

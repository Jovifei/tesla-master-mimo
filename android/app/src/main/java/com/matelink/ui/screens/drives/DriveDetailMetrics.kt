package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.EnergyMetric
import com.matelink.util.parseIsoInstant
import kotlin.math.roundToInt

// Source classification is separate from physical measurement point and quality.
enum class DriveDetailEnergySource { API, POWER_SAMPLES }

data class DriveDetailEnergyPresentation(
    val energyKwh: Double?,
    val efficiencyWhKm: Double?,
    val source: DriveDetailEnergySource?,
    val coverageSeconds: Long?,
    val coverageRatio: Double?,
    val evidence: EnergyMetric? = null
) {
    val isEstimated: Boolean get() = evidence?.isEstimated == true || source == DriveDetailEnergySource.POWER_SAMPLES
}

internal fun presentDriveDetailEnergy(
    energyKwh: Double?,
    efficiencyWhKm: Double?,
    energySource: String?,
    coverageSeconds: Long?,
    coverageRatio: Double?,
    evidence: EnergyMetric? = null
): DriveDetailEnergyPresentation {
    val source = when (energySource) {
        "api" -> DriveDetailEnergySource.API
        "power_samples" -> DriveDetailEnergySource.POWER_SAMPLES
        else -> null
    }
    val ratio = coverageRatio?.takeIf { it.isFinite() && it in 0.0..1.0 }
    val value = energyKwh?.takeIf(Double::isFinite)?.takeIf {
        source != DriveDetailEnergySource.POWER_SAMPLES || ratio?.let { it >= 1.0 - 1e-9 } == true
    }
    if (source == null) {
        return DriveDetailEnergyPresentation(null, null, null, coverageSeconds?.takeIf { it >= 0L }, null, evidence)
    }
    if (value == null) {
        return DriveDetailEnergyPresentation(null, null,
            if (source == DriveDetailEnergySource.POWER_SAMPLES) source else null,
            coverageSeconds?.takeIf { it >= 0L }, ratio, evidence)
    }
    return DriveDetailEnergyPresentation(value, efficiencyWhKm?.takeIf(Double::isFinite), source,
        coverageSeconds?.takeIf { it >= 0L }, ratio, evidence)
}

internal fun calculateDriveDetailStats(detail: DriveDetail, energy: DriveDetailEnergyPresentation): DriveDetailStats {
    val positions = detail.positions.orEmpty()
    val speeds = positions.mapNotNull { it.speed?.takeIf { value -> value.isFinite() && value >= 0.0 } }
    val powers = positions.mapNotNull { it.power?.takeIf(Double::isFinite) }
    val elevations = positions.mapNotNull { it.elevation }
    val (gain, loss) = elevationChange(elevations)
    fun batteryAt(boundary: String?): Int? {
        val at = boundary?.let(::parseIsoInstant) ?: return null
        val values = positions.filter { it.date?.let(::parseIsoInstant) == at }
            .map { it.batteryLevel?.takeIf { value -> value in 0..100 } }
        return values.firstOrNull()?.takeIf { value -> values.all { it == value } }
    }
    val startBattery = detail.startBatteryLevel?.takeIf { it in 0..100 } ?: batteryAt(detail.startDate)
    val endBattery = detail.endBatteryLevel?.takeIf { it in 0..100 } ?: batteryAt(detail.endDate)
    val distance = detail.distance?.takeIf { it.isFinite() && it >= 0.0 }
    val duration = detail.durationMin?.takeIf { it >= 0 }
    val start = detail.startDate?.let(::parseIsoInstant)
    val end = detail.endDate?.let(::parseIsoInstant)
    val seconds = if (start != null && end != null && end.isAfter(start)) {
        val d = java.time.Duration.between(start, end)
        d.seconds + d.nano / 1_000_000_000.0
    } else duration?.takeIf { it > 0 }?.times(60.0)
    return DriveDetailStats(
        speedMax = speeds.maxOrNull()?.roundToInt() ?: detail.speedMax?.takeIf { it >= 0 },
        speedAvg = detail.speedAvg?.takeIf { it.isFinite() && it >= 0.0 }
            ?: speeds.takeIf { it.isNotEmpty() }?.average()?.takeIf(Double::isFinite),
        speedMin = speeds.minOrNull()?.roundToInt(),
        powerMax = powers.maxOrNull()?.roundToInt() ?: detail.powerMax,
        powerMin = powers.minOrNull()?.roundToInt() ?: detail.powerMin,
        powerAvg = powers.takeIf { it.isNotEmpty() }?.average()?.takeIf(Double::isFinite),
        elevationMax = elevations.maxOrNull(), elevationMin = elevations.minOrNull(),
        elevationGain = gain, elevationLoss = loss,
        batteryStart = startBattery, batteryEnd = endBattery,
        batteryUsed = if (startBattery != null && endBattery != null && startBattery >= endBattery) startBattery - endBattery else null,
        energy = energy, distance = distance, durationMin = duration,
        avgSpeedFromDistance = if (distance != null && seconds != null && seconds > 0.0) (distance / seconds * 3600.0).takeIf(Double::isFinite) else null,
        outsideTempAvg = detail.outsideTempAvg?.takeIf(Double::isFinite),
        insideTempAvg = detail.insideTempAvg?.takeIf(Double::isFinite)
    )
}

private fun elevationChange(values: List<Int>): Pair<Int?, Int?> {
    if (values.size < 2) return null to null
    var gain = 0L
    var loss = 0L
    values.zipWithNext().forEach { (a, b) ->
        val d = b.toLong() - a.toLong()
        if (d > 0) gain += d else loss -= d
    }
    return gain.takeIf { it <= Int.MAX_VALUE }?.toInt() to loss.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

package com.matelink.ui.screens.drives

import com.matelink.R
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.EnergyMetric
import com.matelink.util.parseIsoInstant
import kotlin.math.roundToInt

// Source classification is separate from physical measurement point and quality.
enum class DriveDetailEnergySource { API, POWER_SAMPLES, ENERGY_REMAINING_DELTA, SOC_CAPACITY_ESTIMATE }

data class DriveDetailEnergyPresentation(
    val energyKwh: Double?,
    val efficiencyWhKm: Double?,
    val source: DriveDetailEnergySource?,
    val coverageSeconds: Long?,
    val coverageRatio: Double?,
    val evidence: EnergyMetric? = null
) {
    val isEstimated: Boolean get() = evidence?.isEstimated == true || source in setOf(
        DriveDetailEnergySource.POWER_SAMPLES, DriveDetailEnergySource.ENERGY_REMAINING_DELTA,
        DriveDetailEnergySource.SOC_CAPACITY_ESTIMATE)
}

internal fun presentDriveDetailEnergy(
    energyKwh: Double?,
    efficiencyWhKm: Double?,
    energySource: String?,
    coverageSeconds: Long?,
    coverageRatio: Double?,
    evidence: EnergyMetric? = null
): DriveDetailEnergyPresentation {
    val source = when (energySource?.lowercase()) {
        "api" -> DriveDetailEnergySource.API
        "power_samples" -> DriveDetailEnergySource.POWER_SAMPLES
        "energy_remaining_delta" -> DriveDetailEnergySource.ENERGY_REMAINING_DELTA
        "soc_capacity_estimate" -> DriveDetailEnergySource.SOC_CAPACITY_ESTIMATE
        else -> null
    }
    val ratio = coverageRatio?.takeIf { it.isFinite() && it in 0.0..1.0 }
    val value = energyKwh?.takeIf(Double::isFinite)?.takeIf {
        source != DriveDetailEnergySource.POWER_SAMPLES || ratio?.let { it >= 0.85 } == true
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

/**
 * How far from a drive boundary an observed SOC sample may sit and still describe that boundary.
 *
 * Mirrors the server's SOC boundary convention (`maxChargeSOCBoundaryDistance`) so a collector that
 * stamps its first/last sample slightly outside the recorded drive window still yields a real
 * measurement. The window is also capped at a tenth of the drive duration, because a sample taken
 * minutes into a short trip describes mid-drive SOC, not the boundary value. Beyond the window the
 * boundary is genuinely unobserved and stays null rather than becoming a made-up number.
 */
private val batteryBoundaryToleranceCap: java.time.Duration = java.time.Duration.ofMinutes(5)
private val batteryBoundaryToleranceFloor: java.time.Duration = java.time.Duration.ofSeconds(30)

private fun batteryBoundaryTolerance(detail: DriveDetail): java.time.Duration {
    val start = detail.startDate?.let(::parseIsoInstant)
    val end = detail.endDate?.let(::parseIsoInstant)
    val window = if (start != null && end != null && end.isAfter(start))
        java.time.Duration.between(start, end) else return batteryBoundaryToleranceFloor
    val fraction = window.dividedBy(10)
    return when {
        fraction < batteryBoundaryToleranceFloor -> batteryBoundaryToleranceFloor
        fraction > batteryBoundaryToleranceCap -> batteryBoundaryToleranceCap
        else -> fraction
    }
}

/** Arithmetic means describe the received samples, never an evenly sampled whole journey. */
internal fun driveDetailSpeedAverageLabel(detail: DriveDetail): Int {
    val reported = detail.speedAvg?.takeIf { detail.hasKnownLengthUnit && it.isFinite() && it >= 0.0 }
    val sampled = detail.hasKnownLengthUnit && detail.positions.orEmpty().any { it.speed?.let { value -> value.isFinite() && value >= 0.0 } == true }
    return if (reported == null && sampled) R.string.drive_detail_sample_mean else R.string.average
}

internal fun driveDetailPowerAverageLabel(detail: DriveDetail): Int =
    if (detail.positions.orEmpty().any { it.power?.isFinite() == true }) R.string.drive_detail_sample_mean else R.string.average

internal fun calculateDriveDetailStats(detail: DriveDetail, energy: DriveDetailEnergyPresentation): DriveDetailStats {
    val positions = detail.positions.orEmpty()
    val speeds = positions.mapNotNull { it.speed?.takeIf { value -> detail.hasKnownLengthUnit && value.isFinite() && value >= 0.0 } }
    val powers = positions.mapNotNull { it.power?.takeIf(Double::isFinite) }
    val elevations = positions.mapNotNull { it.elevation }
    val (gain, loss) = elevationChange(elevations)
    fun batteryAt(boundary: String?): Int? {
        val at = boundary?.let(::parseIsoInstant) ?: return null
        val samples = positions.mapNotNull { position ->
            val date = position.date?.let(::parseIsoInstant) ?: return@mapNotNull null
            val level = position.batteryLevel?.takeIf { value -> value in 0..100 } ?: return@mapNotNull null
            date to level
        }
        // Conflicting observations stamped exactly on the boundary stay unknown.
        val exact = samples.filter { it.first == at }.map { it.second }
        if (exact.isNotEmpty()) return exact.first().takeIf { value -> exact.all { it == value } }
        // The closest real sample inside the tolerance window describes the boundary without
        // inventing a value; a gap larger than that is genuinely unobserved.
        val tolerance = batteryBoundaryTolerance(detail)
        return samples
            .mapNotNull { (date, level) ->
                java.time.Duration.between(date, at).abs()
                    .takeIf { it <= tolerance }?.let { it to level }
            }
            .minByOrNull { it.first }
            ?.second
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
        speedMax = speeds.maxOrNull()?.roundToInt() ?: detail.speedMax?.takeIf { detail.hasKnownLengthUnit && it >= 0 },
        speedAvg = detail.speedAvg?.takeIf { detail.hasKnownLengthUnit && it.isFinite() && it >= 0.0 }
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

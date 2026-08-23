package com.matelink.domain.analytics

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class RecommendationDriveSample(
    val distanceKm: Double?,
    val energyKwh: Double?,
    val averageSpeedKmh: Double?,
    val outsideTemperatureC: Double?,
    val observedAt: String?
)

data class RecommendationChargeSample(
    val energyAddedKwh: Double?,
    val energyUsedKwh: Double?,
    val observedAt: String?
)

/** Converts stored summaries into the neutral evidence consumed by the rule engine. */
fun buildRecommendationEvidence(
    drives: List<RecommendationDriveSample>,
    charges: List<RecommendationChargeSample>
): RecommendationEvidence {
    val validDrives = drives.filter {
        it.distanceKm?.let { distance -> distance.isFinite() && distance >= 1.0 } == true &&
            it.energyKwh?.let { energy -> energy.isFinite() && energy > 0.0 } == true
    }
    // Keep speed and temperature comparisons orthogonal so one condition is not
    // misreported as the other condition's effect.
    val baseline = validDrives.filter {
        it.averageSpeedKmh.inRange(50.0..90.0) &&
            it.outsideTemperatureC?.let { temp -> temp in 10.0..25.0 } == true
    }
    val highSpeed = validDrives.filter {
        it.averageSpeedKmh.above(90.0) &&
            it.outsideTemperatureC?.let { temp -> temp in 10.0..25.0 } == true
    }
    val cold = validDrives.filter {
        it.averageSpeedKmh.inRange(50.0..90.0) &&
            it.outsideTemperatureC?.let { temp -> temp < 10.0 } == true
    }
    val normalTemperature = baseline

    val validCharges = charges.filter {
        val added = it.energyAddedKwh ?: return@filter false
        val used = it.energyUsedKwh ?: return@filter false
        added.isFinite() && added > 0.0 && used.isFinite() && used >= added
    }
    val usedEnergy = validCharges.mapNotNull { it.energyUsedKwh }.sum()
    val addedEnergy = validCharges.mapNotNull { it.energyAddedKwh }.sum()
    val lossPercent = if (usedEnergy > 0.0) {
        ((usedEnergy - addedEnergy) / usedEnergy * 100.0).coerceAtLeast(0.0)
    } else {
        null
    }

    val dates = (validDrives.mapNotNull { it.observedAt } + validCharges.mapNotNull { it.observedAt })
        .mapNotNull(::parseDate)
    val observationDays = if (dates.isEmpty()) null else {
        (ChronoUnit.DAYS.between(dates.min(), dates.max()) + 1L).toInt()
    }

    return RecommendationEvidence(
        observationDays = observationDays,
        highSpeedEfficiencyWhKm = highSpeed.weightedEfficiency(),
        highSpeedSampleCount = highSpeed.size,
        highSpeedDistanceKm = highSpeed.mapNotNull { it.distanceKm }.sum(),
        baselineEfficiencyWhKm = baseline.weightedEfficiency(),
        baselineSampleCount = baseline.size,
        baselineDistanceKm = baseline.mapNotNull { it.distanceKm }.sum(),
        coldEfficiencyWhKm = cold.weightedEfficiency(),
        coldSampleCount = cold.size,
        coldDistanceKm = cold.mapNotNull { it.distanceKm }.sum(),
        normalTemperatureEfficiencyWhKm = normalTemperature.weightedEfficiency(),
        normalTemperatureSampleCount = normalTemperature.size,
        normalTemperatureDistanceKm = normalTemperature.mapNotNull { it.distanceKm }.sum(),
        chargeLossPercent = lossPercent,
        chargeCount = validCharges.size,
        chargeEnergyKwh = usedEnergy
    )
}

private fun List<RecommendationDriveSample>.weightedEfficiency(): Double? =
    calculateWeightedEfficiency(
        map { EfficiencySample(distanceKm = it.distanceKm, energyKwh = it.energyKwh) }
    )?.efficiencyWhKm

private fun Double?.inRange(range: ClosedFloatingPointRange<Double>): Boolean =
    this?.takeIf { it.isFinite() }?.let { it in range } == true

private fun Double?.above(limit: Double): Boolean =
    this?.takeIf { it.isFinite() }?.let { it > limit } == true

private fun parseDate(value: String): LocalDate? = runCatching {
    LocalDate.parse(value.take(10))
}.getOrNull()

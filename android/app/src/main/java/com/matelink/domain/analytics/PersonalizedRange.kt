package com.matelink.domain.analytics

import java.time.LocalDate
import kotlin.math.min
import kotlin.math.roundToInt

enum class RangeTemperatureBand {
    COLD,
    MILD,
    HOT
}

enum class RangeSpeedBand {
    LOW,
    CRUISE,
    HIGH
}

enum class PersonalizedRangeSource {
    GROUPED,
    GLOBAL,
    UNAVAILABLE
}

data class PersonalizedRangeSample(
    val date: LocalDate,
    val distanceKm: Double?,
    val energyKwh: Double?,
    val speedKmh: Double?,
    val temperatureC: Double?
)

data class PersonalizedRangeEstimate(
    val rangeKm: Double?,
    val efficiencyWhKm: Double?,
    val source: PersonalizedRangeSource,
    val sampleCount: Int,
    val distanceKm: Double,
    val confidencePercent: Int,
    val temperatureBand: RangeTemperatureBand?,
    val speedBand: RangeSpeedBand?
)

private const val LOOKBACK_DAYS = 90L
private const val MIN_GROUP_SAMPLES = 5
private const val MIN_GROUP_DISTANCE_KM = 100.0
private const val MIN_GLOBAL_SAMPLES = 10
private const val MIN_GLOBAL_DISTANCE_KM = 300.0

/**
 * Estimates usable range from recent, distance-weighted driving efficiency.
 *
 * The selected temperature/speed cohort is used only after both minimum
 * sample gates pass. Otherwise the model falls back to the all-condition
 * recent sample, also behind explicit sample and distance gates. A numeric
 * range is never emitted without an observed usable-energy value.
 */
fun estimatePersonalizedRange(
    samples: List<PersonalizedRangeSample>,
    usableEnergyKwh: Double?,
    currentTemperatureC: Double?,
    currentSpeedKmh: Double?,
    asOf: LocalDate
): PersonalizedRangeEstimate {
    val recent = samples.filter { sample ->
        !sample.date.isAfter(asOf) &&
            !sample.date.isBefore(asOf.minusDays(LOOKBACK_DAYS))
    }
    val validRecent = recent.filter { sample ->
        sample.distanceKm.isValidPositive() && sample.energyKwh.isValidPositive()
    }
    val temperatureBand = currentTemperatureC?.temperatureBand()
    val speedBand = currentSpeedKmh?.speedBand()
    val grouped = if (temperatureBand != null && speedBand != null) {
        validRecent.filter { sample ->
            sample.temperatureC?.temperatureBand() == temperatureBand &&
                sample.speedKmh?.speedBand() == speedBand
        }
    } else {
        emptyList()
    }

    val groupedEfficiency = calculateWeightedEfficiency(grouped.toEfficiencySamples())
    val globalEfficiency = calculateWeightedEfficiency(validRecent.toEfficiencySamples())
    val selected = when {
        groupedEfficiency.meetsModelGate(MIN_GROUP_SAMPLES, MIN_GROUP_DISTANCE_KM) ->
            PersonalizedRangeSource.GROUPED to groupedEfficiency
        globalEfficiency.meetsModelGate(MIN_GLOBAL_SAMPLES, MIN_GLOBAL_DISTANCE_KM) ->
            PersonalizedRangeSource.GLOBAL to globalEfficiency
        else -> PersonalizedRangeSource.UNAVAILABLE to null
    }

    val source = selected.first
    val efficiency = selected.second
    val rangeKm = if (efficiency != null) {
        usableEnergyKwh
            ?.takeIf { it.isValidPositive() }
            ?.let { it * 1000.0 / efficiency.efficiencyWhKm!! }
            ?.takeIf { it.isFinite() && it > 0.0 }
    } else {
        null
    }

    return PersonalizedRangeEstimate(
        rangeKm = rangeKm,
        efficiencyWhKm = efficiency?.efficiencyWhKm,
        source = source,
        sampleCount = efficiency?.sampleCount ?: 0,
        distanceKm = efficiency?.validDistanceKm ?: 0.0,
        confidencePercent = efficiency?.confidencePercent() ?: 0,
        temperatureBand = temperatureBand,
        speedBand = speedBand
    )
}

fun Double?.temperatureBand(): RangeTemperatureBand? = this
    ?.takeIf { it.isFinite() }
    ?.let {
        when {
            it < 10.0 -> RangeTemperatureBand.COLD
            it <= 25.0 -> RangeTemperatureBand.MILD
            else -> RangeTemperatureBand.HOT
        }
    }

fun Double?.speedBand(): RangeSpeedBand? = this
    ?.takeIf { it.isFinite() && it >= 0.0 }
    ?.let {
        when {
            it < 50.0 -> RangeSpeedBand.LOW
            it <= 90.0 -> RangeSpeedBand.CRUISE
            else -> RangeSpeedBand.HIGH
        }
    }

private fun List<PersonalizedRangeSample>.toEfficiencySamples(): List<EfficiencySample> =
    map { EfficiencySample(it.distanceKm, it.energyKwh) }

private fun WeightedEfficiencyResult.meetsModelGate(
    minimumSamples: Int,
    minimumDistanceKm: Double
): Boolean =
    efficiencyWhKm != null &&
        sampleCount >= minimumSamples &&
        validDistanceKm >= minimumDistanceKm

private fun WeightedEfficiencyResult.confidencePercent(): Int {
    val sampleScore = min(1.0, sampleCount / 20.0)
    val distanceScore = min(1.0, validDistanceKm / 1000.0)
    return (50.0 + (sampleScore * 30.0 + distanceScore * 20.0))
        .roundToInt()
        .coerceIn(50, 95)
}

private fun Double?.isValidPositive(): Boolean =
    this != null && this.isFinite() && this > 0.0

package com.matelink.domain.analytics

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

data class AnalysisDriveCoverageSample(
    val distanceKm: Double?,
    val energyKwh: Double?,
    val observedAt: String?
)

data class AnalysisChargeCoverageSample(
    val energyAddedKwh: Double?,
    val cost: Double?,
    val observedAt: String?,
    val energyUsedKwh: Double? = null
)

/**
 * Describes how much of the selected history has usable inputs for each metric.
 * A percentage is null only when there are no records of that type; a 0% value
 * means records exist but none contains a valid input for that metric.
 */
data class AnalysisCoverage(
    val driveRecordCount: Int,
    val driveDistanceSampleCount: Int,
    val driveEnergySampleCount: Int,
    val chargeRecordCount: Int,
    val chargeEnergySampleCount: Int,
    val chargeCostSampleCount: Int,
    val firstObservedDate: LocalDate?,
    val lastObservedDate: LocalDate?,
    val chargeEnergyUsedSampleCount: Int = 0,
    val chargeLossSampleCount: Int = 0,
    val chargeEnergyUsedForLossKwh: Double? = null,
    val chargeEnergyAddedForLossKwh: Double? = null
) {
    val distanceCoveragePercent: Double?
        get() = coverage(driveDistanceSampleCount, driveRecordCount)
    val driveEnergyCoveragePercent: Double?
        get() = coverage(driveEnergySampleCount, driveRecordCount)
    val chargeEnergyCoveragePercent: Double?
        get() = coverage(chargeEnergySampleCount, chargeRecordCount)
    val chargeEnergyUsedCoveragePercent: Double?
        get() = coverage(chargeEnergyUsedSampleCount, chargeRecordCount)
    val costCoveragePercent: Double?
        get() = coverage(chargeCostSampleCount, chargeRecordCount)
    val chargeLossCoveragePercent: Double?
        get() = coverage(chargeLossSampleCount, chargeRecordCount)
    val observationDays: Long?
        get() = if (firstObservedDate != null && lastObservedDate != null) {
            ChronoUnit.DAYS.between(firstObservedDate, lastObservedDate)
        } else {
            null
        }

    private fun coverage(valid: Int, total: Int): Double? =
        total.takeIf { it > 0 }?.let { valid * 100.0 / it }
}

fun buildAnalysisCoverage(
    drives: List<AnalysisDriveCoverageSample>,
    charges: List<AnalysisChargeCoverageSample>
): AnalysisCoverage {
    val driveDates = drives.mapNotNull { parseObservedDate(it.observedAt) }
    val chargeDates = charges.mapNotNull { parseObservedDate(it.observedAt) }
    val dates = driveDates + chargeDates

    val validLossSamples = charges.filter { sample ->
        val added = sample.energyAddedKwh
        val used = sample.energyUsedKwh
        added.isValidPositive() && used.isValidPositive() && used!! >= added!!
    }

    return AnalysisCoverage(
        driveRecordCount = drives.size,
        driveDistanceSampleCount = drives.count { it.distanceKm.isValidPositive() },
        driveEnergySampleCount = drives.count { it.energyKwh.isValidPositive() },
        chargeRecordCount = charges.size,
        chargeEnergySampleCount = charges.count { it.energyAddedKwh.isValidPositive() },
        chargeCostSampleCount = charges.count { it.cost.isValidNonNegative() },
        firstObservedDate = dates.minOrNull(),
        lastObservedDate = dates.maxOrNull(),
        chargeEnergyUsedSampleCount = charges.count { it.energyUsedKwh.isValidPositive() },
        chargeLossSampleCount = validLossSamples.size,
        chargeEnergyUsedForLossKwh = validLossSamples.mapNotNull { it.energyUsedKwh }.sum()
            .takeIf { validLossSamples.isNotEmpty() },
        chargeEnergyAddedForLossKwh = validLossSamples.mapNotNull { it.energyAddedKwh }.sum()
            .takeIf { validLossSamples.isNotEmpty() }
    )
}

private fun Double?.isValidPositive(): Boolean = this?.isFinite() == true && this > 0.0

private fun Double?.isValidNonNegative(): Boolean = this?.isFinite() == true && this >= 0.0

private fun parseObservedDate(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(value) }.getOrNull()
}

package com.matelink.domain.analytics

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import com.matelink.util.parseIsoInstant

data class AnalysisDriveCoverageSample(
    val distanceKm: Double?,
    val energyKwh: Double?,
    val observedAt: String?,
    val energyQuality: String? = null
)

data class AnalysisChargeCoverageSample(
    val energyAddedKwh: Double?,
    val cost: Double?,
    val observedAt: String?,
    val energyUsedKwh: Double? = null,
    val acBalanceQualified: Boolean = false
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
    val chargeEnergyAddedForLossKwh: Double? = null,
    /** A positive count means the aggregate must be labeled estimated. */
    val driveEnergyEstimatedSampleCount: Int = 0,
    /** Values and counts come from the same qualified history snapshot. */
    val qualifiedDistanceTotalKm: Double? = null,
    val qualifiedDriveEnergyTotalKwh: Double? = null,
    val qualifiedChargeEnergyTotalKwh: Double? = null,
    val pairedEfficiency: WeightedEfficiencyResult? = null,
    val pairedEstimatedEnergySampleCount: Int = 0,
    val driveDistanceDayCount: Int = 0
) {
    val distanceCoveragePercent: Double?
        get() = coverage(driveDistanceSampleCount, driveRecordCount)
    val driveEnergyCoveragePercent: Double?
        get() = coverage(driveEnergySampleCount, driveRecordCount)
    val efficiencyCoveragePercent: Double?
        get() = coverage(pairedEfficiency?.sampleCount ?: 0, driveRecordCount)
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
    val distances = drives.mapNotNull { it.distanceKm?.takeIf { value -> value.isFinite() && value >= 0.0 } }
    val driveEnergies = drives.mapNotNull { it.energyKwh?.takeIf(Double::isFinite) }
    val chargeEnergies = charges.mapNotNull { it.energyAddedKwh?.takeIf { value -> value.isFinite() && value >= 0.0 } }
    val pairedEfficiency = calculateWeightedEfficiency(drives.map { EfficiencySample(it.distanceKm, it.energyKwh) })

    val validLossSamples = charges.filter { sample ->
        val added = sample.energyAddedKwh
        val used = sample.energyUsedKwh
        sample.acBalanceQualified && added.isValidNonNegative() && used.isValidPositive() && used!! >= added!!
    }

    return AnalysisCoverage(
        driveRecordCount = drives.size,
        driveDistanceSampleCount = distances.size,
        driveEnergySampleCount = driveEnergies.size,
        driveEnergyEstimatedSampleCount = drives.count {
            it.energyKwh.isValidSigned() && it.energyQuality != "reported"
        },
        chargeRecordCount = charges.size,
        chargeEnergySampleCount = chargeEnergies.size,
        chargeCostSampleCount = charges.count { it.cost.isValidNonNegative() },
        firstObservedDate = dates.minOrNull(),
        lastObservedDate = dates.maxOrNull(),
        chargeEnergyUsedSampleCount = charges.count { it.energyUsedKwh.isValidNonNegative() },
        chargeLossSampleCount = validLossSamples.size,
        chargeEnergyUsedForLossKwh = validLossSamples.mapNotNull { it.energyUsedKwh }.sum()
            .takeIf { validLossSamples.isNotEmpty() },
        chargeEnergyAddedForLossKwh = validLossSamples.mapNotNull { it.energyAddedKwh }.sum()
            .takeIf { validLossSamples.isNotEmpty() },
        qualifiedDistanceTotalKm = distances.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite),
        qualifiedDriveEnergyTotalKwh = driveEnergies.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite),
        qualifiedChargeEnergyTotalKwh = chargeEnergies.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite),
        pairedEfficiency = pairedEfficiency,
        pairedEstimatedEnergySampleCount = drives.count { sample ->
            sample.distanceKm.isValidPositive() && sample.energyKwh.isValidSigned() &&
                sample.energyQuality != "reported"
        },
        driveDistanceDayCount = drives.filter { it.distanceKm.isValidNonNegative() }
            .mapNotNull { parseObservedDate(it.observedAt) }.distinct().size
    )
}

private fun Double?.isValidPositive(): Boolean = this?.isFinite() == true && this > 0.0
private fun Double?.isValidSigned(): Boolean = this?.isFinite() == true

private fun Double?.isValidNonNegative(): Boolean = this?.isFinite() == true && this >= 0.0

private fun parseObservedDate(value: String?): LocalDate? {
	if (value.isNullOrBlank()) return null
	return runCatching { LocalDate.parse(value) }.getOrNull()
		?: parseIsoInstant(value)?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()
}

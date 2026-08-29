package com.matelink.domain.analytics

import java.time.LocalDate

enum class StandbyRange {
    LAST_7_DAYS,
    LAST_30_DAYS,
    LAST_YEAR,
    ALL_TIME,
    CUSTOM
}

enum class StandbyExclusion {
    CHARGING_OVERLAP,
    TOO_SHORT,
    SOC_NOT_OBSERVED,
    SOC_NOT_DECREASING
}

data class StandbyWindowInput(
    val date: LocalDate,
    val startDate: String? = null,
    val endDate: String? = null,
    val address: String? = null,
    val durationHours: Double,
    val batteryDeltaPercent: Int?,
    val chargingOverlap: Boolean = false,
    val coveragePercent: Double = 0.0,
    val energyKwh: Double? = null,
    val averagePowerW: Double? = null,
    val peakPowerW: Double? = null,
    val climateActivePercent: Double? = null,
    val climateSampleCount: Int = 0
)

data class QualifiedStandbyWindow(
    val input: StandbyWindowInput,
    val isEligible: Boolean,
    val exclusion: StandbyExclusion? = null,
    val socDeltaPercent: Int? = null,
    val energyKwh: Double? = null,
    val averagePowerW: Double? = null,
    val peakPowerW: Double? = null
)

data class StandbyWindowSummary(
    val eligibleWindowCount: Int,
    val qualifiedHours: Double,
    val hasStableConclusion: Boolean,
    val totalSocDeltaPercent: Int,
    val totalEnergyKwh: Double?,
    val averagePowerW: Double?
)

fun qualifyStandbyWindow(input: StandbyWindowInput): QualifiedStandbyWindow {
    val exclusion = when {
        input.chargingOverlap -> StandbyExclusion.CHARGING_OVERLAP
        !input.durationHours.isFinite() || input.durationHours < 2.0 -> StandbyExclusion.TOO_SHORT
        input.batteryDeltaPercent == null -> StandbyExclusion.SOC_NOT_OBSERVED
        input.batteryDeltaPercent >= 0 -> StandbyExclusion.SOC_NOT_DECREASING
        else -> null
    }
    if (exclusion != null) {
        return QualifiedStandbyWindow(input = input, isEligible = false, exclusion = exclusion)
    }

    val hasPowerCoverage = input.coveragePercent.isFinite() && input.coveragePercent >= 80.0
    return QualifiedStandbyWindow(
        input = input,
        isEligible = true,
        socDeltaPercent = input.batteryDeltaPercent,
        energyKwh = input.energyKwh?.takeIf { hasPowerCoverage && it.isFinite() && it >= 0.0 },
        averagePowerW = input.averagePowerW?.takeIf { hasPowerCoverage && it.isFinite() && it >= 0.0 },
        peakPowerW = input.peakPowerW?.takeIf { hasPowerCoverage && it.isFinite() && it >= 0.0 }
    )
}

fun summarizeStandbyWindows(
    windows: List<QualifiedStandbyWindow>,
    range: StandbyRange,
    asOf: LocalDate,
    customStart: LocalDate? = null,
    customEnd: LocalDate? = null
): StandbyWindowSummary {
    val selected = selectStandbyWindows(windows, range, asOf, customStart, customEnd)
    val eligible = selected.filter { it.isEligible }
    val hours = eligible.sumOf { it.input.durationHours }
    val powers = eligible.mapNotNull { it.averagePowerW }
    val energies = eligible.mapNotNull { it.energyKwh }
    return StandbyWindowSummary(
        eligibleWindowCount = eligible.size,
        qualifiedHours = hours,
        hasStableConclusion = eligible.size >= 5 && hours >= 20.0,
        totalSocDeltaPercent = eligible.sumOf { it.socDeltaPercent ?: 0 },
        totalEnergyKwh = energies.takeIf { it.isNotEmpty() }?.sum(),
        averagePowerW = powers.takeIf { it.isNotEmpty() }?.average()
    )
}

fun selectStandbyWindows(
    windows: List<QualifiedStandbyWindow>,
    range: StandbyRange,
    asOf: LocalDate,
    customStart: LocalDate? = null,
    customEnd: LocalDate? = null
): List<QualifiedStandbyWindow> = windows.filter {
    it.input.date.inStandbyRange(range, asOf, customStart, customEnd)
}

private fun LocalDate.inStandbyRange(
    range: StandbyRange,
    asOf: LocalDate,
    customStart: LocalDate?,
    customEnd: LocalDate?
): Boolean = when (range) {
    StandbyRange.LAST_7_DAYS -> !isBefore(asOf.minusDays(6)) && !isAfter(asOf)
    StandbyRange.LAST_30_DAYS -> !isBefore(asOf.minusDays(29)) && !isAfter(asOf)
    StandbyRange.LAST_YEAR -> !isBefore(asOf.minusDays(364)) && !isAfter(asOf)
    StandbyRange.ALL_TIME -> true
    StandbyRange.CUSTOM -> customStart != null && customEnd != null && !isBefore(customStart) && !isAfter(customEnd)
}

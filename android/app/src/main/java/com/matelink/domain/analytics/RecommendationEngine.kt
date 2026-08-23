package com.matelink.domain.analytics

enum class RecommendationKind {
    STANDBY_POWER,
    HIGH_SPEED_EFFICIENCY,
    COLD_WEATHER_EFFICIENCY,
    CHARGE_LOSS
}

data class RecommendationEvidence(
    val observationDays: Int? = null,
    val standbyAveragePowerW: Double? = null,
    val standbyWindowCount: Int = 0,
    val standbyHours: Double = 0.0,
    val highSpeedEfficiencyWhKm: Double? = null,
    val highSpeedSampleCount: Int = 0,
    val highSpeedDistanceKm: Double = 0.0,
    val baselineEfficiencyWhKm: Double? = null,
    val baselineSampleCount: Int = 0,
    val baselineDistanceKm: Double = 0.0,
    val coldEfficiencyWhKm: Double? = null,
    val coldSampleCount: Int = 0,
    val coldDistanceKm: Double = 0.0,
    val normalTemperatureEfficiencyWhKm: Double? = null,
    val normalTemperatureSampleCount: Int = 0,
    val normalTemperatureDistanceKm: Double = 0.0,
    val chargeLossPercent: Double? = null,
    val chargeCount: Int = 0,
    val chargeEnergyKwh: Double = 0.0
)

data class RecommendationImpact(
    val minimumMonthlyKwh: Double,
    val maximumMonthlyKwh: Double
)

data class Recommendation(
    val kind: RecommendationKind,
    val triggerValue: Double,
    val thresholdValue: Double,
    val sampleCount: Int,
    val coverage: Double,
    val observationDays: Int,
    val confidencePercent: Int,
    val monthlyImpact: RecommendationImpact
)

/**
 * Generates only evidence-backed, read-only suggestions.
 * It intentionally returns no generic advice when the data gates are not met.
 */
fun buildRecommendations(evidence: RecommendationEvidence): List<Recommendation> = buildList {
    val observationDays = evidence.observationDays?.takeIf { it >= 14 } ?: return@buildList
    val standbyPower = evidence.standbyAveragePowerW
    if (standbyPower != null && standbyPower.isFinite() &&
        standbyPower > 200.0 && evidence.standbyWindowCount >= 5 && evidence.standbyHours >= 20.0
    ) {
        add(
            Recommendation(
                kind = RecommendationKind.STANDBY_POWER,
                triggerValue = standbyPower,
                thresholdValue = 200.0,
                sampleCount = evidence.standbyWindowCount,
                coverage = evidence.standbyHours,
                observationDays = observationDays,
                confidencePercent = confidence(evidence.standbyWindowCount, evidence.standbyHours, 5, 20.0),
                monthlyImpact = monthlyImpact(
                    observedSavingsKwh = (standbyPower - 200.0) * evidence.standbyHours / 1000.0,
                    observationDays = observationDays
                )
            )
        )
    }

    addEfficiencyRecommendation(
        kind = RecommendationKind.HIGH_SPEED_EFFICIENCY,
        comparison = evidence.highSpeedEfficiencyWhKm,
        comparisonSamples = evidence.highSpeedSampleCount,
        comparisonCoverage = evidence.highSpeedDistanceKm,
        baseline = evidence.baselineEfficiencyWhKm,
        baselineSamples = evidence.baselineSampleCount,
        baselineCoverage = evidence.baselineDistanceKm,
        observationDays = observationDays
    )
    addEfficiencyRecommendation(
        kind = RecommendationKind.COLD_WEATHER_EFFICIENCY,
        comparison = evidence.coldEfficiencyWhKm,
        comparisonSamples = evidence.coldSampleCount,
        comparisonCoverage = evidence.coldDistanceKm,
        baseline = evidence.normalTemperatureEfficiencyWhKm,
        baselineSamples = evidence.normalTemperatureSampleCount,
        baselineCoverage = evidence.normalTemperatureDistanceKm,
        observationDays = observationDays
    )

    val chargeLoss = evidence.chargeLossPercent
    if (chargeLoss != null && chargeLoss.isFinite() &&
        chargeLoss > 12.0 && evidence.chargeCount >= 5 && evidence.chargeEnergyKwh >= 50.0
    ) {
        add(
            Recommendation(
                kind = RecommendationKind.CHARGE_LOSS,
                triggerValue = chargeLoss,
                thresholdValue = 12.0,
                sampleCount = evidence.chargeCount,
                coverage = evidence.chargeEnergyKwh,
                observationDays = observationDays,
                confidencePercent = confidence(evidence.chargeCount, evidence.chargeEnergyKwh, 5, 50.0),
                monthlyImpact = monthlyImpact(
                    observedSavingsKwh = evidence.chargeEnergyKwh * (chargeLoss - 12.0) / 100.0,
                    observationDays = observationDays
                )
            )
        )
    }
}

private fun MutableList<Recommendation>.addEfficiencyRecommendation(
    kind: RecommendationKind,
    comparison: Double?,
    comparisonSamples: Int,
    comparisonCoverage: Double,
    baseline: Double?,
    baselineSamples: Int,
    baselineCoverage: Double,
    observationDays: Int
) {
    val comparisonValue = comparison?.takeIf { it.isFinite() && it > 0.0 } ?: return
    val baselineValue = baseline?.takeIf { it.isFinite() && it > 0.0 } ?: return
    if (comparisonSamples < 5 || comparisonCoverage < 100.0 ||
        baselineSamples < 5 || baselineCoverage < 100.0
    ) return

    val threshold = baselineValue * 1.15
    if (comparisonValue <= threshold) return

    add(
        Recommendation(
            kind = kind,
            triggerValue = comparisonValue,
            thresholdValue = threshold,
            sampleCount = comparisonSamples + baselineSamples,
            coverage = comparisonCoverage + baselineCoverage,
            observationDays = observationDays,
            confidencePercent = confidence(
                comparisonSamples + baselineSamples,
                comparisonCoverage + baselineCoverage,
                10,
                200.0
            ),
            monthlyImpact = monthlyImpact(
                observedSavingsKwh = (comparisonValue - baselineValue) * comparisonCoverage / 1000.0,
                observationDays = observationDays
            )
        )
    )
}

private fun monthlyImpact(observedSavingsKwh: Double, observationDays: Int): RecommendationImpact {
    val normalized = (observedSavingsKwh.coerceAtLeast(0.0) * 30.0 / observationDays)
    return RecommendationImpact(
        minimumMonthlyKwh = normalized * 0.5,
        maximumMonthlyKwh = normalized
    )
}

private fun confidence(sampleCount: Int, coverage: Double, minSamples: Int, minCoverage: Double): Int {
    val sampleScore = (sampleCount.toDouble() / (minSamples * 2)).coerceIn(0.0, 1.0)
    val coverageScore = (coverage / (minCoverage * 2)).coerceIn(0.0, 1.0)
    return ((sampleScore * 0.6 + coverageScore * 0.4) * 100.0).toInt().coerceIn(0, 100)
}

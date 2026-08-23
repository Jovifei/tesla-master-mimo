package com.matelink.domain.analytics

import com.matelink.domain.model.QuickStats

data class AnalysisSummary(
    val distanceKm: MetricState<Double>,
    val drivingEnergyKwh: MetricState<Double>,
    val efficiencyWhKm: MetricState<Double>,
    val chargedEnergyKwh: MetricState<Double>,
    val totalCost: MetricState<Double>,
    val sourceRecordCount: Int
)

/**
 * Additional conclusions derived from the same selected-period aggregates.
 * These are intentionally kept separate from observed totals so the UI can
 * label the calculation instead of presenting it as a server measurement.
 */
data class AnalysisConclusions(
    val averageDriveDistanceKm: MetricState<Double>,
    val averageDistancePerDrivingDayKm: MetricState<Double> = MetricState.Unavailable(
        "No valid driving days"
    ),
    val averageChargeEnergyKwh: MetricState<Double>,
    val costPer100Km: MetricState<Double>,
    val chargedToDrivingEnergyPercent: MetricState<Double>,
    val chargingLossPercent: MetricState<Double> = MetricState.Unavailable(
        "Grid and battery charge energy is unavailable"
    )
)

/**
 * Keeps an empty metric honest while the provider is still collecting its
 * first history records. Existing values are never replaced.
 */
fun AnalysisSummary.withHistoryCollectionState(
    drivesCollecting: Boolean,
    chargesCollecting: Boolean
): AnalysisSummary = copy(
    distanceKm = distanceKm.collectingWhenUnavailable(drivesCollecting),
    drivingEnergyKwh = drivingEnergyKwh.collectingWhenUnavailable(drivesCollecting),
    efficiencyWhKm = efficiencyWhKm.collectingWhenUnavailable(drivesCollecting),
    chargedEnergyKwh = chargedEnergyKwh.collectingWhenUnavailable(chargesCollecting),
    totalCost = totalCost.collectingWhenUnavailable(chargesCollecting)
)

fun AnalysisConclusions.withHistoryCollectionState(
    drivesCollecting: Boolean,
    chargesCollecting: Boolean
): AnalysisConclusions = copy(
    averageDriveDistanceKm = averageDriveDistanceKm.collectingWhenUnavailable(drivesCollecting),
    averageDistancePerDrivingDayKm = averageDistancePerDrivingDayKm.collectingWhenUnavailable(
        drivesCollecting
    ),
    averageChargeEnergyKwh = averageChargeEnergyKwh.collectingWhenUnavailable(chargesCollecting),
    costPer100Km = costPer100Km.collectingWhenUnavailable(drivesCollecting || chargesCollecting),
    chargedToDrivingEnergyPercent = chargedToDrivingEnergyPercent.collectingWhenUnavailable(
        drivesCollecting || chargesCollecting
    ),
    chargingLossPercent = chargingLossPercent.collectingWhenUnavailable(chargesCollecting)
)

/** Builds the compact conclusion layer without changing the existing detail cards. */
fun buildAnalysisSummary(
    stats: QuickStats,
    coverage: AnalysisCoverage? = null
): AnalysisSummary {
    val driveCount = stats.totalDrives
    val chargeCount = stats.totalCharges
    val distanceSamples = coverage?.driveDistanceSampleCount ?: driveCount
    val driveEnergySamples = coverage?.driveEnergySampleCount ?: driveCount
    val chargeEnergySamples = coverage?.chargeEnergySampleCount ?: chargeCount
    val costSamples = coverage?.chargeCostSampleCount ?: chargeCount
    val distance = stats.totalDistanceKm.takeIf {
        distanceSamples > 0 && it.isFinite() && it >= 0.0
    }
    val drivingEnergy = stats.totalEnergyConsumedKwh
        .takeIf { driveEnergySamples > 0 && it.isFinite() && it >= 0.0 }
    val chargedEnergy = stats.totalEnergyAddedKwh
        .takeIf { chargeEnergySamples > 0 && it.isFinite() && it >= 0.0 }
    val efficiency = stats.avgEfficiencyWhKm
        .takeIf {
            distanceSamples > 0 && driveEnergySamples > 0 && it.isFinite() && it >= 0.0
        }
    val efficiencySamples = minOf(distanceSamples, driveEnergySamples)
    val cost = stats.totalCost?.takeIf { costSamples > 0 && it.isFinite() && it >= 0.0 }

    return AnalysisSummary(
        distanceKm = distance.toObserved(MetricSource.TESLAMATE, distanceSamples),
        drivingEnergyKwh = drivingEnergy.toObserved(MetricSource.TESLAMATE, driveEnergySamples),
        efficiencyWhKm = efficiency.toDerived(MetricSource.LOCAL_CALCULATION, efficiencySamples),
        chargedEnergyKwh = chargedEnergy.toObserved(MetricSource.TESLAMATE, chargeEnergySamples),
        totalCost = cost.toObserved(MetricSource.TESLAMATE, costSamples),
        sourceRecordCount = driveCount + chargeCount
    )
}

fun buildAnalysisConclusions(
    stats: QuickStats,
    coverage: AnalysisCoverage? = null
): AnalysisConclusions {
    val distanceSamples = coverage?.driveDistanceSampleCount ?: stats.totalDrives
    val driveEnergySamples = coverage?.driveEnergySampleCount ?: stats.totalDrives
    val chargeEnergySamples = coverage?.chargeEnergySampleCount ?: stats.totalCharges
    val costSamples = coverage?.chargeCostSampleCount ?: stats.totalCharges
    val distance = stats.totalDistanceKm.takeIf {
        distanceSamples > 0 && it.isFinite() && it >= 0.0
    }
    val drivingEnergy = stats.totalEnergyConsumedKwh.takeIf {
        driveEnergySamples > 0 && it.isFinite() && it >= 0.0
    }
    val chargedEnergy = stats.totalEnergyAddedKwh.takeIf {
        chargeEnergySamples > 0 && it.isFinite() && it >= 0.0
    }
    val drivingDays = stats.totalDrivingDays?.takeIf { it > 0 }
    val cost = stats.totalCost?.takeIf {
        costSamples > 0 && it.isFinite() && it >= 0.0
    }
    val costMetricSamples = minOf(distanceSamples, costSamples)
    val energyRatioSamples = minOf(driveEnergySamples, chargeEnergySamples)
    val chargeLossSampleCount = coverage?.chargeLossSampleCount ?: 0
    val chargeEnergyUsed = coverage?.chargeEnergyUsedForLossKwh
    val chargeEnergyAddedForLoss = coverage?.chargeEnergyAddedForLossKwh

    return AnalysisConclusions(
        averageDriveDistanceKm = derivedMetric(
            value = distance?.takeIf { distanceSamples > 0 }?.div(distanceSamples),
            sampleCount = distanceSamples,
            unavailableReason = "No valid drive distance"
        ),
        averageDistancePerDrivingDayKm = derivedMetric(
            value = if (distance != null && drivingDays != null) {
                distance / drivingDays
            } else {
                null
            },
            sampleCount = drivingDays ?: 0,
            unavailableReason = "No valid driving days"
        ),
        averageChargeEnergyKwh = derivedMetric(
            value = chargedEnergy?.takeIf { chargeEnergySamples > 0 }?.div(chargeEnergySamples),
            sampleCount = chargeEnergySamples,
            unavailableReason = "No valid charge energy"
        ),
        costPer100Km = derivedMetric(
            value = if (cost != null && distance != null && distance > 0.0) {
                cost / distance * 100.0
            } else {
                null
            },
            sampleCount = costMetricSamples,
            unavailableReason = "Cost or distance is unavailable"
        ),
        chargedToDrivingEnergyPercent = derivedMetric(
            value = if (chargedEnergy != null && drivingEnergy != null && drivingEnergy > 0.0) {
                chargedEnergy / drivingEnergy * 100.0
            } else {
                null
            },
            sampleCount = energyRatioSamples,
            unavailableReason = "Driving or charging energy is unavailable"
        ),
        chargingLossPercent = derivedMetric(
            value = if (
                chargeEnergyUsed != null &&
                chargeEnergyAddedForLoss != null &&
                chargeEnergyUsed > 0.0 &&
                chargeEnergyUsed >= chargeEnergyAddedForLoss
            ) {
                (chargeEnergyUsed - chargeEnergyAddedForLoss) / chargeEnergyUsed * 100.0
            } else {
                null
            },
            sampleCount = chargeLossSampleCount,
            unavailableReason = "Grid and battery charge energy is unavailable"
        )
    )
}

private fun Double?.toObserved(source: MetricSource, sampleCount: Int): MetricState<Double> =
    this?.let {
        MetricState.Available(
            value = it,
            evidence = MetricEvidence.OBSERVED,
            source = source,
            sampleCount = sampleCount
        )
    } ?: MetricState.Unavailable("No valid source value")

private fun Double?.toDerived(source: MetricSource, sampleCount: Int): MetricState<Double> =
    this?.let {
        MetricState.Available(
            value = it,
            evidence = MetricEvidence.DERIVED,
            source = source,
            sampleCount = sampleCount
        )
    } ?: MetricState.Unavailable("Insufficient distance or energy")

private fun derivedMetric(
    value: Double?,
    sampleCount: Int,
    unavailableReason: String
): MetricState<Double> = value
    ?.takeIf { it.isFinite() && it >= 0.0 }
    ?.let {
        MetricState.Available(
            value = it,
            evidence = MetricEvidence.DERIVED,
            source = MetricSource.LOCAL_CALCULATION,
            sampleCount = sampleCount
        )
    }
    ?: MetricState.Unavailable(unavailableReason)

private fun <T> MetricState<T>.collectingWhenUnavailable(
    enabled: Boolean
): MetricState<T> = if (enabled && this is MetricState.Unavailable) {
    MetricState.Collecting()
} else {
    this
}

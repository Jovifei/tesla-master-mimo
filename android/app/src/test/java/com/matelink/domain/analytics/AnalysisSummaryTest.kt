package com.matelink.domain.analytics

import com.matelink.domain.model.QuickStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSummaryTest {
    @Test
    fun efficiencyIsDerivedAndUsesDriveSampleCount() {
        val summary = buildAnalysisSummary(sampleStats())
        val efficiency = summary.efficiencyWhKm as MetricState.Available

        assertEquals(MetricEvidence.DERIVED, efficiency.evidence)
        assertEquals(4, efficiency.sampleCount)
        assertEquals(180.0, efficiency.value, 0.0001)
    }

    @Test
    fun unavailableCostIsNotRenderedAsZero() {
        val summary = buildAnalysisSummary(sampleStats().copy(totalCost = null))

        assertTrue(summary.totalCost is MetricState.Unavailable)
    }

    @Test
    fun metricStateKeepsCollectingAndRetryableFailureDistinct() {
        val collecting = MetricState.Collecting(progressPercent = 35)
        val failed = MetricState.Failed(message = "temporary", retryable = true)

        assertEquals(35, collecting.progressPercent)
        assertEquals("temporary", failed.message)
        assertTrue(failed.retryable)
        assertFalse(MetricState.Failed(message = "permanent", retryable = false).retryable)
    }

    @Test
    fun historyCollectionStateOnlyChangesUnavailableMetrics() {
        val summary = buildAnalysisSummary(sampleStats().copy(totalCost = null))
            .withHistoryCollectionState(drivesCollecting = true, chargesCollecting = true)
        val conclusions = buildAnalysisConclusions(sampleStats().copy(totalCost = null))
            .withHistoryCollectionState(drivesCollecting = true, chargesCollecting = true)

        assertTrue(summary.totalCost is MetricState.Collecting)
        assertTrue(summary.distanceKm is MetricState.Available)
        assertTrue(conclusions.costPer100Km is MetricState.Collecting)
        assertTrue(conclusions.averageDriveDistanceKm is MetricState.Available)
    }

    @Test
    fun derivedConclusionsUseSelectedPeriodAggregates() {
        val conclusions = buildAnalysisConclusions(sampleStats())

        assertEquals(25.0, available(conclusions.averageDriveDistanceKm).value, 0.0001)
        assertEquals(33.3333, available(conclusions.averageDistancePerDrivingDayKm).value, 0.0001)
        assertEquals(12.5, available(conclusions.averageChargeEnergyKwh).value, 0.0001)
        assertEquals(20.0, available(conclusions.costPer100Km).value, 0.0001)
        assertEquals(138.8889, available(conclusions.chargedToDrivingEnergyPercent).value, 0.0001)
        assertEquals(MetricEvidence.DERIVED, available(conclusions.costPer100Km).evidence)
    }

    @Test
    fun derivedConclusionsKeepMissingInputsUnavailable() {
        val conclusions = buildAnalysisConclusions(sampleStats().copy(totalCost = null))

        assertTrue(conclusions.costPer100Km is MetricState.Unavailable)
        assertTrue(conclusions.chargedToDrivingEnergyPercent is MetricState.Available)
    }

    @Test
    fun pairedDerivedMetricsUseConservativeSampleCounts() {
        val coverage = AnalysisCoverage(
            driveRecordCount = 4,
            driveDistanceSampleCount = 4,
            driveEnergySampleCount = 2,
            chargeRecordCount = 3,
            chargeEnergySampleCount = 1,
            chargeCostSampleCount = 1,
            firstObservedDate = null,
            lastObservedDate = null
        )

        val summary = buildAnalysisSummary(sampleStats(), coverage)
        val conclusions = buildAnalysisConclusions(sampleStats(), coverage)

        assertEquals(2, available(summary.efficiencyWhKm).sampleCount)
        assertEquals(1, available(conclusions.costPer100Km).sampleCount)
        assertEquals(1, available(conclusions.chargedToDrivingEnergyPercent).sampleCount)
    }

    @Test
    fun chargingLossUsesOnlyPairedGridAndBatteryEnergy() {
        val coverage = buildAnalysisCoverage(
            drives = emptyList(),
            charges = listOf(
                AnalysisChargeCoverageSample(
                    energyAddedKwh = 30.0,
                    cost = 0.0,
                    observedAt = "2026-01-01T10:00:00",
                    energyUsedKwh = 35.0
                ),
                AnalysisChargeCoverageSample(
                    energyAddedKwh = 10.0,
                    cost = null,
                    observedAt = "2026-01-02T10:00:00",
                    energyUsedKwh = 10.0
                ),
                AnalysisChargeCoverageSample(
                    energyAddedKwh = 12.0,
                    cost = null,
                    observedAt = "2026-01-03T10:00:00",
                    energyUsedKwh = null
                )
            )
        )

        val conclusions = buildAnalysisConclusions(sampleStats(), coverage)
        val loss = available(conclusions.chargingLossPercent)

        assertEquals(2, loss.sampleCount)
        assertEquals(5.0 / 45.0 * 100.0, loss.value, 0.0001)
        assertEquals(66.6667, coverage.chargeLossCoveragePercent!!, 0.0001)
    }

    @Test
    fun chargingLossRemainsUnavailableWhenGridEnergyIsLowerThanBatteryEnergy() {
        val coverage = buildAnalysisCoverage(
            drives = emptyList(),
            charges = listOf(
                AnalysisChargeCoverageSample(
                    energyAddedKwh = 30.0,
                    cost = null,
                    observedAt = "2026-01-01T10:00:00",
                    energyUsedKwh = 29.0
                )
            )
        )

        val conclusions = buildAnalysisConclusions(sampleStats(), coverage)

        assertTrue(conclusions.chargingLossPercent is MetricState.Unavailable)
        assertEquals(0, coverage.chargeLossSampleCount)
    }

    @Test
    fun zeroObservedTotalsRemainAvailableWhenRecordsExist() {
        val stats = sampleStats().copy(
            totalDrives = 1,
            totalDistanceKm = 0.0,
            totalEnergyConsumedKwh = 0.0,
            avgEfficiencyWhKm = 0.0,
            totalCharges = 1,
            totalEnergyAddedKwh = 0.0,
            totalCost = 0.0
        )

        val summary = buildAnalysisSummary(stats)
        assertEquals(0.0, available(summary.distanceKm).value, 0.0001)
        assertEquals(0.0, available(summary.drivingEnergyKwh).value, 0.0001)
        assertEquals(0.0, available(summary.chargedEnergyKwh).value, 0.0001)
        assertEquals(0.0, available(summary.totalCost).value, 0.0001)

        val conclusions = buildAnalysisConclusions(stats)
        assertEquals(0.0, available(conclusions.averageDriveDistanceKm).value, 0.0001)
        assertEquals(0.0, available(conclusions.averageDistancePerDrivingDayKm).value, 0.0001)
        assertEquals(0.0, available(conclusions.averageChargeEnergyKwh).value, 0.0001)
        assertTrue(conclusions.costPer100Km is MetricState.Unavailable)
        assertTrue(conclusions.chargedToDrivingEnergyPercent is MetricState.Unavailable)
    }

    @Test
    fun emptyRecordSetDoesNotTurnDefaultZerosIntoObservedMetrics() {
        val summary = buildAnalysisSummary(
            sampleStats().copy(
                totalDrives = 0,
                totalDistanceKm = 0.0,
                totalEnergyConsumedKwh = 0.0,
                avgEfficiencyWhKm = 0.0,
                totalCharges = 0,
                totalEnergyAddedKwh = 0.0,
                totalCost = 0.0
            )
        )

        assertTrue(summary.distanceKm is MetricState.Unavailable)
        assertTrue(summary.drivingEnergyKwh is MetricState.Unavailable)
        assertTrue(summary.chargedEnergyKwh is MetricState.Unavailable)
        assertTrue(summary.totalCost is MetricState.Unavailable)
    }

    @Test
    fun coverageHidesRoomPlaceholderZerosFromSummaryMetrics() {
        val stats = sampleStats().copy(
            totalDrives = 1,
            totalDistanceKm = 0.0,
            totalEnergyConsumedKwh = 0.0,
            avgEfficiencyWhKm = 0.0,
            totalCharges = 1,
            totalEnergyAddedKwh = 0.0,
            totalCost = 0.0
        )
        val coverage = AnalysisCoverage(
            driveRecordCount = 1,
            driveDistanceSampleCount = 0,
            driveEnergySampleCount = 0,
            chargeRecordCount = 1,
            chargeEnergySampleCount = 0,
            chargeCostSampleCount = 0,
            firstObservedDate = null,
            lastObservedDate = null
        )

        val summary = buildAnalysisSummary(stats, coverage)
        val conclusions = buildAnalysisConclusions(stats, coverage)

        assertTrue(summary.distanceKm is MetricState.Unavailable)
        assertTrue(summary.drivingEnergyKwh is MetricState.Unavailable)
        assertTrue(summary.efficiencyWhKm is MetricState.Unavailable)
        assertTrue(summary.chargedEnergyKwh is MetricState.Unavailable)
        assertTrue(summary.totalCost is MetricState.Unavailable)
        assertTrue(conclusions.averageDriveDistanceKm is MetricState.Unavailable)
        assertTrue(conclusions.averageDistancePerDrivingDayKm is MetricState.Unavailable)
        assertTrue(conclusions.averageChargeEnergyKwh is MetricState.Unavailable)
    }

    @Suppress("UNCHECKED_CAST")
    private fun available(metric: MetricState<Double>): MetricState.Available<Double> =
        metric as MetricState.Available<Double>

    private fun sampleStats() = QuickStats(
        totalDrives = 4,
        totalDistanceKm = 100.0,
        totalEnergyConsumedKwh = 18.0,
        avgEfficiencyWhKm = 180.0,
        maxSpeedKmh = 120,
        avgDriveMinutes = 30.0,
        totalDrivingDays = 3,
        totalCharges = 2,
        totalEnergyAddedKwh = 25.0,
        totalCost = 20.0,
        avgCostPerKwh = 0.8,
        avgChargeMinutes = 45.0,
        longestDrive = null,
        fastestDrive = null,
        mostEfficientDrive = null,
        leastEfficientDrive = null,
        biggestCharge = null,
        mostExpensiveCharge = null,
        mostExpensivePerKwhCharge = null,
        firstDriveDate = null,
        firstChargeDate = null,
        busiestDay = null,
        mostDistanceDay = null,
        maxDistanceBetweenCharges = null,
        longestGapWithoutCharging = null,
        longestGapWithoutDriving = null,
        longestDrivingStreak = null,
        biggestBatteryGainCharge = null,
        biggestBatteryDrainDrive = null
    )
}

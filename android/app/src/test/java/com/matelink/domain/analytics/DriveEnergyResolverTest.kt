package com.matelink.domain.analytics

import org.junit.Assert.*
import org.junit.Test

class DriveEnergyResolverTest {
    private val start = "2026-07-11T10:00:00Z"
    private val end = "2026-07-11T10:00:10Z"
    private fun rows(power: Double) = listOf(DrivePowerSample(start, power), DrivePowerSample(end, power))
    private fun estimate(api: Double? = null, power: Double = 4.0, distance: Double? = 1.0) =
        DriveEnergyResolver.resolve(api, distance, rows(power), startDate = start, endDate = end)

    @Test fun partialPowerCoverageCannotRepresentWholeDriveConsumption() {
        val result = DriveEnergyResolver.resolve(null, 10.0, rows(10.0), durationSeconds = 600,
            startDate = start, endDate = "2026-07-11T10:10:00Z")
        assertNull(result.energyKwh)
        assertNull(result.efficiencyWhKm)
        assertEquals(10.0 / 600, result.coverageRatio!!, 1e-12)
        assertNotNull(result.observedEnergyKwh)
    }
    @Test fun apiEnergyWinsIncludingZeroAndNegativeNetRecovery() {
        listOf(2.5, 0.0, -1.0).forEach { api ->
            val result = estimate(api)
            assertEquals(api, result.energyKwh!!, 0.0)
            assertEquals(api * 1000, result.efficiencyWhKm!!, 1e-12)
            assertEquals(DriveEnergySource.API, result.source)
            assertNull(result.coverageRatio)
        }
    }
    @Test fun completePowerWindowProvidesAnEstimateWhenApiEnergyIsMissing() {
        val result = estimate()
        assertEquals(40.0 / 3600, result.energyKwh!!, 1e-12)
        assertEquals(DriveEnergySource.POWER_SAMPLES, result.source)
        assertEquals(1.0, result.coverageRatio!!, 1e-12)
    }
    @Test fun unknownDriveBoundariesRemainUnknownEvenWithRoundedDuration() {
        val result = DriveEnergyResolver.resolve(null, 1.0, rows(4.0), durationSeconds = 10)
        assertNull(result.energyKwh)
        assertEquals("missing_window", result.qualityReason)
        assertNotNull(result.observedEnergyKwh)
    }
    @Test fun unavailableEnergyStaysUnknownInsteadOfBecomingZero() {
        val result = DriveEnergyResolver.resolve(null, 1.0, emptyList(), startDate = start, endDate = end)
        assertNull(result.energyKwh)
        assertNull(result.efficiencyWhKm)
        assertEquals(DriveEnergySource.UNAVAILABLE, result.source)
    }
    @Test fun nonFiniteApiEnergyOnlyFallsBackToCompletePowerWindow() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            assertEquals(DriveEnergySource.POWER_SAMPLES, estimate(bad).source)
            assertNull(DriveEnergyResolver.resolve(bad, 1.0, rows(4.0)).energyKwh)
        }
    }
    @Test fun nullOrNonFinitePowerCannotBeInterpolatedOver() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            val result = DriveEnergyResolver.resolve(null, 1.0, rows(4.0) + DrivePowerSample("2026-07-11T10:00:20Z", bad),
                startDate = start, endDate = "2026-07-11T10:00:20Z")
            assertNull(result.energyKwh)
            assertEquals(10L, result.coverageSeconds)
            assertEquals(0.5, result.coverageRatio!!, 1e-12)
        }
    }
    @Test fun continuousTrapezoidsAccumulateTheWholeWindow() {
        val result = DriveEnergyResolver.resolve(null, 1.0, listOf(DrivePowerSample(start, 2.0),
            DrivePowerSample(end, 4.0), DrivePowerSample("2026-07-11T10:00:20Z", 6.0)),
            startDate = start, endDate = "2026-07-11T10:00:20Z")
        assertEquals(80.0 / 3600, result.energyKwh!!, 1e-12)
        assertEquals(20L, result.coverageSeconds)
    }
    @Test fun zeroAndNegativePowerIntegralsRemainAvailableEstimates() {
        listOf(0.0, -4.0).forEach { p ->
            val result = estimate(power = p)
            assertEquals(p * 10 / 3600, result.energyKwh!!, 1e-12)
            assertEquals(DriveEnergySource.POWER_SAMPLES, result.source)
        }
    }
    @Test fun invalidDistanceDoesNotProduceEfficiency() {
        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { distance ->
            val result = estimate(api = 1.0, distance = distance)
            assertEquals(1.0, result.energyKwh!!, 0.0)
            assertNull(result.efficiencyWhKm)
        }
    }
    @Test fun longSampleGapsDoNotBecomeThirtySecondsOfEnergy() {
        val result = DriveEnergyResolver.resolve(null, 1.0, listOf(DrivePowerSample(start, 6.0),
            DrivePowerSample("2026-07-11T10:01:00Z", 6.0)), startDate = start, endDate = "2026-07-11T10:01:00Z")
        assertNull(result.energyKwh)
        assertEquals(0L, result.coverageSeconds)
    }
    @Test fun unorderedReplayedSamplesHaveTheSameResultWithoutExtraCoverage() {
        val a = estimate()
        val b = DriveEnergyResolver.resolve(null, 1.0, rows(4.0).reversed() + rows(4.0), startDate = start, endDate = end)
        assertEquals(a, b)
    }
    @Test fun fractionalCoverageMustNotBeRoundedIntoMissingTime() {
        val finish = "2026-07-11T10:00:00.5Z"
        val result = DriveEnergyResolver.resolve(null, 1.0, listOf(DrivePowerSample(start, 36.0), DrivePowerSample(finish, 36.0)), startDate = start, endDate = finish)
        assertEquals(0.005, result.energyKwh!!, 1e-12)
        assertEquals(0L, result.coverageSeconds)
        assertEquals(0.5, result.coverageSecondsExact, 1e-12)
        assertEquals(1.0, result.coverageRatio!!, 1e-12)
    }
    @Test fun reportUnitsAreWhPerKmNotKwhPer100Km() {
        val result = estimate(api = 2.0, distance = 10.0)
        assertEquals(200.0, result.efficiencyWhKm!!, 1e-12)
        assertEquals(20.0, result.efficiencyWhKm!! / 10.0, 1e-12)
    }
}

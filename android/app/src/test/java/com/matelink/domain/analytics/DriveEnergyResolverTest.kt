package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveEnergyResolverTest {

    @Test
    fun apiEnergyWinsWhenTheServerProvidesIt() {
        val estimate = DriveEnergyResolver.resolve(
            apiEnergyKwh = 2.5,
            distanceKm = 10.0,
            samples = listOf(
                DrivePowerSample("2026-07-11T10:00:00Z", 10.0),
                DrivePowerSample("2026-07-11T10:00:10Z", 10.0)
            )
        )

        assertEquals(2.5, estimate.energyKwh!!, 0.0001)
        assertEquals(250.0, estimate.efficiencyWhKm!!, 0.0001)
        assertEquals(DriveEnergySource.API, estimate.source)
    }

    @Test
    fun powerSamplesProvideAnEstimateWhenApiEnergyIsMissing() {
        val estimate = DriveEnergyResolver.resolve(
            apiEnergyKwh = null,
            distanceKm = 1.0,
            samples = listOf(
                DrivePowerSample("2026-07-11T10:00:00Z", 4.0),
                DrivePowerSample("2026-07-11T10:00:10Z", 4.0)
            )
        )

        assertEquals(4.0 * 10.0 / 3600.0, estimate.energyKwh!!, 0.0001)
        assertEquals(4.0 * 10.0 * 1000.0 / 3600.0, estimate.efficiencyWhKm!!, 0.0001)
        assertEquals(DriveEnergySource.POWER_SAMPLES, estimate.source)
        assertEquals(10L, estimate.coverageSeconds)
    }

    @Test
    fun unavailableEnergyStaysUnknownInsteadOfBecomingZero() {
        val estimate = DriveEnergyResolver.resolve(
            apiEnergyKwh = null,
            distanceKm = 1.0,
            samples = emptyList()
        )

        assertNull(estimate.energyKwh)
        assertNull(estimate.efficiencyWhKm)
        assertEquals(DriveEnergySource.UNAVAILABLE, estimate.source)
    }

    @Test
    fun negativeApiEnergyFallsBackToValidPowerSamples() {
        val estimate = DriveEnergyResolver.resolve(
            apiEnergyKwh = -1.0,
            distanceKm = 1.0,
            samples = constantPowerSamples(powerKw = 4.0, endSeconds = 10)
        )

        assertEquals(4.0 * 10.0 / 3600.0, estimate.energyKwh!!, 0.0000001)
        assertEquals(DriveEnergySource.POWER_SAMPLES, estimate.source)
        assertEquals(10L, estimate.coverageSeconds)
        assertFinite(estimate)
    }

    @Test
    fun nonFiniteApiEnergyFallsBackToValidPowerSamples() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { apiEnergy ->
            val estimate = DriveEnergyResolver.resolve(
                apiEnergyKwh = apiEnergy,
                distanceKm = 1.0,
                samples = constantPowerSamples(powerKw = 4.0, endSeconds = 10)
            )

            assertEquals(4.0 * 10.0 / 3600.0, estimate.energyKwh!!, 0.0000001)
            assertEquals(DriveEnergySource.POWER_SAMPLES, estimate.source)
            assertEquals(10L, estimate.coverageSeconds)
            assertFinite(estimate)
        }
    }

    @Test
    fun partialSamplesKeepOnlyContiguousValidCoverage() {
        val estimate = DriveEnergyResolver.resolve(
            apiEnergyKwh = null,
            distanceKm = 1.0,
            samples = listOf(
                DrivePowerSample("2026-07-11T10:00:00Z", 4.0),
                DrivePowerSample("2026-07-11T10:00:10Z", 4.0),
                DrivePowerSample("2026-07-11T10:00:20Z", null),
                DrivePowerSample("2026-07-11T10:00:30Z", 4.0)
            )
        )

        assertEquals(4.0 * 10.0 / 3600.0, estimate.energyKwh!!, 0.0000001)
        assertEquals(DriveEnergySource.POWER_SAMPLES, estimate.source)
        assertEquals(10L, estimate.coverageSeconds)
        assertFinite(estimate)
    }

    @Test
    fun continuousValidSamplesAccumulateCompleteReachableCoverage() {
        val estimate = DriveEnergyResolver.resolve(
            apiEnergyKwh = null,
            distanceKm = 1.0,
            samples = listOf(
                DrivePowerSample("2026-07-11T10:00:00Z", 2.0),
                DrivePowerSample("2026-07-11T10:00:10Z", 4.0),
                DrivePowerSample("2026-07-11T10:00:20Z", 6.0)
            )
        )

        assertEquals(80.0 / 3600.0, estimate.energyKwh!!, 0.0000001)
        assertEquals(DriveEnergySource.POWER_SAMPLES, estimate.source)
        assertEquals(20L, estimate.coverageSeconds)
        assertFinite(estimate)
    }

    @Test
    fun nonFiniteSamplePowerDoesNotInflateCoverage() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalidPower ->
            val estimate = DriveEnergyResolver.resolve(
                apiEnergyKwh = null,
                distanceKm = 1.0,
                samples = listOf(
                    DrivePowerSample("2026-07-11T10:00:00Z", 4.0),
                    DrivePowerSample("2026-07-11T10:00:10Z", 4.0),
                    DrivePowerSample("2026-07-11T10:00:20Z", invalidPower)
                )
            )

            assertEquals(4.0 * 10.0 / 3600.0, estimate.energyKwh!!, 0.0000001)
            assertEquals(DriveEnergySource.POWER_SAMPLES, estimate.source)
            assertEquals(10L, estimate.coverageSeconds)
            assertFinite(estimate)
        }
    }

    @Test
    fun nonPositiveSampleEnergyDoesNotBecomeNegativeOrAvailable() {
        val estimate = DriveEnergyResolver.resolve(
            apiEnergyKwh = null,
            distanceKm = 1.0,
            samples = constantPowerSamples(powerKw = -4.0, endSeconds = 10)
        )

        assertNull(estimate.energyKwh)
        assertNull(estimate.efficiencyWhKm)
        assertEquals(DriveEnergySource.UNAVAILABLE, estimate.source)
        assertEquals(0L, estimate.coverageSeconds)
    }

    @Test
    fun invalidDistanceDoesNotProduceNonFiniteEfficiency() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { distance ->
            val estimate = DriveEnergyResolver.resolve(
                apiEnergyKwh = 1.0,
                distanceKm = distance,
                samples = emptyList()
            )

            assertEquals(1.0, estimate.energyKwh!!, 0.0000001)
            assertNull(estimate.efficiencyWhKm)
            assertEquals(DriveEnergySource.API, estimate.source)
            assertEquals(0L, estimate.coverageSeconds)
        }
    }

    @Test
    fun longSampleIntervalsAreCappedAtThirtySeconds() {
        val estimate = DriveEnergyResolver.resolve(
            apiEnergyKwh = null,
            distanceKm = 1.0,
            samples = listOf(
                DrivePowerSample("2026-07-11T10:00:00Z", 6.0),
                DrivePowerSample("2026-07-11T10:01:00Z", 6.0)
            )
        )

        assertEquals(6.0 * 30.0 / 3600.0, estimate.energyKwh!!, 0.0000001)
        assertEquals(DriveEnergySource.POWER_SAMPLES, estimate.source)
        assertEquals(30L, estimate.coverageSeconds)
        assertFinite(estimate)
    }

    @Test
    fun reversedTimestampsDoNotCreateNegativeCoverage() {
        val estimate = DriveEnergyResolver.resolve(
            apiEnergyKwh = null,
            distanceKm = 1.0,
            samples = listOf(
                DrivePowerSample("2026-07-11T10:00:10Z", 4.0),
                DrivePowerSample("2026-07-11T10:00:00Z", 4.0)
            )
        )

        assertNull(estimate.energyKwh)
        assertNull(estimate.efficiencyWhKm)
        assertEquals(DriveEnergySource.UNAVAILABLE, estimate.source)
        assertEquals(0L, estimate.coverageSeconds)
    }

    @Test
    fun resolveIsDeterministicForTheSameSyntheticInput() {
        val samples = listOf(
            DrivePowerSample("2026-07-11T10:00:00Z", 3.0),
            DrivePowerSample("2026-07-11T10:00:10Z", 5.0),
            DrivePowerSample("2026-07-11T10:00:20Z", 7.0)
        )

        val first = DriveEnergyResolver.resolve(null, 2.0, samples)
        val second = DriveEnergyResolver.resolve(null, 2.0, samples)

        assertEquals(first, second)
        assertFinite(first)
    }

    private fun constantPowerSamples(powerKw: Double, endSeconds: Int): List<DrivePowerSample> = listOf(
        DrivePowerSample("2026-07-11T10:00:00Z", powerKw),
        DrivePowerSample("2026-07-11T10:00:${endSeconds.toString().padStart(2, '0')}Z", powerKw)
    )

    private fun assertFinite(estimate: DriveEnergyEstimate) {
        estimate.energyKwh?.let { assertTrue(it.isFinite()) }
        estimate.efficiencyWhKm?.let { assertTrue(it.isFinite()) }
    }
}

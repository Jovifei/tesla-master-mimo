package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent numeric examples; never calls the calculator to produce expected values. */
class EnergyContractIndependentRegressionTest {
    @Test fun reportedZeroMustKeepItsSource() {
        val value = DriveEnergyResolver.resolve(0.0, 10.0, emptyList(), 30L)
        assertEquals(DriveEnergySource.API, value.source)
        assertEquals(0.0, value.energyKwh!!, 0.000001)
    }

    @Test fun reportedNetRecoveryIsNotMissing() {
        val value = DriveEnergyResolver.resolve(-0.5, 10.0, emptyList(), 30L)
        assertEquals(DriveEnergySource.API, value.source)
        assertEquals(-50.0, value.efficiencyWhKm!!, 0.000001)
    }

    @Test fun zeroEnergyDriveStillCountsInWeightedDistance() {
        val value = calculateWeightedEfficiency(listOf(EfficiencySample(1.0, 1.0), EfficiencySample(9.0, 0.0)))
        assertEquals(100.0, value.efficiencyWhKm!!, 0.000001)
        assertEquals(10.0, value.validDistanceKm, 0.000001)
    }

    @Test fun overlappingRepeatedIntervalsCannotCreateCoverage() {
        val value = DriveEnergyCalculator.calculate(listOf(
            DrivePowerSample("2026-10-08T00:00:00Z", 10.0),
            DrivePowerSample("2026-10-08T00:00:30Z", 10.0),
            DrivePowerSample("2026-10-08T00:00:00Z", 10.0),
            DrivePowerSample("2026-10-08T00:00:30Z", 10.0)))
        assertTrue(value.coverageSeconds <= 30L)
        assertTrue(value.energyKwh == null || value.energyKwh!! <= 1.0 / 12.0 + 0.000001)
    }

    @Test fun fiveMinuteGapIsNotAnObservedThirtySecondInterval() {
        val value = DriveEnergyCalculator.calculate(listOf(
            DrivePowerSample("2026-10-08T00:00:00Z", 10.0),
            DrivePowerSample("2026-10-08T00:05:00Z", 10.0)))
        assertEquals(0L, value.coverageSeconds)
        assertNull(value.energyKwh)
    }

    @Test fun finiteInputsCannotPublishAnInfiniteEnergy() {
        val value = DriveEnergyCalculator.calculate(listOf(
            DrivePowerSample("2026-10-08T00:00:00Z", Double.MAX_VALUE),
            DrivePowerSample("2026-10-08T00:00:30Z", Double.MAX_VALUE)))
        assertTrue(value.energyKwh == null || value.energyKwh!!.isFinite())
    }
}

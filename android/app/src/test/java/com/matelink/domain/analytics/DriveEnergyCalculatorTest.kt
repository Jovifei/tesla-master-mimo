package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveEnergyCalculatorTest {

    @Test
    fun adjacentValidSamples_integratePowerWithTrapezoidRule() {
        val result = DriveEnergyCalculator.calculate(
            listOf(
                DrivePowerSample("2026-07-11T00:00:00Z", 4.0),
                DrivePowerSample("2026-07-11T00:00:10Z", 8.0)
            )
        )

        assertEquals(10L, result.coverageSeconds)
        assertEquals(6.0 * 10.0 / 3600.0, result.energyKwh!!, 0.0000001)
    }

    @Test
    fun longInterval_isCappedAtThirtySeconds() {
        val result = DriveEnergyCalculator.calculate(
            listOf(
                DrivePowerSample("2026-07-11T00:00:00Z", 6.0),
                DrivePowerSample("2026-07-11T00:01:00Z", 6.0)
            )
        )

        assertEquals(30L, result.coverageSeconds)
        assertEquals(6.0 * 30.0 / 3600.0, result.energyKwh!!, 0.0000001)
    }

    @Test
    fun invalidTimestampOrPower_skipsAffectedIntervals() {
        val result = DriveEnergyCalculator.calculate(
            listOf(
                DrivePowerSample("2026-07-11T00:00:00Z", 4.0),
                DrivePowerSample("not-a-timestamp", 6.0),
                DrivePowerSample("2026-07-11T00:00:20Z", Double.NaN),
                DrivePowerSample("2026-07-11T00:00:30Z", 8.0)
            )
        )

        assertEquals(0L, result.coverageSeconds)
        assertNull(result.energyKwh)
    }

    @Test
    fun nonPositiveNetConsumption_returnsNullEnergy() {
        val result = DriveEnergyCalculator.calculate(
            listOf(
                DrivePowerSample("2026-07-11T00:00:00Z", -2.0),
                DrivePowerSample("2026-07-11T00:00:10Z", -2.0)
            )
        )

        assertEquals(10L, result.coverageSeconds)
        assertNull(result.energyKwh)
    }
}

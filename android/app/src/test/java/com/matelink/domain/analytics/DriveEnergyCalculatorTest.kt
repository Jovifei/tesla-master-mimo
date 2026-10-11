package com.matelink.domain.analytics

import org.junit.Assert.*
import org.junit.Test

class DriveEnergyCalculatorTest {
    private val start = "2026-07-11T10:00:00Z"
    private fun sample(second: Int, power: Double?) = DrivePowerSample("2026-07-11T10:00:${second.toString().padStart(2, '0')}Z", power)

    @Test fun subSecondIntervalsAccumulateWithoutTruncation() {
        val result = DriveEnergyCalculator.calculate(listOf(DrivePowerSample(start, 36.0),
            DrivePowerSample("2026-07-11T10:00:00.5Z", 36.0), sample(1, 36.0)), start, "2026-07-11T10:00:01Z")
        assertEquals(0.01, result.energyKwh!!, 1e-12)
        assertEquals(1.0, result.coverageSecondsExact, 1e-12)
        assertTrue(result.complete)
    }
    @Test fun subMillisecondIntervalsAlsoRemainObservedTime() {
        val end = "2026-07-11T10:00:00.0005Z"
        val result = DriveEnergyCalculator.calculate(listOf(DrivePowerSample(start, 36.0), DrivePowerSample(end, 36.0)), start, end)
        assertEquals(0.000005, result.energyKwh!!, 1e-12)
        assertEquals(0.0005, result.coverageSecondsExact, 1e-12)
        assertTrue(result.complete)
    }
    @Test fun trapezoidalIntegrationUsesBothEndpoints() {
        val result = DriveEnergyCalculator.calculate(listOf(sample(0, 4.0), sample(10, 8.0)))
        assertEquals(6.0 * 10 / 3600, result.energyKwh!!, 1e-12)
        assertEquals(10L, result.coverageSeconds)
        assertFalse(result.complete)
    }
    @Test fun longGapIsMissingNotACappedThirtySecondMeasurement() {
        val result = DriveEnergyCalculator.calculate(listOf(sample(0, 6.0), DrivePowerSample("2026-07-11T10:01:00Z", 6.0)), start, "2026-07-11T10:01:00Z")
        assertNull(result.energyKwh)
        assertEquals(0L, result.coverageSeconds)
        assertFalse(result.complete)
    }
    @Test fun clippingCannotTurnALongGapIntoValidCoverage() {
        val result = DriveEnergyCalculator.calculate(listOf(sample(0, 6.0), DrivePowerSample("2026-07-11T10:01:00Z", 6.0)), start, "2026-07-11T10:00:10Z")
        assertNull(result.energyKwh)
    }
    @Test fun identicalReplaysAndArrivalOrderDoNotChangeTheIntegral() {
        val rows = listOf(sample(0, 4.0), sample(10, 8.0), sample(20, 0.0))
        val a = DriveEnergyCalculator.calculate(rows, start, "2026-07-11T10:00:20Z")
        val b = DriveEnergyCalculator.calculate(listOf(rows[2], rows[1], rows[0], rows[1], rows[2]), start, "2026-07-11T10:00:20Z")
        assertEquals(a, b)
        assertEquals(20.0, b.coverageSecondsExact, 1e-12)
    }
    @Test fun conflictingDuplicateAndMissingPowerAreBarriers() {
        listOf(null, 3.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { bad ->
            val result = DriveEnergyCalculator.calculate(listOf(sample(0, 4.0), sample(10, 4.0), sample(10, bad), sample(20, 4.0)), start, "2026-07-11T10:00:20Z")
            assertNull(result.energyKwh)
            assertFalse(result.complete)
        }
    }
    @Test fun invalidTimestampCannotMakeAWindowComplete() {
        val result = DriveEnergyCalculator.calculate(listOf(sample(0, 4.0), DrivePowerSample("invalid", 4.0), sample(10, 4.0)), start, "2026-07-11T10:00:10Z")
        assertFalse(result.complete)
        assertEquals("invalid_sample_timestamp", result.qualityReason)
    }
    @Test fun validZeroAndNetRecoveryRemainSigned() {
        listOf(0.0, -2.0).forEach { power ->
            val result = DriveEnergyCalculator.calculate(listOf(sample(0, power), sample(10, power)), start, "2026-07-11T10:00:10Z")
            assertEquals(power * 10 / 3600, result.energyKwh!!, 1e-12)
            assertTrue(result.complete)
        }
    }
    @Test fun onlyTheRequestedWindowIsIntegrated() {
        val result = DriveEnergyCalculator.calculate(listOf(sample(0, 0.0), sample(20, 20.0)), "2026-07-11T10:00:05Z", "2026-07-11T10:00:15Z")
        assertEquals(10.0 * 10 / 3600, result.energyKwh!!, 1e-12)
        assertEquals(10.0, result.coverageSecondsExact, 1e-12)
        assertTrue(result.complete)
    }
    @Test fun missingEndpointIsNotExtrapolated() {
        val result = DriveEnergyCalculator.calculate(listOf(sample(5, 4.0), sample(10, 4.0)), start, "2026-07-11T10:00:10Z")
        assertEquals(5.0, result.coverageSecondsExact, 1e-12)
        assertFalse(result.complete)
    }
    @Test fun invalidAndZeroDurationWindowsAreUnknown() {
        listOf(start, "invalid", "2026-07-11T09:59:59Z").forEach { end ->
            val result = DriveEnergyCalculator.calculate(listOf(sample(0, 4.0), sample(10, 4.0)), start, end)
            assertNull(result.energyKwh)
            assertEquals("invalid_window", result.qualityReason)
        }
    }
    @Test fun finiteLargeOppositePowersDoNotOverflowWhenTheyCancel() {
        val result = DriveEnergyCalculator.calculate(listOf(sample(0, Double.MAX_VALUE), sample(10, -Double.MAX_VALUE)), start, "2026-07-11T10:00:10Z")
        assertEquals(0.0, result.energyKwh!!, 0.0)
        assertTrue(result.complete)
    }
}

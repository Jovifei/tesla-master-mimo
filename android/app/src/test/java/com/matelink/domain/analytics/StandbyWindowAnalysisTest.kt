package com.matelink.domain.analytics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandbyWindowAnalysisTest {
    @Test
    fun chargingOverlapIsExcludedFromStandby() {
        val result = qualifyStandbyWindow(
            StandbyWindowInput(
                date = LocalDate.of(2026, 8, 20),
                durationHours = 4.0,
                batteryDeltaPercent = -2,
                chargingOverlap = true,
                coveragePercent = 100.0,
                energyKwh = 1.2,
                averagePowerW = 300.0
            )
        )

        assertFalse(result.isEligible)
        assertEquals(StandbyExclusion.CHARGING_OVERLAP, result.exclusion)
    }

    @Test
    fun lowCoverageKeepsObservedSocButWithholdsEnergyAndPower() {
        val result = qualifyStandbyWindow(
            StandbyWindowInput(
                date = LocalDate.of(2026, 8, 20),
                durationHours = 3.0,
                batteryDeltaPercent = -1,
                coveragePercent = 45.0,
                energyKwh = 0.8,
                averagePowerW = 270.0
            )
        )

        assertTrue(result.isEligible)
        assertEquals(-1, result.socDeltaPercent)
        assertNull(result.energyKwh)
        assertNull(result.averagePowerW)
    }

    @Test
    fun stableConclusionNeedsFiveWindowsAndTwentyHoursInSelectedRange() {
        val windows = (0 until 5).map { offset ->
            qualifyStandbyWindow(
                StandbyWindowInput(
                    date = LocalDate.of(2026, 8, 23).minusDays(offset.toLong()),
                    durationHours = 4.0,
                    batteryDeltaPercent = -1,
                    coveragePercent = 90.0,
                    energyKwh = 0.7,
                    averagePowerW = 175.0
                )
            )
        }

        val summary = summarizeStandbyWindows(
            windows = windows,
            range = StandbyRange.LAST_7_DAYS,
            asOf = LocalDate.of(2026, 8, 23)
        )

        assertTrue(summary.hasStableConclusion)
        assertEquals(5, summary.eligibleWindowCount)
        assertEquals(20.0, summary.qualifiedHours, 0.0)
    }

    private fun powerWindow(hours: Double, powerW: Double?, coveragePercent: Double = 100.0) =
        qualifyStandbyWindow(StandbyWindowInput(
            date = LocalDate.of(2026, 8, 23), durationHours = hours, batteryDeltaPercent = -1,
            coveragePercent = coveragePercent, averagePowerW = powerW,
            energyKwh = powerW?.let { it * hours / 1000.0 }
        ))

    private fun summarize(windows: List<QualifiedStandbyWindow>) = summarizeStandbyWindows(
        windows, StandbyRange.ALL_TIME, LocalDate.of(2026, 8, 23)
    )

    @Test fun unequalWindowDurationsUseTimeWeightedKnownPower() {
        val summary = summarize(listOf(powerWindow(2.0, 400.0), powerWindow(20.0, 100.0)))
        assertEquals(22.0, summary.qualifiedHours, 0.0)
        assertEquals(2.8, summary.totalEnergyKwh ?: Double.NaN, 1e-12)
        assertEquals(2800.0 / 22.0, summary.averagePowerW ?: Double.NaN, 1e-9)
    }

    @Test fun trueZeroPowerKeepsItsDurationWeightAndUnknownPowerDoesNotDiluteIt() {
        val summary = summarize(listOf(
            powerWindow(2.0, 400.0), powerWindow(6.0, 0.0), powerWindow(20.0, null),
            powerWindow(10.0, 900.0, coveragePercent = 45.0)
        ))
        assertEquals(38.0, summary.qualifiedHours, 0.0)
        assertEquals(0.8, summary.totalEnergyKwh ?: Double.NaN, 1e-12)
        // Only the 2h known positive and 6h observed zero belong in the power denominator.
        assertEquals(100.0, summary.averagePowerW ?: Double.NaN, 1e-9)
    }

    @Test fun allUnknownPowerRemainsUnknownWithEligibleSocWindows() {
        val summary = summarize(listOf(powerWindow(2.0, null), powerWindow(20.0, null)))
        assertEquals(2, summary.eligibleWindowCount)
        assertEquals(22.0, summary.qualifiedHours, 0.0)
        assertNull(summary.totalEnergyKwh)
        assertNull(summary.averagePowerW)
    }
}

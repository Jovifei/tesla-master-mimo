package com.matelink.ui.screens.temperature

import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TemperaturePairEvidenceRegressionTest {
    private val now = Instant.parse("2026-10-27T00:00:00Z")
    private fun gain(state: TemperatureTrendUiState): Double? = state.maxPairedDifferenceC

    @Test fun independentExtremaCannotCreateAPairedTemperatureDifference() = runTest {
        var state = TemperatureTrendUiState()
        val source = object : TemperatureTrendSource {
            override suspend fun load(carId: Int, window: TemperatureTrendWindow, now: Instant) =
                TemperatureTrendSnapshot(listOf(TemperaturePoint(1, 50.0, null), TemperaturePoint(2, null, 20.0)))
        }
        TemperatureTrendRefreshController(source, this, { now }) { state = it }.refresh(1, TemperatureTrendWindow.SEVEN)
        runCurrent()
        assertEquals(50.0, state.maxInsideTemp!!, 0.0)
        assertEquals(20.0, state.maxOutsideTemp!!, 0.0)
        assertNull(gain(state))
    }

    @Test fun pairedDifferenceUsesEachSameObservationAndRetainsZeroAndNegativeValues() = runTest {
        for ((inside, outside) in listOf(25.0 to 20.0, 0.0 to 0.0, -5.0 to -2.0)) {
            var state = TemperatureTrendUiState()
            val source = object : TemperatureTrendSource {
                override suspend fun load(carId: Int, window: TemperatureTrendWindow, now: Instant) =
                    TemperatureTrendSnapshot(listOf(TemperaturePoint(1, inside, outside)))
            }
            TemperatureTrendRefreshController(source, this, { now }) { state = it }.refresh(1, TemperatureTrendWindow.SEVEN)
            runCurrent()
            assertEquals(inside - outside, gain(state) ?: Double.NaN, 0.0)
        }
    }

    @Test fun pairedMaximumDoesNotBorrowAColderUnpairedOutdoorExtreme() = runTest {
        var state = TemperatureTrendUiState()
        val source = object : TemperatureTrendSource {
            override suspend fun load(carId: Int, window: TemperatureTrendWindow, now: Instant) =
                TemperatureTrendSnapshot(listOf(TemperaturePoint(1, 30.0, 25.0),
                    TemperaturePoint(2, 50.0, 48.0), TemperaturePoint(3, null, -20.0)))
        }
        TemperatureTrendRefreshController(source, this, { now }) { state = it }.refresh(1, TemperatureTrendWindow.SEVEN)
        runCurrent()
        assertEquals(5.0, gain(state)!!, 0.0)
    }
}

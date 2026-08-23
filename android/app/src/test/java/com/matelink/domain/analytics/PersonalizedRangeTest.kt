package com.matelink.domain.analytics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalizedRangeTest {
    private val asOf = LocalDate.of(2026, 8, 21)

    @Test
    fun usesTemperatureAndSpeedCohortWhenBothGatesPass() {
        val samples = (0 until 5).map { index ->
            PersonalizedRangeSample(
                date = asOf.minusDays(index.toLong()),
                distanceKm = 20.0,
                energyKwh = 4.0,
                speedKmh = 100.0,
                temperatureC = 5.0
            )
        } + PersonalizedRangeSample(
            date = asOf.minusDays(1),
            distanceKm = 300.0,
            energyKwh = 30.0,
            speedKmh = 60.0,
            temperatureC = 20.0
        )

        val result = estimatePersonalizedRange(
            samples = samples,
            usableEnergyKwh = 50.0,
            currentTemperatureC = 5.0,
            currentSpeedKmh = 100.0,
            asOf = asOf
        )

        assertEquals(PersonalizedRangeSource.GROUPED, result.source)
        assertEquals(200.0, result.efficiencyWhKm!!, 0.0001)
        assertEquals(250.0, result.rangeKm!!, 0.0001)
        assertEquals(RangeTemperatureBand.COLD, result.temperatureBand)
        assertEquals(RangeSpeedBand.HIGH, result.speedBand)
        assertEquals(5, result.sampleCount)
    }

    @Test
    fun fallsBackToGlobalModelWhenCohortIsTooSmall() {
        val samples = (0 until 10).map { index ->
            PersonalizedRangeSample(
                date = asOf.minusDays(index.toLong()),
                distanceKm = 30.0,
                energyKwh = 6.0,
                speedKmh = if (index == 0) 100.0 else 60.0,
                temperatureC = if (index == 0) 5.0 else 20.0
            )
        }

        val result = estimatePersonalizedRange(
            samples = samples,
            usableEnergyKwh = 60.0,
            currentTemperatureC = 5.0,
            currentSpeedKmh = 100.0,
            asOf = asOf
        )

        assertEquals(PersonalizedRangeSource.GLOBAL, result.source)
        assertEquals(200.0, result.efficiencyWhKm!!, 0.0001)
        assertEquals(300.0, result.rangeKm!!, 0.0001)
        assertEquals(10, result.sampleCount)
    }

    @Test
    fun doesNotInventRangeWhenCapacityOrSamplesAreUnavailable() {
        val samples = listOf(
            PersonalizedRangeSample(
                date = asOf,
                distanceKm = 100.0,
                energyKwh = 20.0,
                speedKmh = 60.0,
                temperatureC = 20.0
            )
        )

        val result = estimatePersonalizedRange(
            samples = samples,
            usableEnergyKwh = null,
            currentTemperatureC = 20.0,
            currentSpeedKmh = 60.0,
            asOf = asOf
        )

        assertEquals(PersonalizedRangeSource.UNAVAILABLE, result.source)
        assertNull(result.efficiencyWhKm)
        assertNull(result.rangeKm)
        assertEquals(0, result.sampleCount)
    }

    @Test
    fun ignoresOutOfWindowAndInvalidSamples() {
        val samples = listOf(
            PersonalizedRangeSample(
                date = asOf.minusDays(91),
                distanceKm = 1000.0,
                energyKwh = 100.0,
                speedKmh = 60.0,
                temperatureC = 20.0
            ),
            PersonalizedRangeSample(
                date = asOf,
                distanceKm = Double.NaN,
                energyKwh = 1.0,
                speedKmh = 60.0,
                temperatureC = 20.0
            )
        )

        val result = estimatePersonalizedRange(
            samples = samples,
            usableEnergyKwh = 50.0,
            currentTemperatureC = 20.0,
            currentSpeedKmh = 60.0,
            asOf = asOf
        )

        assertEquals(PersonalizedRangeSource.UNAVAILABLE, result.source)
        assertEquals(0, result.sampleCount)
        assertNull(result.rangeKm)
    }

    @Test
    fun temperatureAndSpeedBoundariesUseTheMiddleBand() {
        assertEquals(RangeTemperatureBand.MILD, 10.0.temperatureBand())
        assertEquals(RangeTemperatureBand.MILD, 25.0.temperatureBand())
        assertEquals(RangeSpeedBand.CRUISE, 50.0.speedBand())
        assertEquals(RangeSpeedBand.CRUISE, 90.0.speedBand())
    }
}

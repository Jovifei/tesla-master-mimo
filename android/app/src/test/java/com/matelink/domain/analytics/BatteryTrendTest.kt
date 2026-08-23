package com.matelink.domain.analytics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryTrendTest {

    @Test
    fun normalizesRatedRangeAndComparesSeparateBaselineAndRecentWindows() {
        val baseline = listOf(
            sample("2026-01-01", soc = 80.0, range = 240.0),
            sample("2026-01-05", soc = 80.0, range = 240.0),
            sample("2026-01-10", soc = 80.0, range = 240.0),
            sample("2026-01-15", soc = 80.0, range = 240.0),
            sample("2026-01-20", soc = 80.0, range = 240.0)
        )
        val recent = listOf(
            sample("2026-03-01", soc = 80.0, range = 228.0),
            sample("2026-03-05", soc = 80.0, range = 228.0),
            sample("2026-03-10", soc = 80.0, range = 228.0),
            sample("2026-03-15", soc = 80.0, range = 228.0),
            sample("2026-03-20", soc = 80.0, range = 228.0)
        )

        val result = estimateBatteryTrend(baseline + recent)

        assertEquals(BatteryTrendSource.TREND_ESTIMATE, result.source)
        assertEquals(300.0, result.baselineRangeKm ?: error("baseline missing"), 0.0001)
        assertEquals(285.0, result.normalizedCurrentRangeKm ?: error("current missing"), 0.0001)
        assertEquals(5.0, result.degradationPercent ?: error("degradation missing"), 0.0001)
        assertEquals(10, result.sampleCount)
        assertEquals(78L, result.coverageDays)
        assertEquals(LocalDate.parse("2026-01-01"), result.firstDate)
        assertEquals(LocalDate.parse("2026-03-20"), result.lastDate)
        assertEquals(77, result.confidencePercent)
    }

    @Test
    fun keepsTrendOnlyWhenThereIsNoSeparateEarlyBaseline() {
        val offsets = listOf(0L, 3L, 6L, 9L, 12L, 15L, 18L, 21L, 25L, 30L)
        val samples = offsets.map { offset ->
            sample(
                date = LocalDate.of(2026, 1, 31).plusDays(offset),
                soc = 90.0,
                range = 270.0
            )
        }

        val result = estimateBatteryTrend(samples)

        assertEquals(BatteryTrendSource.TREND_ONLY, result.source)
        assertEquals(300.0, result.normalizedCurrentRangeKm ?: error("current missing"), 0.0001)
        assertNull(result.baselineRangeKm)
        assertNull(result.degradationPercent)
        assertEquals(30L, result.coverageDays)
    }

    @Test
    fun requiresTenValidSamplesAndThirtyDaysOfCoverage() {
        val tooFew = (0 until 9).map { index ->
            sample(LocalDate.of(2026, 1, 1).plusDays(index.toLong() * 4), 80.0, 240.0)
        }
        val shortCoverage = (0 until 10).map { index ->
            sample(LocalDate.of(2026, 1, 1).plusDays(index.toLong() * 2), 80.0, 240.0)
        }

        val tooFewResult = estimateBatteryTrend(tooFew)
        val shortCoverageResult = estimateBatteryTrend(shortCoverage)

        assertEquals(BatteryTrendSource.UNAVAILABLE, tooFewResult.source)
        assertEquals(9, tooFewResult.sampleCount)
        assertEquals(BatteryTrendSource.UNAVAILABLE, shortCoverageResult.source)
        assertEquals(18L, shortCoverageResult.coverageDays)
    }

    @Test
    fun rejectsMissingOutOfRangeAndNonFiniteObservations() {
        val samples = listOf(
            sample("2026-01-01", soc = 69.0, range = 240.0),
            sample("2026-01-02", soc = 80.0, range = 240.0, temperature = 14.9),
            sample("2026-01-03", soc = 80.0, range = Double.NaN),
            sample("2026-01-04", soc = 80.0, range = 240.0, temperature = null),
            sample("2026-01-05", soc = 80.0, range = 240.0)
        )

        val result = estimateBatteryTrend(samples)

        assertEquals(BatteryTrendSource.UNAVAILABLE, result.source)
        assertEquals(1, result.sampleCount)
        assertEquals(LocalDate.parse("2026-01-05"), result.firstDate)
        assertEquals(LocalDate.parse("2026-01-05"), result.lastDate)
    }

    @Test
    fun acceptsInclusiveSocAndTemperatureBoundaries() {
        val samples = (0 until 10).map { index ->
            sample(
                date = LocalDate.of(2026, 1, 1).plusDays(index.toLong() * 4),
                soc = if (index % 2 == 0) 70.0 else 100.0,
                range = 200.0,
                temperature = if (index % 2 == 0) 15.0 else 30.0
            )
        }

        val result = estimateBatteryTrend(samples)

        assertEquals(BatteryTrendSource.TREND_ESTIMATE, result.source)
        assertEquals(10, result.sampleCount)
        assertEquals(36L, result.coverageDays)
    }

    @Test
    fun exposesOneMedianPointPerDateForTrendChart() {
        val samples = (0 until 9).map { index ->
            sample(
                date = LocalDate.of(2026, 1, 1).plusDays(index.toLong() * 4),
                soc = 80.0,
                range = 240.0
            )
        } + sample(
            date = LocalDate.of(2026, 1, 1),
            soc = 80.0,
            range = 232.0
        )

        val result = estimateBatteryTrend(samples)

        assertEquals(BatteryTrendSource.TREND_ESTIMATE, result.source)
        assertEquals(9, result.points.size)
        assertEquals(LocalDate.of(2026, 1, 1), result.points.first().date)
        assertEquals(295.0, result.points.first().normalizedRangeKm, 0.0001)
    }

    private fun sample(
        date: String,
        soc: Double?,
        range: Double?,
        temperature: Double? = 20.0
    ) = sample(LocalDate.parse(date), soc, range, temperature)

    private fun sample(
        date: LocalDate,
        soc: Double?,
        range: Double?,
        temperature: Double? = 20.0
    ) = BatteryTrendSample(
        date = date,
        socPercent = soc,
        ratedRangeKm = range,
        temperatureC = temperature
    )
}

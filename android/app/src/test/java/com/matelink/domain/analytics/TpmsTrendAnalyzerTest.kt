package com.matelink.domain.analytics

import com.matelink.data.local.TirePosition
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.TpmsPressureSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TpmsTrendAnalyzerTest {
    private val analyzer = TpmsTrendAnalyzer()

    @Test
    fun preservesNullAndZeroValuesInWheelSeries() {
        val result = analyzer.analyze(
            samples = listOf(
                sample(1_000L, fl = 0.0, fr = null, rl = 2.8, rr = Double.NaN),
                sample(2_000L, fl = 2.7, fr = 2.8, rl = null, rr = 2.9)
            ),
            drives = emptyList()
        )

        assertEquals(listOf(0.0, 2.7), result.series[TirePosition.FL]?.map { it.pressureBar })
        assertEquals(listOf(null, 2.8), result.series[TirePosition.FR]?.map { it.pressureBar })
        assertEquals(listOf(2.8, null), result.series[TirePosition.RL]?.map { it.pressureBar })
        assertEquals(listOf(null, 2.9), result.series[TirePosition.RR]?.map { it.pressureBar })
        assertNull(result.deltas[TirePosition.RL])
    }

    @Test
    fun reportsAmbientFactorOnlyWhenAllWheelsFollowObservedTemperature() {
        val result = analyzer.analyze(
            samples = listOf(
                sample(1_000L, 2.5, 2.5, 2.5, 2.5, outsideTempC = 10.0),
                sample(2_000L, 2.7, 2.6, 2.8, 2.7, outsideTempC = 20.0)
            ),
            drives = emptyList()
        )

        assertTrue(result.possibleFactors.any { it.factor == TpmsTrendFactor.AMBIENT })
    }

    @Test
    fun ambientEvidenceDoesNotRequireTemperatureAndPressureToMoveInSameDirection() {
        val result = analyzer.analyze(
            samples = listOf(
                sample(1_000L, 2.5, 2.5, 2.5, 2.5, outsideTempC = 20.0),
                sample(2_000L, 2.7, 2.6, 2.8, 2.7, outsideTempC = 10.0)
            ),
            drives = emptyList()
        )

        assertTrue(result.possibleFactors.any { it.factor == TpmsTrendFactor.AMBIENT })
    }

    @Test
    fun reportsHighwayFactorForRecentFastDriveAndFourRisingWheels() {
        val result = analyzer.analyze(
            samples = listOf(
                sample(1_000_000L, 2.5, 2.5, 2.5, 2.5),
                sample(2_000_000L, 2.7, 2.6, 2.8, 2.7)
            ),
            drives = listOf(drive(startDate = "1970-01-01T00:00:01Z", endDate = "1970-01-01T00:16:40Z", speedMax = 100))
        )

        assertTrue(result.possibleFactors.any { it.factor == TpmsTrendFactor.HIGHWAY })
    }

    @Test
    fun doesNotReportHighwayWhenFastDriveFollowsLatestCompleteSampleBeforeLaterPartialSample() {
        val result = analyzer.analyze(
            samples = listOf(
                sample(1_000_000L, 2.5, 2.5, 2.5, 2.5),
                sample(2_000_000L, 2.7, 2.6, 2.8, 2.7),
                sample(3_000_000L, fl = 2.8)
            ),
            drives = listOf(
                drive(
                    startDate = "1970-01-01T00:30:00Z",
                    endDate = "1970-01-01T00:41:40Z",
                    speedMax = 100
                )
            )
        )

        assertTrue(result.possibleFactors.none { it.factor == TpmsTrendFactor.HIGHWAY })
    }

    @Test
    fun reportsParkingFactorWithColdCheckRecommendationForSingleWheelDecline() {
        val result = analyzer.analyze(
            samples = listOf(
                sample(0L, 2.8, 2.8, 2.8, 2.8),
                sample(24 * 60 * 60 * 1_000L, 2.5, 2.8, 2.8, 2.8)
            ),
            drives = emptyList()
        )

        val parking = result.possibleFactors.first { it.factor == TpmsTrendFactor.PARKING }
        assertTrue(parking.recommendation.orEmpty().contains("cold", ignoreCase = true))
    }

    @Test
    fun reportsParkingFactorWhenOneWheelIsPointTwoBelowThreeWheelsAtTheThreshold() {
        val result = analyzer.analyze(
            samples = listOf(
                sample(0L, 3.0, 3.0, 3.0, 3.0),
                sample(24 * 60 * 60 * 1_000L, 2.6, 2.8, 2.8, 2.8)
            ),
            drives = emptyList()
        )

        assertTrue(result.possibleFactors.any { it.factor == TpmsTrendFactor.PARKING })
    }

    @Test
    fun reportsParkingFactorAtTheTwoPointSixToTwoPointEightDecimalBoundary() {
        val result = analyzer.analyze(
            samples = listOf(
                sample(0L, 2.8, 2.8, 2.8, 2.8),
                sample(24 * 60 * 60 * 1_000L, 2.6, 2.8, 2.8, 2.8)
            ),
            drives = emptyList()
        )

        assertTrue(result.possibleFactors.any { it.factor == TpmsTrendFactor.PARKING })
    }

    @Test
    fun returnsInsufficientEvidenceWhenNoRuleHasEnoughEvidence() {
        val result = analyzer.analyze(
            samples = listOf(sample(1_000L, fl = 2.8)),
            drives = emptyList()
        )

        assertEquals(listOf(TpmsTrendFactor.INSUFFICIENT_EVIDENCE), result.possibleFactors.map { it.factor })
    }

    private fun sample(
        observedAt: Long,
        fl: Double? = null,
        fr: Double? = null,
        rl: Double? = null,
        rr: Double? = null,
        outsideTempC: Double? = null
    ) = TpmsPressureSample(1, observedAt, fl, fr, rl, rr, outsideTempC)

    private fun drive(startDate: String, endDate: String, speedMax: Int) = DriveSummary(
        driveId = 1,
        carId = 1,
        startDate = startDate,
        endDate = endDate,
        durationMin = 16,
        startAddress = "",
        endAddress = "",
        distance = 1.0,
        speedMax = speedMax,
        speedAvg = 50,
        powerMax = 0,
        powerMin = 0,
        startBatteryLevel = 50,
        endBatteryLevel = 49,
        outsideTempAvg = null,
        insideTempAvg = null,
        energyConsumed = null,
        efficiency = null
    )
}

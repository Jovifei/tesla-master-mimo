package com.matelink.domain.analytics

import com.matelink.data.local.TirePosition
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.data.local.entity.TPMS_VERIFIED_COLLECTOR
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
    fun v2PerWheelReceiptsUseTheirActualTimesWithoutFalseNullGapsOrLegacyPromotion() {
        val rows = listOf(
            sample(1_000, fl = 2.5).copy(provenance=TPMS_VERIFIED_COLLECTOR),
            sample(1_500, fr = 2.8).copy(provenance=TPMS_VERIFIED_COLLECTOR),
            sample(2_000, fl = 0.0).copy(provenance=TPMS_VERIFIED_COLLECTOR),
            sample(2_500, fr = 2.7).copy(provenance=TPMS_VERIFIED_COLLECTOR)
        )
        val result = analyzer.analyze(rows, emptyList())
        assertEquals(listOf(1_000L,2_000L), result.series.getValue(TirePosition.FL).map { it.observedAt })
        assertEquals(listOf(2.5,0.0), result.series.getValue(TirePosition.FL).map { it.pressureBar })
        assertEquals(listOf(1_500L,2_500L), result.series.getValue(TirePosition.FR).map { it.observedAt })
        assertEquals(listOf(2.8,2.7), result.series.getValue(TirePosition.FR).map { it.pressureBar })
        assertTrue(result.series.getValue(TirePosition.RL).isEmpty())
        assertTrue(result.series.getValue(TirePosition.RR).isEmpty())
        assertEquals(4,result.coverage.sampleCount)
        assertEquals(2,result.coverage.coverageByWheel[TirePosition.FL])
        assertEquals(2,result.coverage.coverageByWheel[TirePosition.FR])
        assertEquals(-2.5,result.deltas[TirePosition.FL]!!,0.0)
        assertEquals(listOf(TpmsTrendFactor.INSUFFICIENT_EVIDENCE),
            result.possibleFactors.map { it.factor })
    }

    @Test
    fun mixedLegacySamplesRetainExplicitNullButNeverCreateAdditionalV2WheelPoints() {
        val rows = listOf(
            sample(1_000, fl=2.5).copy(provenance=TPMS_VERIFIED_COLLECTOR),
            sample(1_500, fr=2.7).copy(provenance=TPMS_VERIFIED_COLLECTOR),
            sample(2_000, fr=null),
            sample(2_500, fl=2.6).copy(provenance=TPMS_VERIFIED_COLLECTOR)
        )
        val result=analyzer.analyze(rows, emptyList())
        assertEquals(listOf(2.5,null,2.6),result.series.getValue(TirePosition.FL).map { it.pressureBar })
        assertEquals(listOf(2.7,null),result.series.getValue(TirePosition.FR).map { it.pressureBar })
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
    fun ambiguousWallTimeWithoutOffsetCannotProveARecentHighwayDrive() {
        val result = analyzer.analyze(
            samples = listOf(
                sample(1_000_000L, 2.5, 2.5, 2.5, 2.5),
                sample(2_000_000L, 2.7, 2.6, 2.8, 2.7)
            ),
            drives = listOf(drive(startDate = "1970-01-01T00:00:01",
                endDate = "1970-01-01T00:16:40", speedMax = 100))
        )

        assertTrue(result.possibleFactors.none { it.factor == TpmsTrendFactor.HIGHWAY })
    }

    @Test
    fun explicitOffsetAndZAtTheSameInstantRetainTheSameHighwayEvidence() {
        val samples = listOf(
            sample(1_000_000L, 2.5, 2.5, 2.5, 2.5),
            sample(2_000_000L, 2.7, 2.6, 2.8, 2.7)
        )
        val utc = analyzer.analyze(samples,
            listOf(drive(startDate = "1970-01-01T00:00:01Z",
                endDate = "1970-01-01T00:16:40Z", speedMax = 100)))
        val offset = analyzer.analyze(samples,
            listOf(drive(startDate = "1970-01-01T08:00:01+08:00",
                endDate = "1970-01-01T08:16:40+08:00", speedMax = 100)))

        assertTrue(utc.possibleFactors.any { it.factor == TpmsTrendFactor.HIGHWAY })
        assertEquals(utc.possibleFactors, offset.possibleFactors)
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

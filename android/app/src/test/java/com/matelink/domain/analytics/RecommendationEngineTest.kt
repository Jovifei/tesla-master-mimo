package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    @Test
    fun standbyRecommendationRequiresBothSampleAndTimeCoverage() {
        val insufficient = buildRecommendations(
            RecommendationEvidence(
                observationDays = 30,
                standbyAveragePowerW = 260.0,
                standbyWindowCount = 5,
                standbyHours = 19.0
            )
        )
        assertTrue(insufficient.isEmpty())

        val sufficient = buildRecommendations(
            RecommendationEvidence(
                observationDays = 30,
                standbyAveragePowerW = 260.0,
                standbyWindowCount = 5,
                standbyHours = 20.0
            )
        )
        assertEquals(RecommendationKind.STANDBY_POWER, sufficient.single().kind)
    }

    @Test
    fun highSpeedRecommendationNeedsDistanceWeightedBaseline() {
        val recommendations = buildRecommendations(
            RecommendationEvidence(
                observationDays = 60,
                highSpeedEfficiencyWhKm = 220.0,
                highSpeedSampleCount = 5,
                highSpeedDistanceKm = 120.0,
                baselineEfficiencyWhKm = 180.0,
                baselineSampleCount = 5,
                baselineDistanceKm = 100.0
            )
        )

        assertEquals(RecommendationKind.HIGH_SPEED_EFFICIENCY, recommendations.single().kind)
        assertEquals(10, recommendations.single().sampleCount)
        assertEquals(60, recommendations.single().observationDays)
        assertTrue(recommendations.single().monthlyImpact.maximumMonthlyKwh > 0.0)
    }

    @Test
    fun chargeLossBelowThresholdDoesNotCreateAdvice() {
        val recommendations = buildRecommendations(
            RecommendationEvidence(
                observationDays = 30,
                chargeLossPercent = 12.0,
                chargeCount = 10,
                chargeEnergyKwh = 100.0
            )
        )

        assertTrue(recommendations.isEmpty())
    }

    @Test
    fun recommendationRequiresEnoughObservationDaysForMonthlyImpact() {
        val recommendations = buildRecommendations(
            RecommendationEvidence(
                observationDays = 13,
                highSpeedEfficiencyWhKm = 230.0,
                highSpeedSampleCount = 5,
                highSpeedDistanceKm = 120.0,
                baselineEfficiencyWhKm = 180.0,
                baselineSampleCount = 5,
                baselineDistanceKm = 120.0
            )
        )

        assertTrue(recommendations.isEmpty())
    }
}

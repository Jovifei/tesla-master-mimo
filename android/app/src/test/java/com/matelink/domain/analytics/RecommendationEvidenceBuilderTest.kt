package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecommendationEvidenceBuilderTest {
    @Test
    fun buildsWeightedSpeedTemperatureAndChargeEvidence() {
        val drives = buildList {
            repeat(5) { index ->
                add(drive(20.0, 3.6, 70.0, 20.0, "2026-06-${(index + 1).toString().padStart(2, '0')}T08:00:00Z"))
                add(drive(20.0, 4.6, 105.0, 20.0, "2026-07-${(index + 1).toString().padStart(2, '0')}T08:00:00Z"))
                add(drive(20.0, 4.8, 70.0, 0.0, "2026-08-${(index + 1).toString().padStart(2, '0')}T08:00:00Z"))
            }
        }
        val charges = (1..5).map { index ->
            RecommendationChargeSample(11.5, 14.0, "2026-06-${(index * 5).toString().padStart(2, '0')}T20:00:00Z")
        }

        val evidence = buildRecommendationEvidence(drives, charges)

        assertEquals(180.0, evidence.baselineEfficiencyWhKm!!, 0.001)
        assertEquals(230.0, evidence.highSpeedEfficiencyWhKm!!, 0.001)
        assertEquals(240.0, evidence.coldEfficiencyWhKm!!, 0.001)
        assertEquals(180.0, evidence.normalTemperatureEfficiencyWhKm!!, 0.001)
        assertEquals(100.0, evidence.highSpeedDistanceKm, 0.001)
        assertEquals(17.857, evidence.chargeLossPercent!!, 0.001)
        assertEquals(70.0, evidence.chargeEnergyKwh, 0.001)
        assertNotNull(evidence.observationDays)
    }

    @Test
    fun invalidMeasurementsDoNotCreateEvidence() {
        val evidence = buildRecommendationEvidence(
            drives = listOf(drive(0.5, Double.NaN, 110.0, -5.0, "invalid")),
            charges = listOf(RecommendationChargeSample(12.0, 10.0, "invalid"))
        )

        assertNull(evidence.highSpeedEfficiencyWhKm)
        assertNull(evidence.chargeLossPercent)
        assertNull(evidence.observationDays)
    }

    @Test
    fun missingSpeedDoesNotCreateSpeedGroupEvidence() {
        val evidence = buildRecommendationEvidence(
            drives = (1..10).map { index ->
                drive(30.0, 4.0, null, 20.0, "2026-06-${index.toString().padStart(2, '0')}T08:00:00Z")
            },
            charges = emptyList()
        )

        assertNull(evidence.baselineEfficiencyWhKm)
        assertNull(evidence.highSpeedEfficiencyWhKm)
        assertNull(evidence.coldEfficiencyWhKm)
    }

    private fun drive(
        distance: Double,
        energy: Double,
        speed: Double?,
        temperature: Double,
        date: String
    ) = RecommendationDriveSample(distance, energy, speed, temperature, date)
}

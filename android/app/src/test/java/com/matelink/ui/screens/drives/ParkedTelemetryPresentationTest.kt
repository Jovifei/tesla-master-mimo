package com.matelink.ui.screens.drives

import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.api.models.ParkedDetailData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class ParkedTelemetryPresentationTest {
    private val start = "2026-10-25T02:31:00Z"
    private val end = "2026-10-25T02:50:00Z"

    private fun parked(source: String = "teslamate_archive") = ParkedDetailData(
        olderDriveId = 701, newerDriveId = 702,
        startDate = start, endDate = end, source = source
    )

    @Test fun localBoundaryCacheDoesNotClaimHistoricalSamples() {
        val view = parkedTelemetryPresentation(parked("local_room_cache"), Duration.ofMinutes(19), false)
        assertNull(view.energyKwh)
        assertNull(view.rate)
        assertNull(view.insideTemp)
        assertNull(view.outsideTemp)
        assertNull(view.coveragePercent)
        assertNull(view.sampleCount)
        assertTrue(view.hasMissing)
    }

    @Test fun partialResponseKeepsRecordedSamplesVisibleEvenWithoutEnergyOrTemperature() {
        val view = parkedTelemetryPresentation(
            parked().copy(sampleCount = 5, coverageRatio = 0.25), Duration.ofMinutes(19), false
        )
        assertNull(view.energyKwh)
        assertNull(view.rate)
        assertEquals(5, view.sampleCount)
        assertEquals(25.0, view.coveragePercent!!, 0.0)
        assertTrue(view.hasMissing)
    }

    @Test fun rawScalarsNeverBecomeQualifiedEnergyButTemperaturesAndZeroCoverageRemain() {
        val view = parkedTelemetryPresentation(
            parked().copy(energyKwh = 0.42, averagePowerKw = 0.28,
                insideTempAverage = 21.8, outsideTempAverage = 18.2,
                sampleCount = 12, coverageRatio = 0.0), Duration.ofMinutes(19), false
        )
        assertNull(view.energyKwh)
        assertNull(view.rate)
        assertEquals(21.8, view.insideTemp!!, 0.0)
        assertEquals(18.2, view.outsideTemp!!, 0.0)
        assertEquals(0.0, view.coveragePercent!!, 0.0)
        assertEquals(12, view.sampleCount)
    }

    @Test fun qualifiedZeroEnergyAndZeroTemperatureStayRealZero() {
        val metric = EnergyMetric(valueKwh = 0.0, method = "energy_remaining_delta",
            measurementPoint = "nominal_battery_remaining", source = "fleet_telemetry",
            sourceField = "EnergyRemaining", quality = "estimated", startDate = start,
            endDate = end, observedStartAt = start, observedEndAt = end,
            coverageKind = "endpoints", coverageRatio = 1.0,
            activityEvidence = "no_driving_or_charging_during_window")
        val data = parked("fleet_telemetry").copy(energyContract = EnergyContract(storedChange = metric),
            insideTempAverage = 0.0, outsideTempAverage = 0.0,
            sampleCount = 2, coverageRatio = 1.0)
        val view = parkedTelemetryPresentation(data, Duration.ofMinutes(19), false)
        assertEquals(0.0, view.energyKwh!!, 0.0)
        assertEquals(0.0, view.rate!!, 0.0)
        assertEquals(0.0, view.insideTemp!!, 0.0)
        assertEquals(0.0, view.outsideTemp!!, 0.0)
        assertEquals(100.0, view.coveragePercent!!, 0.0)
        assertEquals(2, view.sampleCount)
        assertTrue(view.rateFromTotal)
        assertFalse(view.hasMissing)
        assertNull(parkedTelemetryPresentation(data, Duration.ofMinutes(19), true).energyKwh)
    }
}

package com.matelink.ui.components

import com.matelink.data.local.TpmsAlertProfile
import com.matelink.domain.model.VisualWheelReading
import com.matelink.domain.model.VisualWheelState
import org.junit.Assert.*
import org.junit.Test

class ConfiguredPressureVisualTest {
    private val profile = TpmsAlertProfile(2.9, 2.6, 3.4, true)
    private fun reading(pressure: Double) = VisualWheelReading(pressure, 1800000000000L, VisualWheelState.RECORDED)
    @Test fun usesOnlySavedLimitsAndPreservesZeroAsAQualifiedLowValue() {
        assertEquals(VisualPressureBand.LOW, visualPressureBand(reading(0.0), profile))
        assertEquals(VisualPressureBand.LOW, visualPressureBand(reading(2.59), profile))
        assertEquals(VisualPressureBand.IN_RANGE, visualPressureBand(reading(2.6), profile))
        assertEquals(VisualPressureBand.IN_RANGE, visualPressureBand(reading(3.4), profile))
        assertEquals(VisualPressureBand.HIGH, visualPressureBand(reading(3.41), profile))
    }
    @Test fun absentDisabledAndInvalidSettingsNeverMeanNormal() {
        for (setting in listOf(null, profile.copy(enabled = false), profile.copy(lowBar = 4.0), profile.copy(highBar = Double.NaN))) {
            assertEquals(VisualPressureBand.UNASSESSED, visualPressureBand(reading(2.9), setting))
        }
    }
    @Test fun staleUnknownMalformedAndMissingTimesNeverBecomeCurrentWarnings() {
        for (row in listOf(null, reading(2.0).copy(state = VisualWheelState.STALE),
            reading(2.0).copy(state = VisualWheelState.UNKNOWN), reading(Double.NaN),
            reading(2.0).copy(observedAt = null))) {
            assertEquals(VisualPressureBand.UNASSESSED, visualPressureBand(row, profile))
        }
    }
    @Test fun ruleChangesReclassifyWithoutMutatingTheOriginalObservation() {
        val row = reading(2.7)
        assertEquals(VisualPressureBand.IN_RANGE, visualPressureBand(row, profile))
        assertEquals(VisualPressureBand.LOW, visualPressureBand(row, profile.copy(lowBar = 2.8)))
        assertEquals(reading(2.7), row)
    }
}

package com.matelink.ui.components

import com.matelink.domain.model.VisualWheelReading
import com.matelink.domain.model.VisualWheelState
import org.junit.Assert.*
import org.junit.Test

class InteractionEvidencePresentationTest {
    private val observed = VisualWheelReading(2.6, 1800000000000L, VisualWheelState.RECORDED)
    @Test fun unknownNeverPublishesItsUnverifiedValueOrTime() {
        val result = qualifiedVisualReading(observed.copy(state = VisualWheelState.UNKNOWN))
        assertNull(result.pressureBar); assertNull(result.observedAt)
        assertEquals(VisualWheelState.UNKNOWN, result.state)
    }
    @Test fun genuineZeroSurvivesWithoutBeingCalledNormal() {
        val result = qualifiedVisualReading(observed.copy(pressureBar = 0.0))
        assertEquals(0.0, result.pressureBar!!, 0.0)
        assertEquals(VisualWheelState.RECORDED, result.state)
    }
    @Test fun rejectsNonFiniteAndOutOfRangeValues() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 15.01)) {
            assertNull(qualifiedVisualReading(observed.copy(pressureBar = value)).pressureBar)
        }
    }
    @Test fun missingOrInvalidObservationTimeDoesNotGainAClockTime() {
        for (time in listOf(null, 0L, -1L)) {
            assertNull(qualifiedVisualReading(observed.copy(observedAt = time)).observedAt)
        }
    }
    @Test fun preservesExistingWarningAndStaleClassification() {
        for (state in listOf(VisualWheelState.STALE, VisualWheelState.WARNING)) {
            val original = observed.copy(state = state)
            assertEquals(original, qualifiedVisualReading(original))
        }
    }
    @Test fun latestTimeUsesQualifiedIndependentWheelsOnly() {
        val unknown = observed.copy(observedAt = observed.observedAt!! + 10000L, state = VisualWheelState.UNKNOWN)
        val older = observed.copy(observedAt = observed.observedAt!! - 1000L, state = VisualWheelState.STALE)
        assertEquals(observed.observedAt, latestVisualWheelObservation(listOf(older, observed, unknown)))
    }
    @Test fun emptyAndMalformedCollectionsRemainUnknown() {
        assertNull(latestVisualWheelObservation(emptyList()))
        assertNull(latestVisualWheelObservation(listOf(observed.copy(pressureBar = null))))
        assertNull(qualifiedVisualReading(null).pressureBar)
    }
}

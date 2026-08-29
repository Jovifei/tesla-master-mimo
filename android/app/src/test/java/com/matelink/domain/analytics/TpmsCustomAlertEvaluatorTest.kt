package com.matelink.domain.analytics

import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.local.TirePosition
import com.matelink.data.local.TpmsCustomPendingAlert
import com.matelink.data.local.TpmsCustomPressureState
import com.matelink.data.local.TpmsCustomWheelState
import com.matelink.data.local.TpmsAlertProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TpmsCustomAlertEvaluatorTest {
    private val evaluator = TpmsCustomAlertEvaluator()
    private val profile = TpmsAlertProfile(
        targetBar = 2.9,
        lowBar = 2.6,
        highBar = 3.4,
        enabled = true
    )

    @Test
    fun notifiesOnBreachEntryAndChangeButNotOnRepeatedRuns() {
        val first = evaluator.evaluate(
            profile = profile,
            previousStates = emptyMap(),
            tpms = TpmsDetails(pressureFl = 2.5)
        )
        assertEquals(
            listOf(TpmsCustomAlert(TirePosition.FL, TpmsCustomPressureState.LOW, 2.5, 2.6)),
            first.alerts
        )

        val repeated = evaluator.evaluate(
            profile = profile,
            previousStates = first.nextStates,
            tpms = TpmsDetails(pressureFl = 2.4)
        )
        assertTrue(repeated.alerts.isEmpty())

        val changed = evaluator.evaluate(
            profile = profile,
            previousStates = repeated.nextStates,
            tpms = TpmsDetails(pressureFl = 3.5)
        )
        assertEquals(
            listOf(TpmsCustomAlert(TirePosition.FL, TpmsCustomPressureState.HIGH, 3.5, 3.4)),
            changed.alerts
        )
    }

    @Test
    fun normalRecoveryClearsStateWithoutAlert() {
        val result = evaluator.evaluate(
            profile = profile,
            previousStates = mapOf(
                TirePosition.FL to TpmsCustomWheelState(
                    TpmsCustomPressureState.LOW,
                    TpmsCustomPendingAlert(2.5, 2.6)
                )
            ),
            tpms = TpmsDetails(pressureFl = 2.9)
        )

        assertTrue(result.alerts.isEmpty())
        assertTrue(result.nextStates.isEmpty())
    }

    @Test
    fun nullAndNonFiniteReadingsDoNotCreateOrClearCustomState() {
        val previous = mapOf(
            TirePosition.FL to TpmsCustomWheelState(TpmsCustomPressureState.LOW)
        )
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { reading ->
            val result = evaluator.evaluate(
                profile = profile,
                previousStates = previous,
                tpms = TpmsDetails(pressureFl = reading)
            )

            assertTrue(result.alerts.isEmpty())
            assertEquals(previous, result.nextStates)
        }
    }

    @Test
    fun disabledOrInvalidProfileDoesNotEvaluateBreaches() {
        listOf(
            profile.copy(enabled = false),
            TpmsAlertProfile(2.9, 3.0, 2.8, enabled = true)
        ).forEach { candidate ->
            val result = evaluator.evaluate(
                profile = candidate,
                previousStates = emptyMap(),
                tpms = TpmsDetails(pressureFl = 2.0)
            )

            assertTrue(result.alerts.isEmpty())
            assertTrue(result.nextStates.isEmpty())
        }
    }
}

package com.matelink.domain.analytics

import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.local.TirePosition
import com.matelink.data.local.TpmsAlertProfile
import com.matelink.data.local.TpmsCustomWheelObservation
import com.matelink.data.local.TpmsCustomWheelState
import com.matelink.data.local.TpmsCustomPressureState

data class TpmsCustomAlert(
    val wheel: TirePosition,
    val state: TpmsCustomPressureState,
    val observedPressureBar: Double,
    val thresholdBar: Double
)

data class TpmsCustomAlertEvaluation(
    val nextStates: Map<TirePosition, TpmsCustomWheelState>,
    val currentBreaches: Map<TirePosition, TpmsCustomAlert>,
    val alerts: List<TpmsCustomAlert>
)

/** Stable profile identity used to invalidate persisted custom-alert state. */
internal fun TpmsAlertProfile.tpmsCustomAlertFingerprint(): String =
    listOf(
        enabled,
        lowBar.toBits(),
        targetBar.toBits(),
        highBar.toBits()
    ).joinToString(separator = "|")

/** Classifies finite wheel observations and emits only custom breach transitions. */
class TpmsCustomAlertEvaluator {
    fun evaluate(
        profile: TpmsAlertProfile,
        previousStates: Map<TirePosition, TpmsCustomWheelState>,
        tpms: TpmsDetails?
    ): TpmsCustomAlertEvaluation {
        if (!profile.enabled || !profile.isValid) {
            return TpmsCustomAlertEvaluation(previousStates, emptyMap(), emptyList())
        }

        val nextStates = previousStates.toMutableMap()
        val currentBreaches = linkedMapOf<TirePosition, TpmsCustomAlert>()
        val alerts = mutableListOf<TpmsCustomAlert>()

        observe(profile, tpms).forEach { (wheel, observation) ->
            if (!observation.observed) return@forEach
            val pressure = requireNotNull(observation.observedPressureBar)
            val state = observation.state
            if (state == null) {
                nextStates.remove(wheel)
            } else {
                val alert = TpmsCustomAlert(
                    wheel = wheel,
                    state = state,
                    observedPressureBar = pressure,
                    thresholdBar = if (state == TpmsCustomPressureState.LOW) {
                        profile.lowBar
                    } else {
                        profile.highBar
                    }
                )
                currentBreaches[wheel] = alert
                if (previousStates[wheel]?.state != state) {
                    alerts += alert
                }
                nextStates[wheel] = TpmsCustomWheelState(
                    state = state,
                    pending = previousStates[wheel]?.pending
                )
            }
        }

        return TpmsCustomAlertEvaluation(nextStates, currentBreaches, alerts)
    }

    fun observe(
        profile: TpmsAlertProfile,
        tpms: TpmsDetails?
    ): Map<TirePosition, TpmsCustomWheelObservation> = mapOf(
        TirePosition.FL to observation(tpms?.pressureFl, profile),
        TirePosition.FR to observation(tpms?.pressureFr, profile),
        TirePosition.RL to observation(tpms?.pressureRl, profile),
        TirePosition.RR to observation(tpms?.pressureRr, profile)
    )

    private fun observation(
        pressure: Double?,
        profile: TpmsAlertProfile
    ): TpmsCustomWheelObservation {
        if (pressure == null || !pressure.isFinite()) {
            return TpmsCustomWheelObservation(false, null, null, null)
        }
        val state = when {
            pressure < profile.lowBar -> TpmsCustomPressureState.LOW
            pressure > profile.highBar -> TpmsCustomPressureState.HIGH
            else -> null
        }
        return TpmsCustomWheelObservation(
            observed = true,
            state = state,
            observedPressureBar = pressure,
            thresholdBar = when (state) {
                TpmsCustomPressureState.LOW -> profile.lowBar
                TpmsCustomPressureState.HIGH -> profile.highBar
                null -> null
            }
        )
    }
}

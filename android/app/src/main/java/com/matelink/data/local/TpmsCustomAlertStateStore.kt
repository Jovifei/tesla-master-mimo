package com.matelink.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

enum class TpmsCustomPressureState {
    LOW,
    HIGH
}

data class TpmsCustomPendingAlert(
    val observedPressureBar: Double,
    val thresholdBar: Double
)

internal fun finiteTpmsCustomPendingAlert(
    observedPressureBar: Double?,
    thresholdBar: Double?
): TpmsCustomPendingAlert? = if (
    observedPressureBar?.isFinite() == true && thresholdBar?.isFinite() == true
) {
    TpmsCustomPendingAlert(observedPressureBar, thresholdBar)
} else {
    null
}

data class TpmsCustomAlertClaim(
    val wheel: TirePosition,
    val state: TpmsCustomPressureState,
    val observedPressureBar: Double,
    val thresholdBar: Double,
    val token: String = ""
)

data class TpmsCustomWheelObservation(
    val observed: Boolean,
    val state: TpmsCustomPressureState?,
    val observedPressureBar: Double?,
    val thresholdBar: Double?
)

data class TpmsCustomWheelState(
    val state: TpmsCustomPressureState,
    val pending: TpmsCustomPendingAlert? = null,
    val claim: TpmsCustomPendingAlert? = null,
    val claimStartedAt: Long = 0L,
    val claimToken: String? = null
)

private val Context.tpmsCustomAlertDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tpms_custom_alert_state"
)

/** Persistent custom-alert state kept separate from Tesla soft-warning state. */
@Singleton
class TpmsCustomAlertStateStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun getStates(carId: Int): Map<TirePosition, TpmsCustomWheelState> {
        val preferences = context.tpmsCustomAlertDataStore.data.first()
        return TirePosition.values().mapNotNull { wheel ->
            val state = preferences[stateKey(carId, wheel)]?.let { value ->
                runCatching { TpmsCustomPressureState.valueOf(value) }.getOrNull()
            } ?: return@mapNotNull null
            val pending = finiteTpmsCustomPendingAlert(
                preferences[pendingObservedKey(carId, wheel)],
                preferences[pendingThresholdKey(carId, wheel)]
            )
            val claim = finiteTpmsCustomPendingAlert(
                preferences[claimObservedKey(carId, wheel)],
                preferences[claimThresholdKey(carId, wheel)]
            )
            wheel to TpmsCustomWheelState(
                state = state,
                pending = pending,
                claim = claim,
                claimStartedAt = preferences[claimStartedAtKey(carId, wheel)] ?: 0L,
                claimToken = claim?.let {
                    preferences[claimTokenKey(carId, wheel)]?.takeIf(String::isNotBlank)
                }
            )
        }.toMap()
    }

    suspend fun saveStates(
        carId: Int,
        states: Map<TirePosition, TpmsCustomWheelState>
    ) {
        context.tpmsCustomAlertDataStore.edit { preferences ->
            TirePosition.values().forEach { wheel ->
                preferences.remove(stateKey(carId, wheel))
                preferences.remove(pendingObservedKey(carId, wheel))
                preferences.remove(pendingThresholdKey(carId, wheel))
                preferences.remove(claimObservedKey(carId, wheel))
                preferences.remove(claimThresholdKey(carId, wheel))
                preferences.remove(claimStartedAtKey(carId, wheel))
                preferences.remove(claimTokenKey(carId, wheel))
                states[wheel]?.let { state ->
                    preferences[stateKey(carId, wheel)] = state.state.name
                    finiteTpmsCustomPendingAlert(
                        state.pending?.observedPressureBar,
                        state.pending?.thresholdBar
                    )?.let { pending ->
                        preferences[pendingObservedKey(carId, wheel)] = pending.observedPressureBar
                        preferences[pendingThresholdKey(carId, wheel)] = pending.thresholdBar
                    }
                    finiteTpmsCustomPendingAlert(
                        state.claim?.observedPressureBar,
                        state.claim?.thresholdBar
                    )?.let { claim ->
                        preferences[claimObservedKey(carId, wheel)] = claim.observedPressureBar
                        preferences[claimThresholdKey(carId, wheel)] = claim.thresholdBar
                        preferences[claimStartedAtKey(carId, wheel)] = state.claimStartedAt
                        state.claimToken?.takeIf(String::isNotBlank)?.let { token ->
                            preferences[claimTokenKey(carId, wheel)] = token
                        }
                    }
                }
            }
        }
    }

    suspend fun resetForProfile(carId: Int, fingerprint: String) {
        context.tpmsCustomAlertDataStore.edit { preferences ->
            if (preferences[profileFingerprintKey(carId)] != fingerprint) {
                clearCarState(preferences, carId)
                preferences[profileFingerprintKey(carId)] = fingerprint
            }
        }
    }

    /** Atomically updates wheel state and claims pending alerts for one run. */
    suspend fun claimAlerts(
        carId: Int,
        fingerprint: String,
        observations: Map<TirePosition, TpmsCustomWheelObservation>,
        defer: Boolean,
        now: Long
    ): List<TpmsCustomAlertClaim> {
        val claims = mutableListOf<TpmsCustomAlertClaim>()
        context.tpmsCustomAlertDataStore.edit { preferences ->
            if (preferences[profileFingerprintKey(carId)] != fingerprint) {
                clearCarState(preferences, carId)
                preferences[profileFingerprintKey(carId)] = fingerprint
            }

            observations.forEach { (wheel, observation) ->
                if (!observation.observed) return@forEach
                if (observation.state == null) {
                    clearWheelState(preferences, carId, wheel)
                    return@forEach
                }
                if (observation.observedPressureBar?.isFinite() != true ||
                    observation.thresholdBar?.isFinite() != true
                ) {
                    return@forEach
                }
                val pendingObservation = finiteTpmsCustomPendingAlert(
                    observation.observedPressureBar,
                    observation.thresholdBar
                ) ?: return@forEach

                val previous = readState(preferences, carId, wheel)
                val transition = previous == null || previous.state != observation.state
                var next = if (transition) {
                    TpmsCustomWheelState(
                        state = observation.state,
                        pending = pendingObservation
                    )
                } else {
                    previous.copy(state = observation.state)
                }

                if (!defer && next.pending != null) {
                    val pending = next.pending
                    val claimIsLive = next.claim == pending &&
                        !next.claimToken.isNullOrBlank() &&
                        now - next.claimStartedAt < CLAIM_LEASE_MS
                    if (!claimIsLive) {
                        val token = UUID.randomUUID().toString()
                        next = next.copy(claim = pending, claimStartedAt = now, claimToken = token)
                        claims += TpmsCustomAlertClaim(
                            wheel = wheel,
                            state = next.state,
                            observedPressureBar = pending.observedPressureBar,
                            thresholdBar = pending.thresholdBar,
                            token = token
                        )
                    }
                }
                writeState(preferences, carId, wheel, next)
            }
        }
        return claims
    }

    suspend fun commitClaim(carId: Int, claim: TpmsCustomAlertClaim) {
        if (finiteTpmsCustomPendingAlert(claim.observedPressureBar, claim.thresholdBar) == null) {
            return
        }
        context.tpmsCustomAlertDataStore.edit { preferences ->
            val current = readState(preferences, carId, claim.wheel)
            val expected = TpmsCustomPendingAlert(claim.observedPressureBar, claim.thresholdBar)
            if (current?.claim == expected && current.state == claim.state &&
                current.claimToken == claim.token && claim.token.isNotBlank()
            ) {
                writeState(
                    preferences,
                    carId,
                    claim.wheel,
                    current.copy(pending = null, claim = null, claimStartedAt = 0L, claimToken = null)
                )
            }
        }
    }

    suspend fun releaseClaim(carId: Int, claim: TpmsCustomAlertClaim) {
        if (finiteTpmsCustomPendingAlert(claim.observedPressureBar, claim.thresholdBar) == null) {
            return
        }
        context.tpmsCustomAlertDataStore.edit { preferences ->
            val current = readState(preferences, carId, claim.wheel)
            val expected = TpmsCustomPendingAlert(claim.observedPressureBar, claim.thresholdBar)
            if (current?.claim == expected && current.state == claim.state &&
                current.claimToken == claim.token && claim.token.isNotBlank()
            ) {
                writeState(
                    preferences,
                    carId,
                    claim.wheel,
                    current.copy(claim = null, claimStartedAt = 0L, claimToken = null)
                )
            }
        }
    }

    private fun stateKey(carId: Int, wheel: TirePosition) =
        stringPreferencesKey("tpms_custom_${carId}_${wheel.name}")

    private fun pendingObservedKey(carId: Int, wheel: TirePosition) =
        doublePreferencesKey("tpms_custom_pending_observed_${carId}_${wheel.name}")

    private fun pendingThresholdKey(carId: Int, wheel: TirePosition) =
        doublePreferencesKey("tpms_custom_pending_threshold_${carId}_${wheel.name}")

    private fun claimObservedKey(carId: Int, wheel: TirePosition) =
        doublePreferencesKey("tpms_custom_claim_observed_${carId}_${wheel.name}")

    private fun claimThresholdKey(carId: Int, wheel: TirePosition) =
        doublePreferencesKey("tpms_custom_claim_threshold_${carId}_${wheel.name}")

    private fun claimStartedAtKey(carId: Int, wheel: TirePosition) =
        longPreferencesKey("tpms_custom_claim_started_${carId}_${wheel.name}")

    private fun claimTokenKey(carId: Int, wheel: TirePosition) =
        stringPreferencesKey("tpms_custom_claim_token_${carId}_${wheel.name}")

    private fun profileFingerprintKey(carId: Int) =
        stringPreferencesKey("tpms_custom_profile_${carId}")

    private fun readState(
        preferences: Preferences,
        carId: Int,
        wheel: TirePosition
    ): TpmsCustomWheelState? = preferences[stateKey(carId, wheel)]?.let { value ->
        val state = runCatching { TpmsCustomPressureState.valueOf(value) }.getOrNull() ?: return null
        val pendingObserved = preferences[pendingObservedKey(carId, wheel)]?.takeIf { it.isFinite() }
        val pendingThreshold = preferences[pendingThresholdKey(carId, wheel)]?.takeIf { it.isFinite() }
        val claimObserved = preferences[claimObservedKey(carId, wheel)]?.takeIf { it.isFinite() }
        val claimThreshold = preferences[claimThresholdKey(carId, wheel)]?.takeIf { it.isFinite() }
        TpmsCustomWheelState(
            state = state,
            pending = finiteTpmsCustomPendingAlert(pendingObserved, pendingThreshold),
            claim = finiteTpmsCustomPendingAlert(claimObserved, claimThreshold),
            claimStartedAt = preferences[claimStartedAtKey(carId, wheel)] ?: 0L,
            claimToken = finiteTpmsCustomPendingAlert(claimObserved, claimThreshold)?.let {
                preferences[claimTokenKey(carId, wheel)]?.takeIf(String::isNotBlank)
            }
        )
    }

    private fun writeState(
        preferences: MutablePreferences,
        carId: Int,
        wheel: TirePosition,
        state: TpmsCustomWheelState
    ) {
        preferences.remove(pendingObservedKey(carId, wheel))
        preferences.remove(pendingThresholdKey(carId, wheel))
        preferences.remove(claimObservedKey(carId, wheel))
        preferences.remove(claimThresholdKey(carId, wheel))
        preferences.remove(claimStartedAtKey(carId, wheel))
        preferences.remove(claimTokenKey(carId, wheel))
        preferences[stateKey(carId, wheel)] = state.state.name
        state.pending?.takeIf {
            it.observedPressureBar.isFinite() && it.thresholdBar.isFinite()
        }?.let { pending ->
            preferences[pendingObservedKey(carId, wheel)] = pending.observedPressureBar
            preferences[pendingThresholdKey(carId, wheel)] = pending.thresholdBar
        }
        state.claim?.takeIf {
            it.observedPressureBar.isFinite() && it.thresholdBar.isFinite()
        }?.let { claim ->
            preferences[claimObservedKey(carId, wheel)] = claim.observedPressureBar
            preferences[claimThresholdKey(carId, wheel)] = claim.thresholdBar
            preferences[claimStartedAtKey(carId, wheel)] = state.claimStartedAt
            state.claimToken?.takeIf(String::isNotBlank)?.let { token ->
                preferences[claimTokenKey(carId, wheel)] = token
            }
        }
    }

    private fun clearWheelState(
        preferences: MutablePreferences,
        carId: Int,
        wheel: TirePosition
    ) {
        preferences.remove(stateKey(carId, wheel))
        preferences.remove(pendingObservedKey(carId, wheel))
        preferences.remove(pendingThresholdKey(carId, wheel))
        preferences.remove(claimObservedKey(carId, wheel))
        preferences.remove(claimThresholdKey(carId, wheel))
        preferences.remove(claimStartedAtKey(carId, wheel))
        preferences.remove(claimTokenKey(carId, wheel))
    }

    private fun clearCarState(
        preferences: MutablePreferences,
        carId: Int
    ) {
        TirePosition.values().forEach { wheel -> clearWheelState(preferences, carId, wheel) }
    }

    private companion object {
        const val CLAIM_LEASE_MS = 5 * 60 * 1_000L
    }
}

package com.matelink.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    val thresholdBar: Double?,
    val observedAt: Long = 0L,
    val qualifiedReceipt: Boolean = false
)

data class TpmsCustomWheelState(
    val state: TpmsCustomPressureState,
    val pending: TpmsCustomPendingAlert? = null,
    val claim: TpmsCustomPendingAlert? = null,
    val claimStartedAt: Long = 0L,
    val claimToken: String? = null,
    val observedAt: Long = 0L,
    val qualifiedReceipt: Boolean = false
)

private val Context.tpmsCustomAlertDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tpms_custom_alert_state"
)

/** Persistent custom-alert state kept separate from Tesla soft-warning state. */
@Singleton
class TpmsCustomAlertStateStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.tpmsCustomAlertDataStore)
    suspend fun getStates(carId: Int): Map<TirePosition, TpmsCustomWheelState> {
        val preferences = dataStore.data.first()
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
                },
                observedAt = preferences[receiptKey(carId,wheel)] ?: 0L,
                qualifiedReceipt = preferences[qualifiedKey(carId,wheel)] ?: false
            )
        }.toMap()
    }

    suspend fun saveStates(
        carId: Int,
        states: Map<TirePosition, TpmsCustomWheelState>
    ) {
        dataStore.edit { preferences ->
            TirePosition.values().forEach { wheel ->
                preferences.remove(stateKey(carId, wheel))
                preferences.remove(pendingObservedKey(carId, wheel))
                preferences.remove(pendingThresholdKey(carId, wheel))
                preferences.remove(claimObservedKey(carId, wheel))
                preferences.remove(claimThresholdKey(carId, wheel))
                preferences.remove(claimStartedAtKey(carId, wheel))
                preferences.remove(claimTokenKey(carId, wheel))
                preferences.remove(receiptKey(carId,wheel))
                preferences.remove(qualifiedKey(carId,wheel))
                states[wheel]?.let { state ->
                    preferences[stateKey(carId, wheel)] = state.state.name
                    preferences[receiptKey(carId,wheel)] = state.observedAt
                    preferences[qualifiedKey(carId,wheel)] = state.qualifiedReceipt
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
        dataStore.edit { preferences ->
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
        dataStore.edit { preferences ->
            if (preferences[profileFingerprintKey(carId)] != fingerprint) {
                clearCarState(preferences, carId)
                preferences[profileFingerprintKey(carId)] = fingerprint
            }

            observations.forEach { (wheel, observation) ->
                if (!observation.observed) return@forEach
                if (observation.qualifiedReceipt) {
                    if (!validReceiptTime(observation.observedAt, now)) return@forEach
                    val lastReceipt = preferences[lastReceiptKey(carId,wheel)] ?: 0L
                    if (observation.observedAt < lastReceipt) return@forEach
                    val lastValue = preferences[lastValueKey(carId,wheel)]
                    if (observation.observedAt == lastReceipt && lastValue != null &&
                        lastValue != observation.observedPressureBar) return@forEach
                    preferences[lastReceiptKey(carId,wheel)] = observation.observedAt
                    observation.observedPressureBar?.takeIf { it.isFinite() }?.let {
                        preferences[lastValueKey(carId,wheel)] = it
                    }
                }
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
                        pending = pendingObservation,
                        observedAt = observation.observedAt,
                        qualifiedReceipt = observation.qualifiedReceipt
                    )
                } else {
                    previous.copy(state = observation.state)
                }

                val originalReceiptEligible = !observation.qualifiedReceipt ||
                    (next.qualifiedReceipt && validReceiptTime(next.observedAt,now))
                if (!defer && next.pending != null && originalReceiptEligible) {
                    val pending = next.pending
                    val claimIsLive = next.claim == pending &&
                        !next.claimToken.isNullOrBlank() &&
                        liveLease(next.claimStartedAt, now)
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

            // Unknown API fields cannot rearm alerts. Only original qualified
            // durable evidence can be consumed independently of a fresh poll.
            if (!defer) TirePosition.entries.filter { observations[it]?.observed != true }.forEach { wheel ->
                val state = readState(preferences,carId,wheel) ?: return@forEach
                val pending = state.pending ?: return@forEach
                if (!state.qualifiedReceipt || !validReceiptTime(state.observedAt,now)) return@forEach
                if (state.claim == pending && !state.claimToken.isNullOrBlank() && liveLease(state.claimStartedAt,now)) return@forEach
                val token = UUID.randomUUID().toString()
                writeState(preferences,carId,wheel,state.copy(claim=pending,claimStartedAt=now,claimToken=token))
                claims += TpmsCustomAlertClaim(wheel,state.state,pending.observedPressureBar,pending.thresholdBar,token)
            }
        }
        return claims
    }

    suspend fun commitClaim(carId: Int, claim: TpmsCustomAlertClaim) {
        if (finiteTpmsCustomPendingAlert(claim.observedPressureBar, claim.thresholdBar) == null) {
            return
        }
        dataStore.edit { preferences ->
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
        dataStore.edit { preferences ->
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

    internal suspend fun isClaimCurrent(carId:Int,claim:TpmsCustomAlertClaim):Boolean {
        val current=readState(dataStore.data.first(),carId,claim.wheel) ?: return false
        val expected=TpmsCustomPendingAlert(claim.observedPressureBar,claim.thresholdBar)
        return current.claim==expected && current.pending==expected && current.state==claim.state &&
            current.claimToken==claim.token && claim.token.isNotBlank()
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

    private fun receiptKey(carId:Int,wheel:TirePosition)=longPreferencesKey("tpms_custom_receipt_${carId}_${wheel.name}")
    private fun qualifiedKey(carId:Int,wheel:TirePosition)=booleanPreferencesKey("tpms_custom_qualified_${carId}_${wheel.name}")
    private fun lastReceiptKey(carId:Int,wheel:TirePosition)=longPreferencesKey("tpms_custom_last_receipt_${carId}_${wheel.name}")
    private fun lastValueKey(carId:Int,wheel:TirePosition)=doublePreferencesKey("tpms_custom_last_value_${carId}_${wheel.name}")

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
            },
            observedAt = preferences[receiptKey(carId,wheel)] ?: 0L,
            qualifiedReceipt = preferences[qualifiedKey(carId,wheel)] ?: false
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
        preferences[receiptKey(carId,wheel)] = state.observedAt
        preferences[qualifiedKey(carId,wheel)] = state.qualifiedReceipt
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
        preferences.remove(receiptKey(carId,wheel))
        preferences.remove(qualifiedKey(carId,wheel))
    }

    private fun clearCarState(
        preferences: MutablePreferences,
        carId: Int
    ) {
        TirePosition.values().forEach { wheel ->
            clearWheelState(preferences, carId, wheel)
            preferences.remove(lastReceiptKey(carId,wheel))
            preferences.remove(lastValueKey(carId,wheel))
        }
    }

    private companion object {
        const val CLAIM_LEASE_MS = 5 * 60 * 1_000L
    }
}

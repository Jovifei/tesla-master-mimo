package com.matelink.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

private val Context.tpmsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tpms_state")

/**
 * Represents the TPMS (Tire Pressure Monitoring System) warning state for a car.
 */
data class TpmsState(
    val warningFl: Boolean = false,
    val warningFr: Boolean = false,
    val warningRl: Boolean = false,
    val warningRr: Boolean = false,
    val lastCheckedAt: Long = 0L
) {
    /**
     * Returns true if any tire has a warning.
     */
    val hasAnyWarning: Boolean
        get() = warningFl || warningFr || warningRl || warningRr

    /**
     * Returns list of tire positions that have warnings.
     */
    fun getWarningTires(): List<TirePosition> {
        return buildList {
            if (warningFl) add(TirePosition.FL)
            if (warningFr) add(TirePosition.FR)
            if (warningRl) add(TirePosition.RL)
            if (warningRr) add(TirePosition.RR)
        }
    }
}

/**
 * Tire position enum for identifying which tires have warnings.
 */
enum class TirePosition {
    FL, FR, RL, RR
}

/**
 * Preferences DataStore for persisting TPMS warning state per car.
 * State is keyed by carId (e.g., tpms_warning_fl_1 for car 1's front left tire).
 */
@Singleton
class TpmsStateDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Key builders for car-specific preferences
    private fun warningFlKey(carId: Int, prefix: String = "") = booleanPreferencesKey("${prefix}tpms_warning_fl_$carId")
    private fun warningFrKey(carId: Int, prefix: String = "") = booleanPreferencesKey("${prefix}tpms_warning_fr_$carId")
    private fun warningRlKey(carId: Int, prefix: String = "") = booleanPreferencesKey("${prefix}tpms_warning_rl_$carId")
    private fun warningRrKey(carId: Int, prefix: String = "") = booleanPreferencesKey("${prefix}tpms_warning_rr_$carId")
    private fun lastCheckedKey(carId: Int) = longPreferencesKey("tpms_last_checked_$carId")
    private fun claimStartedAtKey(carId: Int) = longPreferencesKey("tpms_claim_started_$carId")
    private fun claimTokenKey(carId: Int) = stringPreferencesKey("tpms_claim_token_$carId")

    /**
     * Get the current TPMS state for a specific car.
     */
    suspend fun getState(carId: Int): TpmsState {
        return context.tpmsDataStore.data.map { preferences ->
            readState(preferences, carId)
        }.first()
    }

    /**
     * Save the TPMS state for a specific car.
     */
    suspend fun saveState(carId: Int, state: TpmsState) {
        context.tpmsDataStore.edit { preferences ->
            writeState(preferences, carId, state)
            preferences[lastCheckedKey(carId)] = state.lastCheckedAt
        }
    }

    internal data class TpmsStateTransitionClaim(
        val previousState: TpmsState,
        val nextState: TpmsState,
        val token: String
    )

    internal sealed interface TpmsStateDataStoreClaimResult {
        data object NoTransition : TpmsStateDataStoreClaimResult
        data class Claimed(val claim: TpmsStateTransitionClaim) : TpmsStateDataStoreClaimResult
        data object InFlight : TpmsStateDataStoreClaimResult
    }

    internal suspend fun claimStateChange(
        carId: Int,
        nextState: TpmsState,
        now: Long
    ): TpmsStateDataStoreClaimResult {
        var result: TpmsStateDataStoreClaimResult = TpmsStateDataStoreClaimResult.NoTransition
        context.tpmsDataStore.edit { preferences ->
            val previousState = readState(preferences, carId)
            val desiredState = nextState.copy(lastCheckedAt = 0L)
            val pendingState = readState(preferences, carId, PENDING_PREFIX)

            if (sameWarnings(previousState, desiredState)) {
                clearPendingState(preferences, carId)
                return@edit
            }
            if (!sameWarnings(pendingState, desiredState)) {
                clearClaimState(preferences, carId)
                writeState(preferences, carId, desiredState, PENDING_PREFIX)
            }

            val claimedState = readState(preferences, carId, CLAIM_PREFIX)
            val startedAt = preferences[claimStartedAtKey(carId)] ?: 0L
            val claimIsLive = sameWarnings(claimedState, desiredState) &&
                !preferences[claimTokenKey(carId)].isNullOrBlank() &&
                now - startedAt < CLAIM_LEASE_MS
            if (claimIsLive) {
                result = TpmsStateDataStoreClaimResult.InFlight
            } else {
                val token = UUID.randomUUID().toString()
                writeState(preferences, carId, desiredState, CLAIM_PREFIX)
                preferences[claimStartedAtKey(carId)] = now
                preferences[claimTokenKey(carId)] = token
                result = TpmsStateDataStoreClaimResult.Claimed(
                    TpmsStateTransitionClaim(previousState, desiredState, token)
                )
            }
        }
        return result
    }

    internal suspend fun commitStateChange(carId: Int, claim: TpmsStateTransitionClaim, now: Long) {
        context.tpmsDataStore.edit { preferences ->
            val claimedState = readState(preferences, carId, CLAIM_PREFIX)
            val pendingState = readState(preferences, carId, PENDING_PREFIX)
            if (sameWarnings(claimedState, claim.nextState) &&
                sameWarnings(pendingState, claim.nextState) &&
                preferences[claimTokenKey(carId)] == claim.token
            ) {
                writeState(preferences, carId, claim.nextState)
                preferences[lastCheckedKey(carId)] = now
                clearPendingState(preferences, carId)
            }
        }
    }

    internal suspend fun releaseStateChange(carId: Int, claim: TpmsStateTransitionClaim) {
        context.tpmsDataStore.edit { preferences ->
            val claimedState = readState(preferences, carId, CLAIM_PREFIX)
            if (sameWarnings(claimedState, claim.nextState) &&
                preferences[claimTokenKey(carId)] == claim.token
            ) {
                clearClaimState(preferences, carId)
            }
        }
    }

    private fun readState(
        preferences: Preferences,
        carId: Int,
        prefix: String = ""
    ): TpmsState = TpmsState(
        warningFl = preferences[warningFlKey(carId, prefix)] ?: false,
        warningFr = preferences[warningFrKey(carId, prefix)] ?: false,
        warningRl = preferences[warningRlKey(carId, prefix)] ?: false,
        warningRr = preferences[warningRrKey(carId, prefix)] ?: false,
        lastCheckedAt = if (prefix.isEmpty()) preferences[lastCheckedKey(carId)] ?: 0L else 0L
    )

    private fun writeState(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        carId: Int,
        state: TpmsState,
        prefix: String = ""
    ) {
        preferences[warningFlKey(carId, prefix)] = state.warningFl
        preferences[warningFrKey(carId, prefix)] = state.warningFr
        preferences[warningRlKey(carId, prefix)] = state.warningRl
        preferences[warningRrKey(carId, prefix)] = state.warningRr
    }

    private fun clearClaimState(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        carId: Int
    ) {
        preferences.remove(warningFlKey(carId, CLAIM_PREFIX))
        preferences.remove(warningFrKey(carId, CLAIM_PREFIX))
        preferences.remove(warningRlKey(carId, CLAIM_PREFIX))
        preferences.remove(warningRrKey(carId, CLAIM_PREFIX))
        preferences.remove(claimStartedAtKey(carId))
        preferences.remove(claimTokenKey(carId))
    }

    private fun clearPendingState(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        carId: Int
    ) {
        preferences.remove(warningFlKey(carId, PENDING_PREFIX))
        preferences.remove(warningFrKey(carId, PENDING_PREFIX))
        preferences.remove(warningRlKey(carId, PENDING_PREFIX))
        preferences.remove(warningRrKey(carId, PENDING_PREFIX))
        clearClaimState(preferences, carId)
    }

    private fun sameWarnings(left: TpmsState?, right: TpmsState): Boolean =
        left != null && left.warningFl == right.warningFl &&
            left.warningFr == right.warningFr &&
            left.warningRl == right.warningRl &&
            left.warningRr == right.warningRr

    /**
     * Clear all TPMS states (for all cars).
     */
    suspend fun clearAllStates() {
        context.tpmsDataStore.edit { preferences ->
            preferences.clear()
        }
    }

    private companion object {
        const val PENDING_PREFIX = "pending_"
        const val CLAIM_PREFIX = "claim_"
        const val CLAIM_LEASE_MS = 5 * 60 * 1_000L
    }
}

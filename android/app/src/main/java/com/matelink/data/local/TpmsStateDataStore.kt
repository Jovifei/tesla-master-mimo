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
class TpmsStateDataStore internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.tpmsDataStore)
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
        return dataStore.data.map { preferences ->
            readState(preferences, carId)
        }.first()
    }

    /**
     * Save the TPMS state for a specific car.
     */
    suspend fun saveState(carId: Int, state: TpmsState) {
        dataStore.edit { preferences ->
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
    ): TpmsStateDataStoreClaimResult = claimPartialStateChange(
        carId, nextState.warningFl, nextState.warningFr,
        nextState.warningRl, nextState.warningRr, now
    )

    /** Merge only observed wheels inside the same transaction that claims a transition. */
    internal suspend fun claimPartialStateChange(
        carId: Int,
        warningFl: Boolean?,
        warningFr: Boolean?,
        warningRl: Boolean?,
        warningRr: Boolean?,
        executionNow: Long,
        qualifiedReceiptTimes: Map<TirePosition,Long> = emptyMap()
    ): TpmsStateDataStoreClaimResult {
        if (warningFl == null && warningFr == null && warningRl == null && warningRr == null) {
            return TpmsStateDataStoreClaimResult.NoTransition
        }
        val now = executionNow
        var result: TpmsStateDataStoreClaimResult = TpmsStateDataStoreClaimResult.NoTransition
        dataStore.edit { preferences ->
            val previousState = readState(preferences, carId)
            // Unknown wheels retain the pending desired snapshot too. An unrelated
            // false wheel must not erase a warning awaiting notification delivery.
            var base = if (preferences.contains(warningFlKey(carId, PENDING_PREFIX))) {
                readState(preferences, carId, PENDING_PREFIX)
            } else previousState
            if (qualifiedReceiptTimes.isNotEmpty()) {
                // A new field is not evidence that an unrelated old pending
                // wheel is still fresh. Only its own durable receipt qualifies.
                fun fresh(wheel:TirePosition)=validReceiptTime(preferences[pendingReceiptKey(carId,wheel)]?:0L,now)
                base=base.copy(
                    warningFl=base.warningFl.takeIf { fresh(TirePosition.FL) } ?: previousState.warningFl,
                    warningFr=base.warningFr.takeIf { fresh(TirePosition.FR) } ?: previousState.warningFr,
                    warningRl=base.warningRl.takeIf { fresh(TirePosition.RL) } ?: previousState.warningRl,
                    warningRr=base.warningRr.takeIf { fresh(TirePosition.RR) } ?: previousState.warningRr)
            }
            val desiredState = base.copy(
                warningFl = warningFl ?: base.warningFl,
                warningFr = warningFr ?: base.warningFr,
                warningRl = warningRl ?: base.warningRl,
                warningRr = warningRr ?: base.warningRr,
                lastCheckedAt = 0L
            )
            val pendingState = readState(preferences, carId, PENDING_PREFIX)

            if (sameWarnings(previousState, desiredState)) {
                clearPendingState(preferences, carId)
                return@edit
            }
            if (!sameWarnings(pendingState, desiredState)) {
                clearClaimState(preferences, carId)
                writeState(preferences, carId, desiredState, PENDING_PREFIX)
            }
            qualifiedReceiptTimes.forEach { (wheel,at) ->
                if(validReceiptTime(at,now)) preferences[pendingReceiptKey(carId,wheel)] = at
            }

            val claimedState = readState(preferences, carId, CLAIM_PREFIX)
            val startedAt = preferences[claimStartedAtKey(carId)] ?: 0L
            val claimIsLive = sameWarnings(claimedState, desiredState) &&
                !preferences[claimTokenKey(carId)].isNullOrBlank() &&
                liveLease(startedAt, now)
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

    internal suspend fun savePartialState(
        carId: Int,
        warningFl: Boolean?,
        warningFr: Boolean?,
        warningRl: Boolean?,
        warningRr: Boolean?,
        executionNow: Long
    ) {
        if (warningFl == null && warningFr == null && warningRl == null && warningRr == null) return
        dataStore.edit { preferences ->
            val previous = readState(preferences, carId)
            val merged = previous.copy(
                warningFl = warningFl ?: previous.warningFl,
                warningFr = warningFr ?: previous.warningFr,
                warningRl = warningRl ?: previous.warningRl,
                warningRr = warningRr ?: previous.warningRr,
                lastCheckedAt = executionNow
            )
            writeState(preferences, carId, merged)
            preferences[lastCheckedKey(carId)] = executionNow
        }
    }

    internal suspend fun commitStateChange(carId: Int, claim: TpmsStateTransitionClaim, now: Long) {
        dataStore.edit { preferences ->
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
        dataStore.edit { preferences ->
            val claimedState = readState(preferences, carId, CLAIM_PREFIX)
            if (sameWarnings(claimedState, claim.nextState) &&
                preferences[claimTokenKey(carId)] == claim.token
            ) {
                clearClaimState(preferences, carId)
            }
        }
    }

    internal suspend fun isClaimCurrent(carId:Int,token:String,nextState:TpmsState):Boolean {
        val p=dataStore.data.first()
        return token.isNotBlank() && p[claimTokenKey(carId)]==token &&
            sameWarnings(readState(p,carId,CLAIM_PREFIX),nextState) &&
            sameWarnings(readState(p,carId,PENDING_PREFIX),nextState)
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
        TirePosition.entries.forEach { preferences.remove(pendingReceiptKey(carId,it)) }
    }

    private fun pendingReceiptKey(carId:Int,wheel:TirePosition)=longPreferencesKey("pending_tpms_receipt_${carId}_${wheel.name}")

    private fun sameWarnings(left: TpmsState?, right: TpmsState): Boolean =
        left != null && left.warningFl == right.warningFl &&
            left.warningFr == right.warningFr &&
            left.warningRl == right.warningRl &&
            left.warningRr == right.warningRr

    /**
     * Clear all TPMS states (for all cars).
     */
    suspend fun clearAllStates() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    private companion object {
        const val PENDING_PREFIX = "pending_"
        const val CLAIM_PREFIX = "claim_"
        const val CLAIM_LEASE_MS = 5 * 60 * 1_000L
    }
}

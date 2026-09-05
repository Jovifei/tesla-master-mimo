package com.matelink.data.repository

import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.local.TirePosition
import com.matelink.data.local.TpmsState
import com.matelink.data.local.TpmsStateDataStore
import com.matelink.data.local.HistoryCarIdResolver
import com.matelink.data.local.LegacyHistoryCarIdResolver
import javax.inject.Inject
import javax.inject.Singleton

sealed class TpmsStateChange {
    data class WarningStarted(val tires: List<TirePosition>) : TpmsStateChange()
    data object WarningCleared : TpmsStateChange()
}

data class TpmsStateChangeClaim(
    val change: TpmsStateChange,
    val nextState: TpmsState,
    val token: String = ""
)

sealed interface TpmsStateChangeClaimResult {
    data object NoTransition : TpmsStateChangeClaimResult
    data class Claimed(val claim: TpmsStateChangeClaim) : TpmsStateChangeClaimResult
    data object InFlight : TpmsStateChangeClaimResult
}

@Singleton
class TpmsStateRepository @Inject constructor(
    private val tpmsStateDataStore: TpmsStateDataStore,
    private val vehicleContextRepository: HistoryCarIdResolver
) {
    constructor(tpmsStateDataStore: TpmsStateDataStore) : this(tpmsStateDataStore, LegacyHistoryCarIdResolver)
    private suspend fun historyId(remoteApiCarId: Int) =
        vehicleContextRepository.requireLocalHistoryCarId(remoteApiCarId)

    suspend fun detectStateChange(carId: Int, currentTpms: TpmsDetails?): TpmsStateChange? {
        return detectStateChangeForHistoryCarId(historyId(carId), currentTpms)
    }

    suspend fun detectStateChangeForHistoryCarId(
        historyCarId: Int,
        currentTpms: TpmsDetails?
    ): TpmsStateChange? {
        if (currentTpms == null || !currentTpms.hasCompleteSoftWarningFields()) return null
        return tpmsStateChange(tpmsStateDataStore.getState(historyCarId), currentTpms.toTpmsState())
    }

    suspend fun claimStateChange(
        carId: Int,
        currentTpms: TpmsDetails?,
        now: Long = System.currentTimeMillis()
    ): TpmsStateChangeClaimResult = claimStateChangeForHistoryCarId(
        historyCarId = historyId(carId),
        currentTpms = currentTpms,
        now = now
    )

    suspend fun claimStateChangeForHistoryCarId(
        historyCarId: Int,
        currentTpms: TpmsDetails?,
        now: Long = System.currentTimeMillis()
    ): TpmsStateChangeClaimResult {
        if (currentTpms == null || !currentTpms.hasCompleteSoftWarningFields()) {
            return TpmsStateChangeClaimResult.NoTransition
        }
        return when (val result = tpmsStateDataStore.claimStateChange(historyCarId, currentTpms.toTpmsState(), now)) {
            TpmsStateDataStore.TpmsStateDataStoreClaimResult.NoTransition ->
                TpmsStateChangeClaimResult.NoTransition
            TpmsStateDataStore.TpmsStateDataStoreClaimResult.InFlight ->
                TpmsStateChangeClaimResult.InFlight
            is TpmsStateDataStore.TpmsStateDataStoreClaimResult.Claimed -> {
                val change = tpmsStateChange(result.claim.previousState, result.claim.nextState)
                    ?: return TpmsStateChangeClaimResult.NoTransition
                TpmsStateChangeClaimResult.Claimed(
                    TpmsStateChangeClaim(change, result.claim.nextState, result.claim.token)
                )
            }
        }
    }

    suspend fun commitStateChange(carId: Int, claim: TpmsStateChangeClaim) {
        commitStateChangeForHistoryCarId(historyId(carId), claim)
    }

    suspend fun commitStateChangeForHistoryCarId(historyCarId: Int, claim: TpmsStateChangeClaim) {
        tpmsStateDataStore.commitStateChange(
            historyCarId,
            TpmsStateDataStore.TpmsStateTransitionClaim(
                previousState = TpmsState(),
                nextState = claim.nextState,
                token = claim.token
            ),
            System.currentTimeMillis()
        )
    }

    suspend fun releaseStateChange(carId: Int, claim: TpmsStateChangeClaim) {
        releaseStateChangeForHistoryCarId(historyId(carId), claim)
    }

    suspend fun releaseStateChangeForHistoryCarId(historyCarId: Int, claim: TpmsStateChangeClaim) {
        tpmsStateDataStore.releaseStateChange(
            historyCarId,
            TpmsStateDataStore.TpmsStateTransitionClaim(
                previousState = TpmsState(),
                nextState = claim.nextState,
                token = claim.token
            )
        )
    }

    suspend fun updateState(carId: Int, tpms: TpmsDetails?) {
        updateStateForHistoryCarId(historyId(carId), tpms)
    }

    suspend fun updateStateForHistoryCarId(historyCarId: Int, tpms: TpmsDetails?) {
        if (tpms == null || !tpms.hasCompleteSoftWarningFields()) return
        tpmsStateDataStore.saveState(historyCarId, tpms.toTpmsState().copy(lastCheckedAt = System.currentTimeMillis()))
    }

    suspend fun getState(carId: Int): TpmsState = tpmsStateDataStore.getState(historyId(carId))

    suspend fun clearAllStates() = tpmsStateDataStore.clearAllStates()

    suspend fun simulateWarning(carId: Int, tire: TirePosition) {
        tpmsStateDataStore.saveState(
            historyId(carId),
            TpmsState(
                warningFl = tire == TirePosition.FL,
                warningFr = tire == TirePosition.FR,
                warningRl = tire == TirePosition.RL,
                warningRr = tire == TirePosition.RR,
                lastCheckedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearWarning(carId: Int) {
        tpmsStateDataStore.saveState(historyId(carId), TpmsState(lastCheckedAt = System.currentTimeMillis()))
    }
}

private fun TpmsDetails.hasCompleteSoftWarningFields(): Boolean =
    warningFl != null && warningFr != null && warningRl != null && warningRr != null

internal fun tpmsStateChange(previous: TpmsState, current: TpmsState): TpmsStateChange? {
    return when {
        !previous.hasAnyWarning && current.hasAnyWarning ->
            TpmsStateChange.WarningStarted(current.getWarningTires())
        previous.hasAnyWarning && !current.hasAnyWarning ->
            TpmsStateChange.WarningCleared
        previous.hasAnyWarning && current.hasAnyWarning &&
            previous.getWarningTires() != current.getWarningTires() ->
            TpmsStateChange.WarningStarted(current.getWarningTires())
        else -> null
    }
}

private fun TpmsDetails.toTpmsState(): TpmsState = TpmsState(
    warningFl = warningFl == true,
    warningFr = warningFr == true,
    warningRl = warningRl == true,
    warningRr = warningRr == true
)

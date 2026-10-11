package com.matelink.data.repository

import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.local.TirePosition
import com.matelink.data.local.TpmsState
import com.matelink.data.local.TpmsStateDataStore
import com.matelink.data.local.HistoryCarIdResolver
import com.matelink.data.local.LegacyHistoryCarIdResolver
import com.matelink.data.sync.verifiedTpmsWarnings
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
        if (currentTpms == null || !currentTpms.hasAnySoftWarningField()) return null
        val previous = tpmsStateDataStore.getState(historyCarId)
        return tpmsStateChange(previous, currentTpms.mergeWarnings(previous))
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
        if (currentTpms == null || !currentTpms.hasAnySoftWarningField()) {
            return TpmsStateChangeClaimResult.NoTransition
        }
        val qualifiedReceiptTimes = buildMap {
            verifiedTpmsWarnings(currentTpms, now).forEach { receipt ->
                val warnings = listOf(receipt.details.warningFl, receipt.details.warningFr,
                    receipt.details.warningRl, receipt.details.warningRr)
                TirePosition.entries.forEachIndexed { index, wheel ->
                    if (warnings[index] != null) put(wheel, receipt.observedAt)
                }
            }
        }
        // Qualified input may contain an independently stale wheel. Do not
        // promote that field using another wheel's valid receipt timestamp.
        fun supplied(wheel: TirePosition, value: Boolean?): Boolean? =
            value.takeIf { qualifiedReceiptTimes.isEmpty() || wheel in qualifiedReceiptTimes }
        return when (val result = tpmsStateDataStore.claimPartialStateChange(historyCarId,
            supplied(TirePosition.FL, currentTpms.warningFl), supplied(TirePosition.FR, currentTpms.warningFr),
            supplied(TirePosition.RL, currentTpms.warningRl), supplied(TirePosition.RR, currentTpms.warningRr),
            now, qualifiedReceiptTimes)) {
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

    suspend fun isClaimCurrentForHistoryCarId(historyCarId: Int, claim: TpmsStateChangeClaim): Boolean =
        tpmsStateDataStore.isClaimCurrent(historyCarId, claim.token, claim.nextState)

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
        if (tpms == null || !tpms.hasAnySoftWarningField()) return
        tpmsStateDataStore.savePartialState(historyCarId,
            tpms.warningFl, tpms.warningFr, tpms.warningRl, tpms.warningRr, System.currentTimeMillis())
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

private fun TpmsDetails.hasAnySoftWarningField(): Boolean =
    warningFl != null || warningFr != null || warningRl != null || warningRr != null

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

private fun TpmsDetails.mergeWarnings(previous: TpmsState): TpmsState = previous.copy(
    warningFl = warningFl ?: previous.warningFl,
    warningFr = warningFr ?: previous.warningFr,
    warningRl = warningRl ?: previous.warningRl,
    warningRr = warningRr ?: previous.warningRr
)

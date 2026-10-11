package com.matelink.data.repository

/** Both list and detail write the same verified namespace. False means do not publish a UI update. */
internal suspend fun saveVerifiedHistoryChargeCost(
    proof: VerifiedHistoryReadContext,
    chargeId: Int,
    amount: Double?,
    isCurrent: suspend (VerifiedHistoryReadContext) -> Boolean,
    save: suspend (Int, Int, Double?) -> Unit
): Boolean {
    if (!isCurrent(proof)) return false
    save(proof.context.localHistoryCarId, chargeId, amount)
    // An account switch during storage must not paint the prior account's result into the new UI.
    return isCurrent(proof)
}

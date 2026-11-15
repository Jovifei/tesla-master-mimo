package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData

/** A processed row or a false legacy DC flag is not evidence of AC. */
internal fun qualifiedChargeMode(
    charge: ChargeData,
    legacyDc: Boolean = false,
    legacyAc: Boolean = false
): String? {
    if (charge.sourceRecordIdentity.conflicted) return null
    val contract = charge.energyContract
    if (contract != null) {
        if (contract.version != 1 || contract.chargeModeEvidence != "observed_boundary_modes_no_conflict" ||
            (charge.chargeType != null && charge.chargeType != contract.chargeMode)) return null
        return contract.chargeMode?.takeIf { it == "ac" || it == "dc" }
    }
    if (legacyDc && !legacyAc) return "dc"
    if (legacyAc && !legacyDc) return "ac"
    if (charge.chargeType?.equals("dc", ignoreCase = true) == true) return "dc"
    if (charge.chargeType?.equals("ac", ignoreCase = true) == true) return "ac"
    return null
}

package com.matelink.ui.screens.charges

import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.chargeCostInstant
import com.matelink.domain.analytics.manualChargeAmount
import com.matelink.domain.analytics.ChargeCostSource
import com.matelink.domain.analytics.EffectiveChargeCost
import com.matelink.domain.analytics.ChargeTariffEstimate

/** Display-only value from this exact historical import; never a qualified measurement. */
internal fun importedChargeReferenceEnergy(row: ChargeSummary, displayed: ChargeData, localCarId: Int): Double? {
    if (localCarId >= 0 || row.carId != localCarId || row.chargeId != displayed.chargeId ||
        row.qualityState != "incomplete" || row.qualityReason !in setOf("local_import_summary_only", "local_import_unverified")) return null
    val raw = row.apiEvidence?.let(HistorySummaryEvidenceCodec::decodeCharge) ?: return null
    if (raw.chargeId != row.chargeId || raw.source != "local_import" || displayed.source != raw.source ||
        raw.qualityState != "incomplete" || displayed.qualityState != "incomplete" ||
        raw.qualityReason != row.qualityReason || displayed.qualityReason != row.qualityReason ||
        raw.energyContract != null || displayed.energyContract != null ||
        raw.sourceRecordIdentity.conflicted || displayed.sourceRecordIdentity.conflicted ||
        raw.sourceRecordIdentity.value != displayed.sourceRecordIdentity.value ||
        raw.sourceInstanceId.orEmpty().trim() != displayed.sourceInstanceId.orEmpty().trim()) return null
    val start = chargeCostInstant(raw.startDate) ?: return null
    val end = chargeCostInstant(raw.endDate) ?: return null
    if (!end.isAfter(start) || start != chargeCostInstant(row.startDate) || end != chargeCostInstant(row.endDate) ||
        start != chargeCostInstant(displayed.startDate) || end != chargeCostInstant(displayed.endDate)) return null
    return raw.chargeEnergyAdded?.takeIf { it.isFinite() && it > 0.0 }
}

internal fun importedChargeReferenceEstimate(
    referenceEnergy: Double?, unitPrice: Double?, costsReady: Boolean,
    hasSavedManualAmount: Boolean, effective: EffectiveChargeCost,
    tariffEstimate: ChargeTariffEstimate? = null
): Double? {
    if (!costsReady || referenceEnergy == null || hasSavedManualAmount ||
        effective.source != ChargeCostSource.UNAVAILABLE || effective.cost != null) return null
    return (if (tariffEstimate != null) tariffEstimate.amount else manualChargeAmount(unitPrice, referenceEnergy))
        ?.takeIf { it.isFinite() && it >= 0.0 }
}

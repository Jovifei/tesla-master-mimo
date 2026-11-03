package com.matelink.ui.screens.charges

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.withDetailEvidence
import com.matelink.domain.analytics.ChargeCostSource
import com.matelink.domain.analytics.EffectiveChargeCost
import com.matelink.domain.analytics.ChargeTariffEstimate
import org.junit.Assert.*
import org.junit.Test

class ChargeHistoryReferenceTest {
    private val start = "2026-10-07T13:48:42Z"
    private val end = "2026-10-07T14:03:48Z"
    private fun imported(energy: Double? = 11.1) = ChargeData(
        chargeId = 25, startDate = start, endDate = end, chargeEnergyAdded = energy,
        source = "local_import", qualityState = "incomplete", qualityReason = "local_import_summary_only"
    )

    @Test fun originalImportValueIsReferenceOnlyAndNeverQualifiesForSummary() {
        val row = imported().toSyncSummary(-17)!!
        val shown = row.toAnalysisChargeData()
        assertNull(shown.batteryInputKwh)
        assertFalse(chargeEligibleForSummary(shown))
        assertEquals(11.1, importedChargeReferenceEnergy(row, shown, -17)!!, 0.0)
    }

    @Test fun detailSyncWithoutAUsableContractKeepsTheOriginalValueAsReference() {
        val row = imported().toSyncSummary(-17)!!
        val merged = row.withDetailEvidence(ChargeDetail(chargeId = 25,
            startDate = start, endDate = end, source = "local_import",
            qualityState = "incomplete", qualityReason = "local_import_summary_only",
            chargePoints = emptyList()))
        val shown = merged.toAnalysisChargeData()
        assertEquals(11.1, importedChargeReferenceEnergy(merged, shown, -17)!!, 0.0)
        assertNull(shown.batteryInputKwh)
        assertFalse(chargeEligibleForSummary(shown))
    }

    @Test fun referenceRequiresTheSameCarRecordSourceAndExactWindow() {
        val row = imported().toSyncSummary(-17)!!
        val shown = row.toAnalysisChargeData()
        assertNull(importedChargeReferenceEnergy(row, shown, -18))
        assertNull(importedChargeReferenceEnergy(row, shown.copy(chargeId = 26), -17))
        assertNull(importedChargeReferenceEnergy(row, shown.copy(source = "teslamate_archive"), -17))
        assertNull(importedChargeReferenceEnergy(row, shown.copy(startDate = "2026-10-07T13:48:43Z"), -17))
        assertNull(importedChargeReferenceEnergy(row, shown.copy(endDate = "2026-10-07T14:03:49Z"), -17))
        assertNull(importedChargeReferenceEnergy(row, shown.copy(startDate = "2026-10-07T13:48:42"), -17))
    }

    @Test fun referenceEstimateNeverOverridesKnownOrPendingManualCost() {
        val unavailable = EffectiveChargeCost(null, ChargeCostSource.UNAVAILABLE)
        val manual = EffectiveChargeCost(20.0, ChargeCostSource.MANUAL)
        assertEquals(7.77, importedChargeReferenceEstimate(11.1, 0.7, true, false, unavailable)!!, 1e-9)
        assertNull(importedChargeReferenceEstimate(11.1, 0.7, true, true, unavailable))
        assertNull(importedChargeReferenceEstimate(11.1, 0.7, false, false, unavailable))
        assertNull(importedChargeReferenceEstimate(11.1, 0.7, true, false, manual))
        assertNull(importedChargeReferenceEstimate(11.1, Double.NaN, true, false, unavailable))
        assertNull(importedChargeReferenceEstimate(11.1, 0.7, true, false, unavailable,
            ChargeTariffEstimate(null, "tariff_coverage_unavailable")))
    }

    @Test fun invalidOrUnqualifiedImportDoesNotInventAReference() {
        listOf(null, 0.0, -1.0).forEach { value ->
            val row = imported(value).toSyncSummary(-17)!!
            assertNull(importedChargeReferenceEnergy(row, row.toAnalysisChargeData(), -17))
        }
        val row = imported().toSyncSummary(-17)!!
        assertNull(importedChargeReferenceEnergy(row.copy(apiEvidence = null), row.toAnalysisChargeData(), -17))
        assertNull(importedChargeReferenceEnergy(row.copy(qualityState = "quarantined"), row.toAnalysisChargeData(), -17))
        val explicitUnknown = imported().copy(energyContract = EnergyContract(batteryInput = EnergyMetric(
            quality = "unknown", reason = "source_revoked"))).toSyncSummary(-17)!!
        assertNull(importedChargeReferenceEnergy(explicitUnknown,
            explicitUnknown.toAnalysisChargeData(), -17))
    }
}

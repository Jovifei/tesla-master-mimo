package com.matelink.data.repository

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.local.dao.persistChargeRowsWithoutEvidenceLoss
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.toRawAnalysisChargeData
import com.matelink.domain.analytics.withDetailEvidence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ChargeDetailLocalPersistenceTest {
    private val start = "2026-10-20T10:00:00Z"
    private val end = "2026-10-20T10:39:00Z"

    private fun summary() = ChargeData(
        chargeId = 64, startDate = start, endDate = end,
        source = "teslamate_archive", qualityState = "observed",
        chargeEnergyAdded = 24.0, durationMin = 39
    ).toSyncSummary(30)!!

    @Test fun sameSessionTeslaMateDetailSurvivesWeakListRefreshAndOfflineRead() = runBlocking {
        val initial = summary()
        val originalReceipt = HistorySummaryEvidenceCodec.sourceJson(initial.apiEvidence)
        val rows = mutableMapOf((30 to 64) to initial)
        suspend fun save(incoming: ChargeSummary) = persistChargeRowsWithoutEvidenceLoss(
            listOf(incoming), { car, id -> rows[car to id] },
            { row -> rows[row.carId to row.chargeId] = row }
        )
        save(initial.withDetailEvidence(ChargeDetail(
            chargeId = 64, startDate = start, endDate = end,
            source = "teslamate_archive", chargeEnergyAdded = 24.02,
            durationMin = 39, cost = 27.38
        )))
        assertEquals(24.02, rows.getValue(30 to 64).toAnalysisChargeData().batteryInputKwh!!, 0.001)
        assertEquals(originalReceipt, HistorySummaryEvidenceCodec.sourceJson(rows.getValue(30 to 64).apiEvidence))

        val weak = ChargeData(64, startDate = start, endDate = end,
            source = "teslamate_archive", qualityState = "observed",
            chargeEnergyAdded = null).toSyncSummary(30)!!
        save(weak)
        val restored = rows.getValue(30 to 64).toAnalysisChargeData()
        assertEquals(24.02, restored.batteryInputKwh!!, 0.001)
        assertEquals(27.38, restored.cost!!, 0.001)
        assertEquals(originalReceipt, HistorySummaryEvidenceCodec.sourceJson(rows.getValue(30 to 64).apiEvidence))
        assertNull(rows.getValue(30 to 64).copy(carId = 31).toAnalysisChargeData().batteryInputKwh)
    }

    @Test fun mismatchedSourceOrWindowCannotAttachDetailToExistingChargeId() {
        val initial = summary()
        listOf(
            ChargeDetail(64, startDate = start, endDate = end,
                source = "telemetry_mqtt", chargeEnergyAdded = 99.0),
            ChargeDetail(64, startDate = start, endDate = "2026-10-20T10:40:00Z",
                source = "teslamate_archive", chargeEnergyAdded = 99.0)
        ).forEach { foreign ->
            try {
                initial.withDetailEvidence(foreign)
                fail("different source or window must not attach detail")
            } catch (_: IllegalArgumentException) {
                // This is a scope rejection, not a read failure.
            }
        }
        assertEquals(24.0, initial.toAnalysisChargeData().batteryInputKwh!!, 0.001)
    }

    @Test fun explicitUnknownContractRevokesOlderLegacyDetailValue() {
        val initial = summary()
        val detailed = initial.withDetailEvidence(ChargeDetail(
            64, startDate = start, endDate = end,
            source = "teslamate_archive", chargeEnergyAdded = 24.02
        ))
        val unknown = ChargeData(64, startDate = start, endDate = end,
            source = "teslamate_archive", qualityState = "observed",
            energyContract = EnergyContract(batteryInput = EnergyMetric(
                source = "teslamate_archive", quality = "unknown",
                method = "session_counter_delta", measurementPoint = "battery_input",
                startDate = start, endDate = end
            ))
        ).toSyncSummary(30)!!
        val revoked = mergeStoredCharge(unknown, detailed)
        assertNull(revoked.toAnalysisChargeData().batteryInputKwh)
        assertEquals("unknown", revoked.toRawAnalysisChargeData().energyContract?.batteryInput?.quality)
    }

    @Test fun equivalentInstantFormattingDoesNotDiscardScopedDetail() {
        val initial = summary()
        val detailed = initial.withDetailEvidence(ChargeDetail(
            64, startDate = start, endDate = end,
            source = "teslamate_archive", chargeEnergyAdded = 24.02
        ))
        val weak = ChargeData(64,
            startDate = "2026-10-20T18:00:00+08:00",
            endDate = "2026-10-20T18:39:00+08:00",
            source = "teslamate_archive", qualityState = "observed",
            chargeEnergyAdded = null
        ).toSyncSummary(30)!!
        val restored = mergeStoredCharge(weak, detailed)
        assertEquals(24.02, restored.toAnalysisChargeData().batteryInputKwh!!, 0.001)
    }

    @Test fun changedReportedScalarRequiresFreshDetailProof() {
        val initial = summary()
        val detailed = initial.withDetailEvidence(ChargeDetail(
            64, startDate = start, endDate = end,
            source = "teslamate_archive", chargeEnergyAdded = 24.02
        ))
        val corrected = ChargeData(64, startDate = start, endDate = end,
            source = "teslamate_archive", qualityState = "observed",
            chargeEnergyAdded = 25.0).toSyncSummary(30)!!
        val merged = mergeStoredCharge(corrected, detailed)
        assertEquals(25.0, merged.toAnalysisChargeData().batteryInputKwh!!, 0.001)
        val redetailed = merged.withDetailEvidence(ChargeDetail(
            64, startDate = start, endDate = end,
            source = "teslamate_archive", chargeEnergyAdded = 25.03
        ))
        assertEquals(25.03, redetailed.toAnalysisChargeData().batteryInputKwh!!, 0.001)
    }

    @Test fun correctedReportedZeroRemainsARealValue() {
        val detailed = summary().withDetailEvidence(ChargeDetail(
            64, startDate = start, endDate = end,
            source = "teslamate_archive", chargeEnergyAdded = 24.02
        ))
        val zero = ChargeData(64, startDate = start, endDate = end,
            source = "teslamate_archive", qualityState = "observed",
            chargeEnergyAdded = 0.0).toSyncSummary(30)!!
        assertEquals(0.0, mergeStoredCharge(zero, detailed)
            .toAnalysisChargeData().batteryInputKwh!!, 0.0)
    }

    @Test fun malformedLaterDetailDoesNotErasePriorScopedFleetMeasurement() {
        val initial = ChargeData(64, startDate = start, endDate = end,
            source = "telemetry_mqtt", qualityState = "observed")
            .toSyncSummary(30)!!
        fun metric(value: Double?, quality: String, purpose: String) = EnergyMetric(
            valueKwh = value, source = "telemetry_mqtt", quality = quality,
            method = "session_counter_delta", measurementPoint = purpose,
            coverageKind = "endpoints", coverageRatio = 1.0,
            startDate = start, endDate = end
        )
        val strong = initial.withDetailEvidence(ChargeDetail(
            64, startDate = start, endDate = end, source = "telemetry_mqtt",
            energyContract = EnergyContract(batteryInput = metric(2.0, "reported", "battery_input"))
        ))
        val malformed = strong.withDetailEvidence(ChargeDetail(
            64, startDate = start, endDate = end, source = "telemetry_mqtt",
            chargeEnergyAdded = 99.0,
            energyContract = EnergyContract(batteryInput = metric(99.0, "reported", "ac_charger_input"))
        ))
        assertEquals(2.0, malformed.toAnalysisChargeData().batteryInputKwh!!, 0.0)
        val revoked = malformed.withDetailEvidence(ChargeDetail(
            64, startDate = start, endDate = end, source = "telemetry_mqtt",
            energyContract = EnergyContract(batteryInput = metric(null, "unknown", "battery_input"))
        ))
        assertNull(revoked.toAnalysisChargeData().batteryInputKwh)
    }

    @Test fun sourceUntaggedLegacyReceiptCanAdmitExactTeslaMateDetailWithoutRewritingRaw() {
        val legacy = ChargeData(64, startDate = start, endDate = end,
            qualityState = "observed", chargeEnergyAdded = 24.0).toSyncSummary(30)!!
        val original = HistorySummaryEvidenceCodec.sourceJson(legacy.apiEvidence)
        val saved = legacy.withDetailEvidence(ChargeDetail(64,
            startDate = "2026-10-20T18:00:00+08:00",
            endDate = "2026-10-20T18:39:00+08:00",
            source = "teslamate_archive", chargeEnergyAdded = 24.02))
        assertEquals(24.02, saved.toAnalysisChargeData().batteryInputKwh!!, 0.001)
        assertEquals(original, HistorySummaryEvidenceCodec.sourceJson(saved.apiEvidence))
        assertNull(saved.toRawAnalysisChargeData().source)
        assertNull(saved.copy(carId = 31).toAnalysisChargeData().batteryInputKwh)
    }

    @Test fun untaggedReceiptDoesNotPermitFleetOrMissingRawOrDifferentWindow() {
        val legacy = ChargeData(64, startDate = start, endDate = end,
            qualityState = "observed").toSyncSummary(30)!!
        val details = listOf(
            legacy to ChargeDetail(64, startDate = start, endDate = end,
                source = "telemetry_mqtt", chargeEnergyAdded = 99.0),
            legacy.copy(apiEvidence = null) to ChargeDetail(64, startDate = start,
                endDate = end, source = "teslamate_archive", chargeEnergyAdded = 99.0),
            legacy to ChargeDetail(64, startDate = start,
                endDate = "2026-10-20T10:40:00Z", source = "teslamate_archive",
                chargeEnergyAdded = 99.0)
        )
        details.forEach { (row, detail) ->
            try {
                row.withDetailEvidence(detail)
                fail("unverified legacy detail must not attach")
            } catch (_: IllegalArgumentException) { }
        }
    }
}

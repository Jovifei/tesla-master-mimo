package com.matelink.data.repository

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargesResponse
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.AnalysisChargeCoverageSample
import com.matelink.domain.analytics.buildAnalysisCoverage
import com.matelink.domain.model.YearFilter
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.Instant

class StatsChargeCostProjectionTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun yearUsesLocalDayScopedManualTotalsAndOnlySameSessionEnergyForUnitCost() {
        val charges = listOf(
            charge(1, "2025-12-31T16:30:00Z", 10.0, 9.0),
            charge(2, "2026-06-01T10:00:00Z", 5.0, 10.0),
            charge(3, "2026-07-01T10:00:00Z", 100.0, 7.0, source = "fleet_mqtt"),
            charge(4, "2026-08-01T10:00:00Z", 10.0, null),
            charge(5, "2026-12-31T16:30:00Z", 4.0, 4.0),
            charge(6, "bad-date", 3.0, 100.0)
        )
        val projection = projectStatsChargeCosts(
            charges, localHistoryCarId = 700,
            manualTotals = mapOf("700:1" to 0.0, "701:2" to 999.0, "700:2" to -4.0),
            filter = YearFilter.Year(2026), zone = zone
        )

        assertEquals(listOf(1, 2, 3, 4), projection.charges.map { it.chargeId })
        assertEquals(17.0, projection.totals.knownTotal!!, 0.0001)
        assertEquals(3, projection.totals.pricedCount)
        assertEquals(2, projection.totals.pairedCount)
        assertEquals(10.0 / 15.0, projection.totals.pairedUnitCost!!, 0.0001)
        assertEquals(0.0, projection.effectiveCosts[1]!!, 0.0001)
        assertEquals(10.0, projection.effectiveCosts[2]!!, 0.0001)
        assertNull(projection.effectiveCosts[4])
        assertFalse(5 in projection.effectiveCosts)
        val coverage = buildAnalysisCoverage(emptyList(), projection.charges.map {
            AnalysisChargeCoverageSample(it.batteryInputKwh, projection.effectiveCosts[it.chargeId], it.startDate)
        })
        assertEquals(3, coverage.chargeCostSampleCount)
        assertEquals(3, coverage.chargeEnergySampleCount)
    }

    @Test fun allTimeWithNoKnownPriceRemainsUnknownRatherThanZero() {
        val projection = projectStatsChargeCosts(
            charges = listOf(charge(1, "2026-10-20T10:00:00Z", 0.0, null)),
            localHistoryCarId = 700, manualTotals = emptyMap(),
            filter = YearFilter.AllTime, zone = zone
        )

        assertNull(projection.totals.knownTotal)
        assertNull(projection.totals.pairedUnitCost)
        assertEquals(0.0, projection.totals.qualifiedBatteryTotal!!, 0.0001)
    }

    @Test fun actualReceiptAfterRoomProjectionUsesManualZeroAndRejectsUnqualifiedFleetEnergy() {
        val receipt = Moshi.Builder().build().adapter(ChargesResponse::class.java).fromJson("""{
            "data":{"charges":[
                {"charge_id":11,"source":"teslamate_archive","quality_state":"observed",
                 "start_date":"2026-10-20T10:00:00Z","end_date":"2026-10-20T11:00:00Z",
                 "charge_energy_added":5.0,"cost":10.0},
                {"charge_id":12,"source":"fleet_mqtt","quality_state":"observed",
                 "start_date":"2026-10-20T12:00:00Z","end_date":"2026-10-20T13:00:00Z",
                 "charge_energy_added":100.0,"cost":7.0}
            ]}}
        """)!!.data!!.charges!!
        val restored = receipt.map { it.toSyncSummary(700)!!.toAnalysisChargeData() }
        val projection = projectStatsChargeCosts(restored, 700, mapOf("700:11" to 0.0),
            YearFilter.AllTime, zone)

        assertEquals(10.0, receipt[0].cost!!, 0.0001)
        assertEquals(7.0, projection.totals.knownTotal!!, 0.0001)
        assertEquals(0.0, projection.totals.pairedUnitCost!!, 0.0001)
        assertEquals(5.0, projection.totals.qualifiedBatteryTotal!!, 0.0001)
        assertEquals(0.0, projection.effectiveCosts[11]!!, 0.0001)
        assertNull(restored[1].batteryInputKwh)
    }

    private fun charge(id: Int, start: String, energy: Double?, cost: Double?,
                       source: String = "teslamate_archive") = ChargeData(
        chargeId = id, startDate = start,
        endDate = runCatching { Instant.parse(start).plusSeconds(3600).toString() }.getOrNull(),
        chargeEnergyAdded = energy, cost = cost, source = source, qualityState = "observed"
    )
}

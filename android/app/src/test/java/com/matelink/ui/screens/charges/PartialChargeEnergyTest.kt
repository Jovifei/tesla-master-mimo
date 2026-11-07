package com.matelink.ui.screens.charges

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.api.models.ChargesResponse
import com.squareup.moshi.Moshi
import com.matelink.data.local.entity.ChargeCostOverride
import com.matelink.data.local.AppSettings
import com.matelink.domain.analytics.ChargeCostSource
import com.matelink.domain.analytics.chargeCostSessionKey
import com.matelink.domain.analytics.projectEffectiveChargeCost
import com.matelink.domain.analytics.toCostChargeData
import com.matelink.domain.analytics.estimateChargeTariff
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PartialChargeEnergyTest {
    private val start = "2026-01-01T00:00:00Z"
    private val end = "2026-01-01T01:00:00Z"
    private val metric = EnergyMetric(
        method = "session_counter_delta", measurementPoint = "battery_input",
        source = "telemetry_mqtt", sourceField = "DCChargingEnergyIn",
        quality = "unknown", reason = "partial_counter_window",
        startDate = start, endDate = end,
        observedStartAt = "2026-01-01T00:05:00Z", observedEndAt = "2026-01-01T00:55:00Z",
        timeBasis = "collector_received_at", coverageKind = "endpoints",
        coverageRatio = 5.0 / 6.0, coveredEnergyKwh = 10.0
    )

    private fun charge(metric: EnergyMetric = this.metric) = ChargeData(
        chargeId = 7, startDate = start, endDate = end, source = "telemetry_mqtt",
        qualityState = "incomplete", qualityReason = "partial_or_missing_charge_counter",
        energyContract = EnergyContract(batteryInput = metric)
    )

    @Test fun partialEvidenceStaysSeparateFromWholeSessionButSupportsEstimates() {
        val data = charge()
        assertNull(data.batteryInputKwh)
        assertEquals(10.0, data.partialBatteryInputKwh!!, 0.0)
        assertTrue(chargeEligibleForSummary(data))
        val cost = projectEffectiveChargeCost(data, -1, null, "CNY", defaultPricePerKwh = 1.5)
        assertEquals(ChargeCostSource.ESTIMATE, cost.source)
        assertEquals(15.0, cost.cost!!, 0.0)
        assertEquals("estimate_partial_counter", cost.qualificationReason)
        val detail = ChargeDetail(chargeId = 7, startDate = start, endDate = end,
            source = data.source, energyContract = data.energyContract)
        assertEquals(10.0, detail.partialBatteryInputKwh!!, 0.0)
        assertEquals(10.0, detail.toCostChargeData().partialBatteryInputKwh!!, 0.0)
    }

    @Test fun verifiedManualAmountStillWins() {
        val data = charge()
        val manual = ChargeCostOverride(-1, data.chargeId, 23.0,
            sessionKey = chargeCostSessionKey(data), currencyCode = "CNY",
            sourceKind = data.source, startInstant = start, endInstant = end)
        val cost = projectEffectiveChargeCost(data, -1, manual, "CNY", defaultPricePerKwh = 1.5)
        assertEquals(ChargeCostSource.MANUAL, cost.source)
        assertEquals(23.0, cost.cost!!, 0.0)
    }

    @Test fun tariffEstimatesUseTheObservedWindowAndKeepEstimateStatus() {
        val data = charge()
        val default = estimateChargeTariff(data, AppSettings(tariffEnabled = false, defaultChargePrice = 1.5))
        assertEquals(15.0, default.amount!!, 0.0)
        val timed = estimateChargeTariff(data, AppSettings(), ZoneId.of("UTC"))
        assertEquals(3.0, timed.amount!!, 1e-9)
        assertEquals("estimate_partial_counter", timed.reason)
    }

    @Test fun conflictingOrUnprovenEvidenceNeverProducesPartialEnergy() {
        for (bad in listOf(
            metric.copy(reason = "counter_replay_or_reset"),
            metric.copy(source = "other"),
            metric.copy(sourceField = "ACChargingEnergyIn"),
            metric.copy(coverageRatio = 1.0),
            metric.copy(observedEndAt = "2026-01-01T02:00:00Z"),
            metric.copy(coveredEnergyKwh = Double.NaN),
            metric.copy(startDate = "2026-01-02T00:00:00Z")
        )) {
            assertNull(charge(bad).partialBatteryInputKwh)
            assertFalse(chargeEligibleForSummary(charge(bad)))
        }
        assertNull(charge().copy(sourceId = "one", sourceRecordId = "other").partialBatteryInputKwh)
        assertNull(charge().copy(source = "teslamate_archive").partialBatteryInputKwh)
    }

    @Test fun observedZeroRemainsAValidPartialCounterValue() {
        assertEquals(0.0, charge(metric.copy(coveredEnergyKwh = 0.0)).partialBatteryInputKwh!!, 0.0)
    }

    @Test fun oldServerSubmicrosecondWindowUsesTheSameDatabaseSession() {
        val old = metric.copy(endDate = "2026-01-01T01:00:00.0000007Z", coverageRatio = null)
        assertEquals(10.0, charge(old).partialBatteryInputKwh!!, 0.0)
        assertNull(charge(old.copy(endDate = "2026-01-01T01:00:00.000001Z")).partialBatteryInputKwh)
    }

    @Test fun serverWireFormatRetainsPartialEvidenceWithoutInventingWholeEnergy() {
        val json = """{"data":{"charges":[{"charge_id":7,"start_date":"$start","end_date":"$end",
            "source":"telemetry_mqtt","quality_state":"incomplete","quality_reason":"partial_or_missing_charge_counter",
            "charge_energy_added":null,"energy_contract":{"version":1,"battery_input":{
            "value_kwh":null,"unit":"kWh","method":"session_counter_delta","measurement_point":"battery_input",
            "source":"telemetry_mqtt","source_field":"DCChargingEnergyIn","quality":"unknown",
            "reason":"partial_counter_window","start_date":"$start","end_date":"$end",
            "observed_start_at":"2026-01-01T00:05:00Z","observed_end_at":"2026-01-01T00:55:00Z",
            "time_basis":"collector_received_at","coverage_kind":"endpoints",
            "coverage_ratio":0.8333333333333334,"covered_energy_kwh":10.0}}}]}}"""
        val parsed = requireNotNull(Moshi.Builder().build().adapter(ChargesResponse::class.java).fromJson(json))
            .data!!.charges!!.single()
        assertNull(parsed.batteryInputKwh)
        assertEquals(10.0, parsed.partialBatteryInputKwh!!, 0.0)
    }
}

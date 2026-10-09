package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.local.entity.ChargeSummary
import java.time.Instant

/** Loss is an AC-to-battery balance, not nominal stored-energy growth. */
data class ChargeEnergyBalance(val inputKwh: Double, val batteryKwh: Double, val lossKwh: Double, val efficiencyPercent: Double)

fun qualifiedAcEnergyBalance(contract: EnergyContract?, start: String?, end: String?, chargeType: String?): ChargeEnergyBalance? {
    if (contract?.version != 1 || chargeType != "ac" || contract.chargeMode != "ac" ||
        contract.chargeModeEvidence != "observed_boundary_modes_no_conflict") return null
    val input = contract.acInput ?: return null
    val battery = contract.batteryInput ?: return null
    if (input.method != "session_counter_delta" || battery.method != "session_counter_delta" ||
        input.measurementPoint != "ac_charger_input" || battery.measurementPoint != "battery_input" ||
        input.source != battery.source || input.timeBasis != battery.timeBasis || input.timeBasis.isNullOrBlank()) return null
    fun instant(value: String?) = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val from = instant(start) ?: return null
    val to = instant(end) ?: return null
    if (instant(input.observedStartAt) != from || instant(battery.observedStartAt) != from ||
        instant(input.observedEndAt) != to || instant(battery.observedEndAt) != to) return null
    val incoming = input.valueForWindow(start, end)?.takeIf { it > 0.0 } ?: return null
    val added = battery.valueForWindow(start, end)?.takeIf { it >= 0.0 && it <= incoming } ?: return null
    val loss = incoming - added
    val percent = added / incoming * 100.0
    if (!loss.isFinite() || !percent.isFinite()) return null
    return ChargeEnergyBalance(incoming, added, loss, percent)
}

fun ChargeData.withQualifiedEnergy(): ChargeData = copy(
    chargeEnergyAdded = batteryInputKwh, chargeEnergyUsed = inputEnergyKwh,
    cost = cost?.takeIf { it.isFinite() && it >= 0.0 }
)
fun ChargeDetail.withQualifiedEnergy(): ChargeDetail = copy(
    chargeEnergyAdded = batteryInputKwh, chargeEnergyUsed = inputEnergyKwh,
    cost = cost?.takeIf { it.isFinite() && it >= 0.0 }
)

fun ChargeSummary.withDetailEvidence(detail: ChargeDetail): ChargeSummary {
    require(detail.chargeId == chargeId) { "history_detail_id_mismatch" }
    val previous = toAnalysisChargeData()
    val rawPrevious = toRawAnalysisChargeData()
    require(previous.source == null || detail.source == null || previous.source == detail.source) { "history_detail_source_mismatch" }
    val evidence = previous.copy(
        startDate = detail.startDate ?: previous.startDate, endDate = detail.endDate ?: previous.endDate,
        address = detail.address ?: previous.address, durationMin = detail.durationMin ?: previous.durationMin,
        batteryDetails = detail.batteryDetails ?: previous.batteryDetails,
        outsideTempAvg = detail.outsideTempAvg ?: previous.outsideTempAvg,
        // The detail can be unknown while the stored API receipt still contains
        // an unverified scalar. Preserve the raw value only in apiEvidence;
        // the getters and analytic columns below keep it unavailable.
        chargeEnergyAdded = detail.batteryInputKwh ?: rawPrevious.chargeEnergyAdded,
        chargeEnergyUsed = detail.inputEnergyKwh ?: rawPrevious.chargeEnergyUsed,
        energyContract = detail.energyContract ?: rawPrevious.energyContract,
        chargeType = detail.chargeType ?: previous.chargeType,
        source = detail.source ?: previous.source,
        cost = detail.cost?.takeIf { it.isFinite() && it >= 0.0 } ?: previous.cost
    )
    return copy(
        startDate = evidence.startDate ?: startDate, endDate = evidence.endDate ?: endDate,
        address = evidence.address.orEmpty(), durationMin = evidence.durationMin ?: durationMin,
        energyAdded = evidence.batteryInputKwh ?: 0.0, energyUsed = evidence.inputEnergyKwh,
        cost = evidence.cost, startBatteryLevel = evidence.startBatteryLevel ?: 0,
        endBatteryLevel = evidence.endBatteryLevel ?: 0, outsideTempAvg = evidence.outsideTempAvg?.takeIf(Double::isFinite),
        apiEvidence = HistorySummaryEvidenceCodec.encode(evidence)
    )
}

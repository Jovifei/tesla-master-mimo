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
    val rawPrevious = toRawAnalysisChargeData()
    val currentDisplay = toAnalysisChargeData()
    require(rawPrevious.source == null || detail.source == null ||
        rawPrevious.source == detail.source) { "history_detail_source_mismatch" }
    val nextStart = detail.startDate ?: rawPrevious.startDate
    val nextEnd = detail.endDate ?: rawPrevious.endDate
    fun sameInstant(a: String?, b: String?): Boolean {
        val left = a?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val right = b?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return left != null && left == right
    }
    val sameWindow = sameInstant(nextStart, rawPrevious.startDate) &&
        sameInstant(nextEnd, rawPrevious.endDate)
    val previouslyProven = currentDisplay.energyContract?.takeIf {
        sameWindow && (detail.source == null || detail.source == rawPrevious.source) &&
            (currentDisplay.batteryInputKwh != null || currentDisplay.inputEnergyKwh != null)
    }
    // Explicit unknown/proven provider detail wins; absence alone never
    // downgrades a known same-source window. Neither is a raw scalar receipt.
    val priorDetail = HistorySummaryEvidenceCodec.detailContract(apiEvidence)
    val selectedContract = detail.energyContract ?:
        (if (sameWindow) priorDetail ?: previouslyProven else null)
    val sourceRecord = detail.source ?: rawPrevious.source
    val rawReceipt = if (detail.energyContract != null ||
        detail.chargeEnergyAdded != null || detail.chargeEnergyUsed != null ||
        (selectedContract != null &&
          HistorySummaryEvidenceCodec.detailContract(apiEvidence) != selectedContract)) {
        HistorySummaryEvidenceCodec.withChargeDetail(
            apiEvidence, rawPrevious,
            selectedContract ?: EnergyContract(), detail.chargeEnergyAdded,
            detail.chargeEnergyUsed, sourceRecord, nextStart, nextEnd
        )
    } else apiEvidence ?: HistorySummaryEvidenceCodec.encode(rawPrevious)
    val qualified = rawPrevious.copy(
        startDate = nextStart, endDate = nextEnd,
        energyContract = selectedContract ?: rawPrevious.energyContract
    ).withQualifiedEnergy()
    return copy(
        startDate = nextStart ?: startDate, endDate = nextEnd ?: endDate,
        address = detail.address ?: address, durationMin = detail.durationMin ?: durationMin,
        energyAdded = qualified.batteryInputKwh ?: 0.0,
        energyUsed = qualified.inputEnergyKwh,
        // Unverified cost remains in raw_json for provenance, never QuickStats.
        cost = (detail.cost ?: rawPrevious.cost)?.takeIf { it.isFinite() && it >= 0.0 },
        startBatteryLevel = detail.startBatteryLevel ?: startBatteryLevel,
        endBatteryLevel = detail.endBatteryLevel ?: endBatteryLevel,
        outsideTempAvg = detail.outsideTempAvg?.takeIf(Double::isFinite) ?: outsideTempAvg,
        apiEvidence = rawReceipt
    )
}

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
    val current = toAnalysisChargeData()
    require(rawPrevious.source == null || detail.source == null ||
        rawPrevious.source == detail.source) { "history_detail_source_mismatch" }
    val nextStart = detail.startDate ?: startDate
    val nextEnd = detail.endDate ?: endDate
    fun sameInstant(a: String?, b: String?): Boolean {
        val left = a?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val right = b?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return left != null && left == right
    }
    val sameWindow = sameInstant(nextStart, startDate) && sameInstant(nextEnd, endDate)
    val oldProven = current.energyContract?.takeIf {
        sameWindow && (detail.source == null || detail.source == rawPrevious.source) &&
            (current.batteryInputKwh != null || current.inputEnergyKwh != null)
    }
    val selectedProof = detail.energyContract ?: (
        if (sameWindow) HistorySummaryEvidenceCodec.detailContract(apiEvidence)
            ?: oldProven else null
    ) ?: EnergyContract()
    // Keep the raw charge scalar and opaque old fields separate from
    // current user-visible address/SOC/cost/odometer and new counters.
    val presentation = current.copy(
        startDate = nextStart, endDate = nextEnd,
        address = detail.address ?: current.address,
        durationMin = detail.durationMin ?: current.durationMin,
        durationStr = detail.durationStr ?: current.durationStr,
        batteryDetails = detail.batteryDetails ?: current.batteryDetails,
        rangeIdeal = detail.rangeIdeal ?: current.rangeIdeal,
        rangeRated = detail.rangeRated ?: current.rangeRated,
        outsideTempAvg = detail.outsideTempAvg ?: current.outsideTempAvg,
        odometer = detail.odometer ?: current.odometer,
        latitude = detail.latitude ?: current.latitude,
        longitude = detail.longitude ?: current.longitude,
        chargeType = detail.chargeType ?: current.chargeType,
        cost = (detail.cost ?: current.cost)?.takeIf { it.isFinite() && it >= 0.0 },
        source = detail.source ?: rawPrevious.source,
        chargeEnergyAdded = null, chargeEnergyUsed = null, energyContract = null
    )
    val receipt = HistorySummaryEvidenceCodec.withChargeDetail(
        apiEvidence, rawPrevious, selectedProof, detail.chargeEnergyAdded,
        detail.chargeEnergyUsed, presentation.source,
        nextStart, nextEnd, carId, presentation
    )
    val qualified = presentation.copy(energyContract = selectedProof).withQualifiedEnergy()
    return copy(
        startDate = nextStart ?: startDate, endDate = nextEnd ?: endDate,
        address = presentation.address.orEmpty(),
        durationMin = presentation.durationMin ?: durationMin,
        latitude = presentation.latitude?.takeIf(Double::isFinite) ?: latitude,
        longitude = presentation.longitude?.takeIf(Double::isFinite) ?: longitude,
        odometer = presentation.odometer?.takeIf(Double::isFinite) ?: odometer,
        energyAdded = qualified.batteryInputKwh ?: 0.0,
        energyUsed = qualified.inputEnergyKwh,
        cost = qualified.cost?.takeIf { it.isFinite() && it >= 0.0 },
        startBatteryLevel = presentation.startBatteryLevel ?: 0,
        endBatteryLevel = presentation.endBatteryLevel ?: 0,
        outsideTempAvg = presentation.outsideTempAvg?.takeIf(Double::isFinite),
        apiEvidence = receipt
    )
}

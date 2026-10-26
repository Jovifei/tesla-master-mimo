package com.matelink.domain.analytics

import com.matelink.data.api.models.SourceRecordIdentity
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.util.parseIsoInstant
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

private fun sameChargeInstant(first: String?, second: String?): Boolean {
    val expected = parseIsoInstant(first) ?: return false
    return expected == parseIsoInstant(second)
}

/** Optional wire identities are fences, never values inferred from a numeric charge ID.
 * Both absent retains the established legacy path; one absent cannot inherit known proof.
 */
internal fun sameChargeSourceInstance(
    firstInstance: String?, firstRecord: SourceRecordIdentity, secondInstance: String?, secondRecord: SourceRecordIdentity
): Boolean = !firstRecord.conflicted && !secondRecord.conflicted && firstInstance?.takeIf(String::isNotBlank) == secondInstance?.takeIf(String::isNotBlank) &&
    firstRecord.value == secondRecord.value

/** A numeric charge ID is only meaningful within the saved source and session window. */
internal fun ChargeSummary.acceptsDetail(detail: ChargeDetail): Boolean {
    val raw = toRawAnalysisChargeData()
    val legacyRaw = apiEvidence?.let(HistorySummaryEvidenceCodec::decodeCharge)
    val sameSource = raw.source != null && raw.source == detail.source
    val oldTeslaMateReceipt = raw.source == null && detail.source == "teslamate_archive" &&
        legacyRaw != null && legacyRaw.chargeId == chargeId && legacyRaw.source == null &&
        sameChargeInstant(legacyRaw.startDate, startDate) &&
        sameChargeInstant(legacyRaw.endDate, endDate)
    return chargeId == detail.chargeId && (sameSource || oldTeslaMateReceipt) &&
        sameChargeSourceInstance(raw.sourceInstanceId, raw.sourceRecordIdentity, detail.sourceInstanceId, detail.sourceRecordIdentity) &&
        sameChargeInstant(startDate, detail.startDate) && sameChargeInstant(endDate, detail.endDate) &&
        sameChargeInstant(raw.startDate, detail.startDate) && sameChargeInstant(raw.endDate, detail.endDate)
}

private fun EnergyContract.authoritativeForChargeDetail(source: String?, start: String?, end: String?): Boolean {
    if (version != 1 || source == null || (batteryInput == null && acInput == null)) return false
    fun EnergyMetric.validFor(point: String): Boolean {
        if (unit != "kWh" || method != "session_counter_delta" ||
            measurementPoint != point || this.source != source ||
            !sameChargeInstant(startDate, start) || !sameChargeInstant(endDate, end)) return false
        if (quality == "unknown") return valueKwh == null
        if (point == "ac_charger_input" &&
            (chargeMode != "ac" || chargeModeEvidence != "observed_boundary_modes_no_conflict")) return false
        return valueForWindow(start, end)?.let { it >= 0.0 } == true
    }
    return (batteryInput?.validFor("battery_input") != false) &&
        (acInput?.validFor("ac_charger_input") != false)
}

fun ChargeSummary.withDetailEvidence(detail: ChargeDetail): ChargeSummary {
    require(acceptsDetail(detail)) { "history_detail_scope_or_window_mismatch" }
    val rawPrevious = toRawAnalysisChargeData()
    val current = toAnalysisChargeData()
    val nextStart = detail.startDate ?: startDate
    val nextEnd = detail.endDate ?: endDate
    val sameWindow = sameChargeInstant(nextStart, startDate) && sameChargeInstant(nextEnd, endDate)
    val oldProven = current.energyContract?.takeIf {
        sameWindow && (detail.source == null || detail.source == rawPrevious.source) &&
            (current.batteryInputKwh != null || current.inputEnergyKwh != null)
    }
    val selectedProof = detail.energyContract?.takeIf {
        it.authoritativeForChargeDetail(rawPrevious.source, nextStart, nextEnd)
    } ?: (
        if (sameWindow) HistorySummaryEvidenceCodec.detailContract(apiEvidence)
            ?: oldProven else null
    ) ?: EnergyContract()
    // Keep the raw charge scalar and opaque old fields separate from
    // current user-visible address/SOC/cost/odometer and new counters.
    val sameLengthUnit = com.matelink.data.api.models.sameChargeLengthUnit(current.lengthUnit, detail.lengthUnit)
    val sameTemperatureUnit = com.matelink.data.api.models.sameHistoryTemperatureUnit(
        detail.temperatureUnit, detail.source, current.temperatureUnit, current.source)
    val presentation = current.copy(
        startDate = nextStart, endDate = nextEnd,
        address = detail.address ?: current.address,
        durationMin = detail.durationMin ?: current.durationMin,
        durationStr = detail.durationStr ?: current.durationStr,
        batteryDetails = detail.batteryDetails ?: current.batteryDetails,
        rangeIdeal = detail.rangeIdeal ?: current.rangeIdeal.takeIf { sameLengthUnit },
        rangeRated = detail.rangeRated ?: current.rangeRated.takeIf { sameLengthUnit },
        lengthUnit = detail.lengthUnit,
        outsideTempAvg = detail.outsideTempAvg ?: current.outsideTempAvg.takeIf { sameTemperatureUnit },
        temperatureUnit = detail.temperatureUnit,
        odometer = detail.odometer ?: current.odometer.takeIf { sameLengthUnit },
        latitude = detail.latitude ?: current.latitude,
        longitude = detail.longitude ?: current.longitude,
        chargeType = detail.chargeType ?: current.chargeType,
        cost = (detail.cost ?: current.cost)?.takeIf { it.isFinite() && it >= 0.0 },
        source = detail.source ?: rawPrevious.source,
        sourceInstanceId = detail.sourceInstanceId, sourceId = detail.sourceId, sourceRecordId = detail.sourceRecordId,
        chargeEnergyAdded = null, chargeEnergyUsed = null, energyContract = null
    )
    val receipt = HistorySummaryEvidenceCodec.withChargeDetail(
        apiEvidence, rawPrevious, selectedProof, detail.chargeEnergyAdded,
        detail.chargeEnergyUsed, presentation.source,
        nextStart, nextEnd, carId, presentation
    )
    val qualified = copy(
        startDate = nextStart, endDate = nextEnd, apiEvidence = receipt
    ).toAnalysisChargeData()
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

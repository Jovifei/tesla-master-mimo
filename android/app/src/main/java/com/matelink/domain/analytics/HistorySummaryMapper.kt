package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.squareup.moshi.Moshi
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.time.Instant

private fun String?.cleanAddress(): String? = this?.trim()?.takeIf {
    it.isNotBlank() && !it.contains("°N") && it != "30.27°N, 120.15°E" && it != "杭州市西湖区西溪路"
}

/** Versioned *local* envelope. rawJson is the exact original wire/cache JSON
 * including unknown fields; derivedContract is independent of raw scalar
 * receipts. Never claim that this client estimate was a Tesla field.
 * Stored in the existing Room apiEvidence TEXT column: no schema rewrite.
 */
@JsonClass(generateAdapter = true)
internal data class LocalHistoryEvidenceEnvelope(
    @Json(name = "_matelink_local_evidence_version") val version: Int,
    @Json(name = "raw_json") val rawJson: String,
    @Json(name = "detail_energy_contract") val derivedContract: EnergyContract? = null,
    @Json(name = "detail_raw_net_kwh") val detailRawNetKwh: Double? = null,
    @Json(name = "detail_raw_battery_kwh") val detailRawBatteryKwh: Double? = null,
    @Json(name = "detail_raw_ac_kwh") val detailRawAcKwh: Double? = null,
    @Json(name = "detail_source") val detailSource: String? = null,
    @Json(name = "detail_start_date") val detailStartDate: String? = null,
    @Json(name = "detail_end_date") val detailEndDate: String? = null
)

/** Rehydrate exact nullable evidence; scalar placeholders never prove a measured zero. */
/** Raw, provenance-bearing values are NEVER normalized before a persistence merge.
 * apiEvidence is an archive of what the endpoint returned, not a certified measurement.
 */
fun DriveSummary.toRawAnalysisDriveData(): DriveData {
    val fromEvidence = apiEvidence?.let(HistorySummaryEvidenceCodec::decodeDrive)?.takeIf { it.driveId == driveId }?.let {
        // This is source evidence, not display text. Preserve raw addresses
        // exactly in apiEvidence; only the view projection sanitizes labels.
        it.copy(qualityState = qualityState, qualityReason = qualityReason)
    }
    return fromEvidence ?: DriveData(
        driveId = driveId, startDate = startDate, endDate = endDate,
        startAddress = startAddress.cleanAddress(), endAddress = endAddress.cleanAddress(),
        odometerDetails = DriveOdometerDetails(distance = distance.takeIf { it.isFinite() && it > 0.0 }),
        durationMin = durationMin.takeIf { it > 0 }, speedMax = speedMax.takeIf { it > 0 },
        speedAvg = speedAvg.toDouble().takeIf { it > 0.0 }, powerMax = powerMax, powerMin = powerMin,
        batteryDetails = DriveBatteryDetails(startBatteryLevel.takeIf(::isLegacyBatteryLevel), endBatteryLevel.takeIf(::isLegacyBatteryLevel)),
        outsideTempAvg = outsideTempAvg?.takeIf(Double::isFinite), insideTempAvg = insideTempAvg?.takeIf(Double::isFinite),
        // An old local power estimate without its window/coverage evidence is not an API report.
        energyConsumedNet = if (apiEvidence != null) null else energyConsumed?.takeIf { it.isFinite() && energySource == "api" },
        consumptionNet = if (apiEvidence != null) null else efficiency?.takeIf { it.isFinite() && energySource == "api" },
        qualityState = qualityState, qualityReason = qualityReason
    )
}

/** Preserve the exact raw charge envelope independently of its UI projection. */
fun ChargeSummary.toRawAnalysisChargeData(): ChargeData =
    apiEvidence?.let(HistorySummaryEvidenceCodec::decodeCharge)?.takeIf { it.chargeId == chargeId }?.copy(
        qualityState = qualityState, qualityReason = qualityReason
    ) ?: ChargeData(
        chargeId = chargeId, startDate = startDate, endDate = endDate, address = address.takeIf(String::isNotBlank),
        chargeEnergyAdded = if (apiEvidence != null) null else energyAdded.takeIf { it.isFinite() && it > 0.0 },
        chargeEnergyUsed = if (apiEvidence != null) null else energyUsed?.takeIf { it.isFinite() && it >= 0.0 },
        cost = cost?.takeIf { it.isFinite() && it >= 0.0 }, durationMin = durationMin.takeIf { it > 0 },
        batteryDetails = com.matelink.data.api.models.ChargeBatteryDetails(startBatteryLevel.takeIf(::isLegacyBatteryLevel), endBatteryLevel.takeIf(::isLegacyBatteryLevel)),
        outsideTempAvg = outsideTempAvg?.takeIf(Double::isFinite), odometer = odometer.takeIf { it.isFinite() && it > 0.0 },
        latitude = latitude.takeIf { it.isFinite() && it != 0.0 }, longitude = longitude.takeIf { it.isFinite() && it != 0.0 },
        qualityState = qualityState, qualityReason = qualityReason
    )

private fun sameEvidenceInstant(a: String?, b: String?): Boolean =
    a != null && b != null &&
        runCatching { Instant.parse(a) }.getOrNull() != null &&
        runCatching { Instant.parse(a) }.getOrNull() == runCatching { Instant.parse(b) }.getOrNull()

/** UI and analytic consumers only see physically qualified values; the saved raw
 * JSON, including old unverified Fleet scalars, is not modified by this view.
 */
/** Shared presentation projection for cached AND freshly downloaded trips.
 * Never use this to create the raw Room evidence JSON.
 */
fun DriveData.withSafeHistoryDisplay(): DriveData =
    withQualifiedEnergy().let {
        it.copy(startAddress = it.startAddress.cleanAddress(),
            endAddress = it.endAddress.cleanAddress())
    }

fun DriveSummary.toAnalysisDriveData(): DriveData {
    val raw = toRawAnalysisDriveData()
    val localDetail = HistorySummaryEvidenceCodec.detailContract(apiEvidence)
    // Bound a derived claim against the persisted precise detail window,
    // while keeping original (possibly rounded) source JSON byte-exact.
    val windowed = if (localDetail == null) raw else raw.copy(
        startDate = startDate, endDate = endDate
    )
    // A sidecar from another source or window is never an admissible proof.
    // A mismatched sidecar must also not fall back to an older raw scalar.
    val projected = if (localDetail == null) raw else {
        val metric = localDetail.netEnergy
        val safe = localDetail.version == 1 && metric != null &&
            metric.source != null && (raw.source == null || raw.source == metric.source) &&
            sameEvidenceInstant(metric.startDate, startDate) &&
            sameEvidenceInstant(metric.endDate, endDate)
        windowed.copy(energyContract = if (safe) localDetail else
            EnergyContract(netEnergy = com.matelink.data.api.models.EnergyMetric(
                quality = "unknown", reason = "local_detail_window_or_source_mismatch"
            )))
    }
    return projected.withSafeHistoryDisplay()
}

fun ChargeSummary.toAnalysisChargeData(): ChargeData {
    val raw = toRawAnalysisChargeData()
    val detail = HistorySummaryEvidenceCodec.detailContract(apiEvidence)
    val windowed = if (detail == null) raw else raw.copy(
        startDate = startDate, endDate = endDate
    )
    val metric = detail?.batteryInput ?: detail?.acInput
    val safe = detail?.version == 1 && metric?.source != null &&
        (raw.source == null || raw.source == metric.source) &&
        sameEvidenceInstant(metric.startDate, startDate) &&
        sameEvidenceInstant(metric.endDate, endDate)
    val projected = if (detail == null) raw else windowed.copy(
        energyContract = if (safe) detail else EnergyContract(
            batteryInput = com.matelink.data.api.models.EnergyMetric(
                quality = "unknown", reason = "local_charge_detail_window_or_source_mismatch"
            )
        )
    )
    return projected.withQualifiedEnergy()
}

private fun isLegacyBatteryLevel(value: Int): Boolean = value in 1..100

/** Persist raw and qualified evidence independently, without a database migration. */
internal object HistorySummaryEvidenceCodec {
    private val moshi = Moshi.Builder().build()
    private val driveAdapter = moshi.adapter(DriveData::class.java)
    private val chargeAdapter = moshi.adapter(ChargeData::class.java)
    private val envelopeAdapter = moshi.adapter(LocalHistoryEvidenceEnvelope::class.java)

    fun encode(drive: DriveData): String = driveAdapter.toJson(drive)
    fun encode(charge: ChargeData): String = chargeAdapter.toJson(charge)

    private fun envelope(value: String?): LocalHistoryEvidenceEnvelope? {
        if (value == null || !value.contains("\"_matelink_local_evidence_version\"")) return null
        return runCatching { envelopeAdapter.fromJson(value) }.getOrNull()
            ?.takeIf { it.version == 1 && it.rawJson.isNotBlank() }
    }

    fun sourceJson(value: String?): String? {
        if (value == null) return null
        if (!value.contains("\"_matelink_local_evidence_version\"")) return value
        // Never reinterpret an invalid/newer envelope as an ordinary API value.
        return envelope(value)?.rawJson
    }

    fun decodeDrive(value: String): DriveData? =
        sourceJson(value)?.let { runCatching { driveAdapter.fromJson(it) }.getOrNull() }
    fun decodeCharge(value: String): ChargeData? =
        sourceJson(value)?.let { runCatching { chargeAdapter.fromJson(it) }.getOrNull() }

    fun detailContract(value: String?): EnergyContract? = envelope(value)?.derivedContract
    fun detailRawNet(value: String?): Double? = envelope(value)?.detailRawNetKwh
    fun detailRawBattery(value: String?): Double? = envelope(value)?.detailRawBatteryKwh
    fun detailRawAc(value: String?): Double? = envelope(value)?.detailRawAcKwh


    /** Keep the source JSON byte-for-byte even when new detail proof is added.
     * Typed new detail scalar is only a receipt, never inferred as qualified.
     */
    fun withDetail(
        original: String?, fallbackRaw: DriveData, contract: EnergyContract,
        detailRawNet: Double?, detailSource: String?, start: String?, end: String?
    ): String {
        val raw = sourceJson(original) ?: encode(fallbackRaw)
        val old = envelope(original)
        return envelopeAdapter.toJson(LocalHistoryEvidenceEnvelope(
            version = 1, rawJson = raw, derivedContract = contract,
            detailRawNetKwh = detailRawNet?.takeIf(Double::isFinite) ?: old?.detailRawNetKwh,
            detailSource = detailSource, detailStartDate = start, detailEndDate = end
        ))
    }

    fun withChargeDetail(
        original: String?, fallbackRaw: ChargeData, contract: EnergyContract,
        rawBattery: Double?, rawAc: Double?, detailSource: String?,
        start: String?, end: String?
    ): String {
        val raw = sourceJson(original) ?: encode(fallbackRaw)
        val old = envelope(original)
        return envelopeAdapter.toJson(LocalHistoryEvidenceEnvelope(
            version = 1, rawJson = raw, derivedContract = contract,
            detailRawBatteryKwh = rawBattery?.takeIf(Double::isFinite) ?: old?.detailRawBatteryKwh,
            detailRawAcKwh = rawAc?.takeIf(Double::isFinite) ?: old?.detailRawAcKwh,
            detailSource = detailSource, detailStartDate = start,
            detailEndDate = end
        ))
    }

    fun hasDetail(value: String?): Boolean = envelope(value)?.derivedContract != null
}

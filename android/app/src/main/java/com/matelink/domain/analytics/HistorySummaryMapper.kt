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
    @Json(name = "detail_end_date") val detailEndDate: String? = null,
    // Only typed presentation metadata; never a Tesla measurement claim.
    // Contains no route points, energy scalar or energy contract.
    @Json(name = "detail_scope_car_id") val detailScopeCarId: Int? = null,
    @Json(name = "detail_drive_presentation") val detailDrivePresentation: DriveData? = null,
    @Json(name = "detail_charge_presentation") val detailChargePresentation: ChargeData? = null
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
    val hasSnapshot = HistorySummaryEvidenceCodec.hasDrivePresentation(apiEvidence)
    val latest = HistorySummaryEvidenceCodec.drivePresentation(
        apiEvidence, carId, driveId, raw.source, startDate, endDate
    )
    // Fail closed if a locally stored snapshot is forged, moved across cars,
    // assigned to a different source, or for another instant window.
    val displayed = latest ?: raw.copy(startDate = startDate, endDate = endDate)
    val localDetail = HistorySummaryEvidenceCodec.detailContract(apiEvidence)
    val metric = localDetail?.netEnergy
    val safe = !hasSnapshot || latest != null
    val verified = safe && localDetail?.version == 1 && metric?.source != null &&
        (raw.source == null || raw.source == metric.source) &&
        sameEvidenceInstant(metric.startDate, startDate) &&
        sameEvidenceInstant(metric.endDate, endDate)
    val projected = if (localDetail == null && !hasSnapshot) displayed else displayed.copy(
        energyContract = if (verified) localDetail else EnergyContract(
            netEnergy = com.matelink.data.api.models.EnergyMetric(
                quality = "unknown", reason = "local_detail_scope_or_window_mismatch"
            )
        )
    )
    return projected.withSafeHistoryDisplay()
}

fun ChargeSummary.toAnalysisChargeData(): ChargeData {
    val raw = toRawAnalysisChargeData()
    val hasSnapshot = HistorySummaryEvidenceCodec.hasChargePresentation(apiEvidence)
    val latest = HistorySummaryEvidenceCodec.chargePresentation(
        apiEvidence, carId, chargeId, raw.source, startDate, endDate
    )
    val displayed = latest ?: raw.copy(startDate = startDate, endDate = endDate)
    val detail = HistorySummaryEvidenceCodec.detailContract(apiEvidence)
    val metric = detail?.batteryInput ?: detail?.acInput
    val safe = !hasSnapshot || latest != null
    val verified = safe && detail?.version == 1 && metric?.source != null &&
        (raw.source == null || raw.source == metric.source) &&
        sameEvidenceInstant(metric.startDate, startDate) &&
        sameEvidenceInstant(metric.endDate, endDate)
    val projected = if (detail == null && !hasSnapshot) displayed else displayed.copy(
        energyContract = if (verified) detail else EnergyContract(
            batteryInput = com.matelink.data.api.models.EnergyMetric(
                quality = "unknown", reason = "local_charge_detail_scope_or_window_mismatch"
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
    fun hasDrivePresentation(value: String?): Boolean =
        envelope(value)?.detailDrivePresentation != null
    fun hasChargePresentation(value: String?): Boolean =
        envelope(value)?.detailChargePresentation != null

    fun drivePresentation(
        value: String?, carId: Int, driveId: Int, source: String?,
        start: String?, end: String?
    ): DriveData? = envelope(value)?.let { e ->
        // The envelope is NOT trusted if its original receipt cannot be
        // decoded as the matching scoped record; a local wrapper is not Fleet
        // authorization or physical energy proof.
        val sourceRecord = runCatching { driveAdapter.fromJson(e.rawJson) }.getOrNull()
        e.detailDrivePresentation?.takeIf { p ->
            sourceRecord != null && sourceRecord.driveId == driveId &&
            sourceRecord.source == source &&
            e.detailScopeCarId == carId && p.driveId == driveId &&
                p.energyConsumedNet == null && p.consumptionNet == null &&
                p.energyContract == null && (source == null || source == p.source) &&
                e.detailSource == p.source &&
                sameEvidenceInstant(e.detailStartDate, p.startDate) &&
                sameEvidenceInstant(e.detailEndDate, p.endDate) &&
                sameEvidenceInstant(start, p.startDate) &&
                sameEvidenceInstant(end, p.endDate)
        }
    }

    fun chargePresentation(
        value: String?, carId: Int, chargeId: Int, source: String?,
        start: String?, end: String?
    ): ChargeData? = envelope(value)?.let { e ->
        val sourceRecord = runCatching { chargeAdapter.fromJson(e.rawJson) }.getOrNull()
        e.detailChargePresentation?.takeIf { p ->
            sourceRecord != null && sourceRecord.chargeId == chargeId &&
            sourceRecord.source == source &&
            e.detailScopeCarId == carId && p.chargeId == chargeId &&
                p.chargeEnergyAdded == null && p.chargeEnergyUsed == null &&
                p.energyContract == null && (source == null || source == p.source) &&
                e.detailSource == p.source &&
                sameEvidenceInstant(e.detailStartDate, p.startDate) &&
                sameEvidenceInstant(e.detailEndDate, p.endDate) &&
                sameEvidenceInstant(start, p.startDate) &&
                sameEvidenceInstant(end, p.endDate)
        }
    }



    /** Keep the source JSON byte-for-byte even when new detail proof is added.
     * Typed new detail scalar is only a receipt, never inferred as qualified.
     */
    fun withDetail(
        original: String?, fallbackRaw: DriveData, contract: EnergyContract,
        detailRawNet: Double?, detailSource: String?, start: String?, end: String?,
        scopeCarId: Int, presentation: DriveData
    ): String {
        val raw = sourceJson(original) ?: encode(fallbackRaw)
        val old = envelope(original)
        return envelopeAdapter.toJson(LocalHistoryEvidenceEnvelope(
            version = 1, rawJson = raw, derivedContract = contract,
            detailRawNetKwh = detailRawNet?.takeIf(Double::isFinite) ?: old?.detailRawNetKwh,
            detailSource = detailSource, detailStartDate = start, detailEndDate = end,
            detailScopeCarId = scopeCarId,
            detailDrivePresentation = presentation.copy(
                energyConsumedNet = null, consumptionNet = null, energyContract = null
            )
        ))
    }

    fun withChargeDetail(
        original: String?, fallbackRaw: ChargeData, contract: EnergyContract,
        rawBattery: Double?, rawAc: Double?, detailSource: String?,
        start: String?, end: String?, scopeCarId: Int,
        presentation: ChargeData
    ): String {
        val raw = sourceJson(original) ?: encode(fallbackRaw)
        val old = envelope(original)
        return envelopeAdapter.toJson(LocalHistoryEvidenceEnvelope(
            version = 1, rawJson = raw, derivedContract = contract,
            detailRawBatteryKwh = rawBattery?.takeIf(Double::isFinite) ?: old?.detailRawBatteryKwh,
            detailRawAcKwh = rawAc?.takeIf(Double::isFinite) ?: old?.detailRawAcKwh,
            detailSource = detailSource, detailStartDate = start,
            detailEndDate = end, detailScopeCarId = scopeCarId,
            detailChargePresentation = presentation.copy(
                chargeEnergyAdded = null, chargeEnergyUsed = null, energyContract = null
            )
        ))
    }

    fun hasDetail(value: String?): Boolean = envelope(value)?.derivedContract != null
}

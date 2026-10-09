package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.squareup.moshi.Moshi

private fun String?.cleanAddress(): String? = this?.trim()?.takeIf {
    it.isNotBlank() && !it.contains("°N") && it != "30.27°N, 120.15°E" && it != "杭州市西湖区西溪路"
}

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
        energyConsumedNet = energyConsumed?.takeIf { it.isFinite() && energySource == "api" },
        consumptionNet = efficiency?.takeIf { it.isFinite() && energySource == "api" },
        qualityState = qualityState, qualityReason = qualityReason
    )
}

/** Preserve the exact raw charge envelope independently of its UI projection. */
fun ChargeSummary.toRawAnalysisChargeData(): ChargeData =
    apiEvidence?.let(HistorySummaryEvidenceCodec::decodeCharge)?.takeIf { it.chargeId == chargeId }?.copy(
        qualityState = qualityState, qualityReason = qualityReason
    ) ?: ChargeData(
        chargeId = chargeId, startDate = startDate, endDate = endDate, address = address.takeIf(String::isNotBlank),
        chargeEnergyAdded = energyAdded.takeIf { it.isFinite() && it > 0.0 },
        chargeEnergyUsed = energyUsed?.takeIf { it.isFinite() && it >= 0.0 },
        cost = cost?.takeIf { it.isFinite() && it >= 0.0 }, durationMin = durationMin.takeIf { it > 0 },
        batteryDetails = com.matelink.data.api.models.ChargeBatteryDetails(startBatteryLevel.takeIf(::isLegacyBatteryLevel), endBatteryLevel.takeIf(::isLegacyBatteryLevel)),
        outsideTempAvg = outsideTempAvg?.takeIf(Double::isFinite), odometer = odometer.takeIf { it.isFinite() && it > 0.0 },
        latitude = latitude.takeIf { it.isFinite() && it != 0.0 }, longitude = longitude.takeIf { it.isFinite() && it != 0.0 },
        qualityState = qualityState, qualityReason = qualityReason
    )

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

fun DriveSummary.toAnalysisDriveData(): DriveData =
    toRawAnalysisDriveData().withSafeHistoryDisplay()

fun ChargeSummary.toAnalysisChargeData(): ChargeData =
    toRawAnalysisChargeData().withQualifiedEnergy()

private fun isLegacyBatteryLevel(value: Int): Boolean = value in 1..100

/** Keeps nullable API evidence distinct from old Room scalar placeholders. */
internal object HistorySummaryEvidenceCodec {
    private val moshi = Moshi.Builder().build()
    private val driveAdapter = moshi.adapter(DriveData::class.java)
    private val chargeAdapter = moshi.adapter(ChargeData::class.java)
    fun encode(drive: DriveData): String = driveAdapter.toJson(drive)
    fun encode(charge: ChargeData): String = chargeAdapter.toJson(charge)
    fun decodeDrive(value: String): DriveData? = runCatching { driveAdapter.fromJson(value) }.getOrNull()
    fun decodeCharge(value: String): ChargeData? = runCatching { chargeAdapter.fromJson(value) }.getOrNull()
}

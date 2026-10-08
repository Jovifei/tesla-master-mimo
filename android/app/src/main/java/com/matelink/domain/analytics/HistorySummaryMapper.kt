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
fun DriveSummary.toAnalysisDriveData(): DriveData {
    val fromEvidence = apiEvidence?.let(HistorySummaryEvidenceCodec::decodeDrive)?.takeIf { it.driveId == driveId }?.let {
        it.copy(startAddress = it.startAddress.cleanAddress(), endAddress = it.endAddress.cleanAddress(),
            qualityState = qualityState, qualityReason = qualityReason).withQualifiedEnergy()
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

fun ChargeSummary.toAnalysisChargeData(): ChargeData =
    apiEvidence?.let(HistorySummaryEvidenceCodec::decodeCharge)?.takeIf { it.chargeId == chargeId }?.copy(
        qualityState = qualityState, qualityReason = qualityReason
    )?.withQualifiedEnergy() ?: ChargeData(
        chargeId = chargeId, startDate = startDate, endDate = endDate, address = address.takeIf(String::isNotBlank),
        chargeEnergyAdded = energyAdded.takeIf { it.isFinite() && it > 0.0 },
        chargeEnergyUsed = energyUsed?.takeIf { it.isFinite() && it >= 0.0 },
        cost = cost?.takeIf { it.isFinite() && it >= 0.0 }, durationMin = durationMin.takeIf { it > 0 },
        batteryDetails = com.matelink.data.api.models.ChargeBatteryDetails(startBatteryLevel.takeIf(::isLegacyBatteryLevel), endBatteryLevel.takeIf(::isLegacyBatteryLevel)),
        outsideTempAvg = outsideTempAvg?.takeIf(Double::isFinite), odometer = odometer.takeIf { it.isFinite() && it > 0.0 },
        latitude = latitude.takeIf { it.isFinite() && it != 0.0 }, longitude = longitude.takeIf { it.isFinite() && it != 0.0 },
        qualityState = qualityState, qualityReason = qualityReason
    )

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

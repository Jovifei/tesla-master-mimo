package com.matelink.domain.analytics

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.local.entity.DriveSummary

/** The same path is used by foreground details and background Room enrichment. */
data class ResolvedDriveEnergy(val estimate: DriveEnergyEstimate, val evidence: EnergyMetric)

fun DriveDetail.resolveDriveEnergy(): ResolvedDriveEnergy {
    val supplied = energyContract
    if (supplied != null) {
        val metric = supplied.netEnergy
        val value = netEnergyKwh
        val source = when {
            value == null -> DriveEnergySource.UNAVAILABLE
            metric?.method == "drive_power_integral" -> DriveEnergySource.POWER_SAMPLES
            else -> DriveEnergySource.API
        }
        val seconds = metric?.coverageSeconds?.takeIf { it.isFinite() && it >= 0.0 }
        return ResolvedDriveEnergy(
            DriveEnergyEstimate(value, value?.let { e -> distance?.takeIf { it.isFinite() && it > 0.0 }?.let { (e / it * 1000).takeIf(Double::isFinite) } },
                source, seconds?.toLong() ?: 0L, seconds ?: 0.0,
                metric?.coverageRatio?.takeIf { it.isFinite() && it in 0.0..1.0 },
                metric?.coveredEnergyKwh?.takeIf(Double::isFinite), metric?.reason),
            metric ?: EnergyMetric(reason = "missing_net_energy", startDate = startDate, endDate = endDate)
        )
    }
    val estimate = DriveEnergyResolver.resolve(energyConsumedNet, distance,
        positions.orEmpty().map { DrivePowerSample(it.date, it.power) },
        durationSeconds = durationMin?.toLong()?.times(60), startDate = startDate, endDate = endDate)
    val power = estimate.source == DriveEnergySource.POWER_SAMPLES || estimate.observedEnergyKwh != null
    val metric = EnergyMetric(
        valueKwh = estimate.energyKwh,
        method = if (power) "drive_power_integral" else "api_reported_net",
        measurementPoint = if (power) "drive_power" else "reported_net",
        source = source ?: "legacy_api",
        sourceField = if (power) "drive_details.power" else "energy_consumed_net",
        quality = when (estimate.source) {
            DriveEnergySource.API -> "reported"
            DriveEnergySource.POWER_SAMPLES -> "estimated"
            DriveEnergySource.UNAVAILABLE -> "unknown"
        },
        reason = estimate.qualityReason,
        startDate = startDate, endDate = endDate,
        timeBasis = if (power) "source_sample_time" else "source_report",
        coverageKind = if (power) "time" else "provider_report",
        coverageSeconds = if (power) estimate.coverageSecondsExact else null,
        coverageRatio = estimate.coverageRatio,
        coveredEnergyKwh = estimate.observedEnergyKwh
    )
    return ResolvedDriveEnergy(estimate, metric)
}

/** Scalar and JSON evidence are updated together; stale evidence cannot mask new detail energy. */
fun DriveSummary.withResolvedDriveEnergy(detail: DriveDetail, resolved: ResolvedDriveEnergy): DriveSummary {
    val estimate = resolved.estimate
    val evidence = toAnalysisDriveData().copy(
        startDate = detail.startDate ?: startDate,
        endDate = detail.endDate ?: endDate,
        startAddress = detail.startAddress ?: startAddress,
        endAddress = detail.endAddress ?: endAddress,
        odometerDetails = detail.odometerDetails ?: toAnalysisDriveData().odometerDetails,
        batteryDetails = detail.batteryDetails ?: toAnalysisDriveData().batteryDetails,
        outsideTempAvg = detail.outsideTempAvg ?: outsideTempAvg,
        insideTempAvg = detail.insideTempAvg ?: insideTempAvg,
        speedMax = detail.speedMax ?: speedMax,
        energyConsumedNet = estimate.energyKwh,
        consumptionNet = estimate.efficiencyWhKm,
        energyContract = detail.energyContract ?: EnergyContract(netEnergy = resolved.evidence)
    )
    return copy(
        energyConsumed = estimate.energyKwh,
        efficiency = estimate.efficiencyWhKm,
        energySource = estimate.source.name.lowercase(),
        energyCoverageSeconds = estimate.coverageSeconds,
        energyCoverageRatio = estimate.coverageRatio ?: 0.0,
        apiEvidence = HistorySummaryEvidenceCodec.encodeDrive(evidence)
    )
}

fun DriveData.asCachedDetail(): DriveDetail = DriveDetail(
    driveId = driveId, startDate = startDate, endDate = endDate,
    startAddress = startAddress, endAddress = endAddress, odometerDetails = odometerDetails,
    durationMin = durationMin, durationStr = durationStr,
    speedMax = speedMax, speedAvg = speedAvg, powerMax = powerMax, powerMin = powerMin,
    batteryDetails = batteryDetails, rangeIdeal = rangeIdeal, rangeRated = rangeRated,
    outsideTempAvg = outsideTempAvg, insideTempAvg = insideTempAvg,
    energyConsumedNet = energyConsumedNet, consumptionNet = consumptionNet,
    source = source, qualityState = qualityState, qualityReason = qualityReason,
    startLatitude = startLatitude, startLongitude = startLongitude,
    endLatitude = endLatitude, endLongitude = endLongitude, energyContract = energyContract
)

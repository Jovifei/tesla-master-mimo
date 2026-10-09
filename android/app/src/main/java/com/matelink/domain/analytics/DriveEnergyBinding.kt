package com.matelink.domain.analytics

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.api.models.legacyScalarEnergyAllowed
import com.matelink.data.local.entity.DriveSummary

/** The same path is used by foreground details and background Room enrichment. */
data class ResolvedDriveEnergy(val estimate: DriveEnergyEstimate, val evidence: EnergyMetric)

fun DriveDetail.resolveDriveEnergy(): ResolvedDriveEnergy {
    val supplied = energyContract
    if (supplied != null) {
        val metric = supplied.netEnergy
        val value = netEnergyKwh
        val classification = when {
            value == null -> DriveEnergySource.UNAVAILABLE
            metric?.method == "drive_power_integral" -> DriveEnergySource.POWER_SAMPLES
            else -> DriveEnergySource.API
        }
        val seconds = metric?.coverageSeconds?.takeIf { it.isFinite() && it >= 0.0 }
        return ResolvedDriveEnergy(
            DriveEnergyEstimate(value, value?.let { e -> distance?.takeIf { it.isFinite() && it > 0.0 }?.let { (e / it * 1000).takeIf(Double::isFinite) } },
                classification, seconds?.toLong() ?: 0L, seconds ?: 0.0,
                metric?.coverageRatio?.takeIf { it.isFinite() && it in 0.0..1.0 },
                metric?.coveredEnergyKwh?.takeIf(Double::isFinite), metric?.reason),
            (metric ?: EnergyMetric(startDate = startDate, endDate = endDate)).let {
                if (value == null) it.copy(valueKwh = null, quality = "unknown", reason = it.reason ?: "unqualified_energy_contract") else it
            }
        )
    }
    // A legacy bb09 Fleet scalar lacks its entire counter/window evidence;
    // archived observed power samples remain a separately labeled estimate.
    val scalar = energyConsumedNet.takeIf { legacyScalarEnergyAllowed(source) }
    val samples = if (source in setOf("local_import", "local_history")) emptyList() else positions.orEmpty()
    val estimate = DriveEnergyResolver.resolve(scalar, distance,
        samples.map { DrivePowerSample(it.date, it.power) },
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
        // MQTT route timestamps are collector receipt instants; archived
        // TeslaMate points carry their recorded source sample instants.
        timeBasis = when {
            !power -> "source_report"
            source == "telemetry_mqtt" -> "collector_received_at"
            source == "teslamate_archive" -> "source_sample_time"
            else -> "route_timestamp_unverified"
        },
        coverageKind = if (power) "time" else "provider_report",
        coverageSeconds = if (power) estimate.coverageSecondsExact else null,
        coverageRatio = estimate.coverageRatio,
        coveredEnergyKwh = estimate.observedEnergyKwh
    )
    return ResolvedDriveEnergy(estimate, metric)
}

/** Scalar and JSON evidence are updated together in the same Room upsert. */
fun DriveSummary.withResolvedDriveEnergy(detail: DriveDetail, resolved: ResolvedDriveEnergy): DriveSummary {
    require(detail.driveId == driveId) { "history_detail_id_mismatch" }
    val previous = toAnalysisDriveData()
    require(previous.source == null || detail.source == null || previous.source == detail.source) { "history_detail_source_mismatch" }
    val estimate = resolved.estimate
    val evidence = previous.copy(
        startDate = detail.startDate ?: startDate,
        endDate = detail.endDate ?: endDate,
        startAddress = detail.startAddress ?: previous.startAddress,
        endAddress = detail.endAddress ?: previous.endAddress,
        odometerDetails = detail.odometerDetails ?: previous.odometerDetails,
        durationMin = detail.durationMin ?: previous.durationMin,
        batteryDetails = detail.batteryDetails ?: previous.batteryDetails,
        outsideTempAvg = detail.outsideTempAvg ?: previous.outsideTempAvg,
        insideTempAvg = detail.insideTempAvg ?: previous.insideTempAvg,
        speedMax = detail.speedMax ?: previous.speedMax,
        powerMax = detail.powerMax ?: previous.powerMax,
        powerMin = detail.powerMin ?: previous.powerMin,
        source = detail.source ?: previous.source,
        energyConsumedNet = estimate.energyKwh,
        consumptionNet = estimate.efficiencyWhKm,
        energyContract = detail.energyContract ?: EnergyContract(netEnergy = resolved.evidence)
    )
    return copy(
        startDate = evidence.startDate ?: startDate, endDate = evidence.endDate ?: endDate,
        durationMin = evidence.durationMin ?: durationMin,
        startAddress = evidence.startAddress.orEmpty(), endAddress = evidence.endAddress.orEmpty(),
        distance = evidence.distance?.takeIf { it.isFinite() && it >= 0.0 } ?: distance,
        startBatteryLevel = evidence.startBatteryLevel ?: 0, endBatteryLevel = evidence.endBatteryLevel ?: 0,
        outsideTempAvg = evidence.outsideTempAvg?.takeIf(Double::isFinite),
        insideTempAvg = evidence.insideTempAvg?.takeIf(Double::isFinite),
        speedMax = evidence.speedMax ?: speedMax, powerMax = evidence.powerMax ?: powerMax, powerMin = evidence.powerMin ?: powerMin,
        energyConsumed = estimate.energyKwh, efficiency = estimate.efficiencyWhKm,
        energySource = estimate.source.name.lowercase(),
        energyCoverageSeconds = estimate.coverageSeconds,
        energyCoverageRatio = estimate.coverageRatio ?: 0.0,
        apiEvidence = HistorySummaryEvidenceCodec.encode(evidence)
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

/** Normalize legacy scalar consumers without throwing away the original provenance. */
fun DriveData.withQualifiedEnergy(): DriveData = copy(energyConsumedNet = netEnergyKwh, consumptionNet = efficiencyWhKm)

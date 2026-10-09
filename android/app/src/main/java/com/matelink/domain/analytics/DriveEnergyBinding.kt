package com.matelink.domain.analytics

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.api.models.legacyScalarEnergyAllowed
import com.matelink.data.api.models.netValueForWindow
import com.matelink.util.parseIsoInstant
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
    val rawPrevious = toRawAnalysisDriveData()
    require(rawPrevious.source == null || detail.source == null || rawPrevious.source == detail.source) { "history_detail_source_mismatch" }
    val estimate = resolved.estimate
    val displayPrevious = toAnalysisDriveData()
    val nextStart = detail.startDate ?: rawPrevious.startDate
    val nextEnd = detail.endDate ?: rawPrevious.endDate
    val sameWindow = parseIsoInstant(nextStart) != null &&
        parseIsoInstant(nextStart) == parseIsoInstant(rawPrevious.startDate) &&
        parseIsoInstant(nextEnd) == parseIsoInstant(rawPrevious.endDate)
    // Only an earlier independently proven contract, for precisely the same
    // source and instant window, can survive a detail with NO new energy proof.
    // Explicit unknown detail always overrides for display, never for raw.
    val prior = displayPrevious.energyContract
    val carried = displayPrevious.netEnergyKwh?.takeIf {
        detail.energyContract == null && estimate.energyKwh == null && sameWindow &&
        prior?.netValueForWindow(nextStart, nextEnd) == it &&
        (detail.source == null || detail.source == rawPrevious.source)
    }
    val detailProof = when {
        detail.energyContract != null -> detail.energyContract
        estimate.energyKwh != null -> EnergyContract(netEnergy = resolved.evidence)
        carried != null && prior != null -> prior
        else -> EnergyContract(netEnergy = resolved.evidence)
    }
    // A derived integral is a NEW measurement claim, not a replacement for
    // the original raw energy_consumed_net (including unverified Fleet 8).
    val rawReceipt = HistorySummaryEvidenceCodec.withDetail(
        apiEvidence, rawPrevious, detailProof,
        detail.energyConsumedNet, detail.source ?: rawPrevious.source,
        nextStart, nextEnd
    )
    val proofValue = detailProof.netValueForWindow(nextStart, nextEnd)
        ?.takeIf { rawPrevious.source == null ||
            detailProof.netEnergy?.source == rawPrevious.source }
    val distanceForEnergy = (detail.distance ?: rawPrevious.distance)
        ?.takeIf { it.isFinite() && it > 0.0 }
    val efficiencyForEnergy = proofValue?.let { e ->
        distanceForEnergy?.let { (e / it * 1000.0).takeIf(Double::isFinite) }
    }
    val evidence = rawPrevious.copy(
        startDate = nextStart, endDate = nextEnd,
        startAddress = detail.startAddress ?: rawPrevious.startAddress,
        endAddress = detail.endAddress ?: rawPrevious.endAddress,
        odometerDetails = detail.odometerDetails ?: rawPrevious.odometerDetails,
        durationMin = detail.durationMin ?: rawPrevious.durationMin,
        batteryDetails = detail.batteryDetails ?: rawPrevious.batteryDetails,
        outsideTempAvg = detail.outsideTempAvg ?: rawPrevious.outsideTempAvg,
        insideTempAvg = detail.insideTempAvg ?: rawPrevious.insideTempAvg,
        speedMax = detail.speedMax ?: rawPrevious.speedMax,
        powerMax = detail.powerMax ?: rawPrevious.powerMax,
        powerMin = detail.powerMin ?: rawPrevious.powerMin,
        source = detail.source ?: rawPrevious.source
        // NO mutation of raw energyConsumedNet/consumptionNet/energyContract.
    )
    val display = evidence.withSafeHistoryDisplay()
    val qualifiedMetric = detailProof.netEnergy
    return copy(
        startDate = evidence.startDate ?: startDate, endDate = evidence.endDate ?: endDate,
        durationMin = evidence.durationMin ?: durationMin,
        startAddress = display.startAddress.orEmpty(), endAddress = display.endAddress.orEmpty(),
        distance = evidence.distance?.takeIf { it.isFinite() && it >= 0.0 } ?: distance,
        startBatteryLevel = evidence.startBatteryLevel ?: 0, endBatteryLevel = evidence.endBatteryLevel ?: 0,
        outsideTempAvg = evidence.outsideTempAvg?.takeIf(Double::isFinite),
        insideTempAvg = evidence.insideTempAvg?.takeIf(Double::isFinite),
        speedMax = evidence.speedMax ?: speedMax, powerMax = evidence.powerMax ?: powerMax, powerMin = evidence.powerMin ?: powerMin,
        energyConsumed = proofValue, efficiency = efficiencyForEnergy,
        energySource = proofValue?.let {
            if (qualifiedMetric?.method == "drive_power_integral") "power_samples" else "api"
        },
        energyCoverageSeconds = qualifiedMetric?.coverageSeconds
            ?.takeIf { it.isFinite() && it >= 0.0 }?.toLong() ?: 0L,
        energyCoverageRatio = qualifiedMetric?.coverageRatio
            ?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.0,
        apiEvidence = rawReceipt
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

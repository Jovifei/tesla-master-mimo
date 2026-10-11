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
    val current = toAnalysisDriveData()
    // Room summary dates are the currently verified detail dates. Original
    // source JSON may contain bb09's older rounded list boundaries.
    val nextStart = detail.startDate ?: startDate
    val nextEnd = detail.endDate ?: endDate
    val sameWindow = parseIsoInstant(nextStart) != null &&
        parseIsoInstant(nextStart) == parseIsoInstant(startDate) &&
        parseIsoInstant(nextEnd) == parseIsoInstant(endDate)
    val oldProof = current.energyContract
    val carried = current.netEnergyKwh?.takeIf { v ->
        detail.energyContract == null && estimate.energyKwh == null && sameWindow &&
        oldProof?.netValueForWindow(nextStart, nextEnd) == v &&
        (detail.source == null || detail.source == rawPrevious.source)
    }
    val proof = when {
        detail.energyContract != null -> detail.energyContract
        estimate.energyKwh != null -> EnergyContract(netEnergy = resolved.evidence)
        carried != null && oldProof != null -> oldProof
        else -> EnergyContract(netEnergy = resolved.evidence)
    }
    // Current metadata is a separate scoped display snapshot, never a raw
    // Tesla receipt. Explicit nulls retain genuinely unknown SOC.
    val presentation = current.copy(
        startDate = nextStart, endDate = nextEnd,
        startAddress = detail.startAddress ?: current.startAddress,
        endAddress = detail.endAddress ?: current.endAddress,
        odometerDetails = detail.odometerDetails ?: current.odometerDetails,
        durationMin = detail.durationMin ?: current.durationMin,
        durationStr = detail.durationStr ?: current.durationStr,
        speedMax = detail.speedMax ?: current.speedMax,
        speedAvg = detail.speedAvg ?: current.speedAvg,
        powerMax = detail.powerMax ?: current.powerMax,
        powerMin = detail.powerMin ?: current.powerMin,
        batteryDetails = detail.batteryDetails ?: current.batteryDetails,
        rangeIdeal = detail.rangeIdeal ?: current.rangeIdeal,
        rangeRated = detail.rangeRated ?: current.rangeRated,
        outsideTempAvg = detail.outsideTempAvg ?: current.outsideTempAvg,
        insideTempAvg = detail.insideTempAvg ?: current.insideTempAvg,
        startLatitude = detail.startLatitude ?: current.startLatitude,
        startLongitude = detail.startLongitude ?: current.startLongitude,
        endLatitude = detail.endLatitude ?: current.endLatitude,
        endLongitude = detail.endLongitude ?: current.endLongitude,
        source = detail.source ?: rawPrevious.source,
        energyConsumedNet = null, consumptionNet = null, energyContract = null
    )
    val receipt = HistorySummaryEvidenceCodec.withDetail(
        apiEvidence, rawPrevious, proof, detail.energyConsumedNet,
        presentation.source, nextStart, nextEnd, carId, presentation
    )
    val net = proof.netValueForWindow(nextStart, nextEnd)
        ?.takeIf { presentation.source == null ||
            proof.netEnergy?.source == presentation.source }
    val distanceKm = presentation.distance?.takeIf { it.isFinite() && it > 0.0 }
    val efficiency = net?.let { value ->
        distanceKm?.let { (value / it * 1000.0).takeIf(Double::isFinite) }
    }
    val visible = presentation.withSafeHistoryDisplay()
    val metric = proof.netEnergy
    return copy(
        startDate = nextStart ?: startDate, endDate = nextEnd ?: endDate,
        durationMin = visible.durationMin ?: durationMin,
        startAddress = visible.startAddress.orEmpty(),
        endAddress = visible.endAddress.orEmpty(),
        distance = visible.distance?.takeIf { it.isFinite() && it >= 0.0 } ?: this.distance,
        speedMax = visible.speedMax ?: speedMax,
        speedAvg = visible.speedAvg?.takeIf(Double::isFinite)?.toInt() ?: speedAvg,
        powerMax = visible.powerMax ?: powerMax,
        powerMin = visible.powerMin ?: powerMin,
        startBatteryLevel = visible.startBatteryLevel ?: 0,
        endBatteryLevel = visible.endBatteryLevel ?: 0,
        outsideTempAvg = visible.outsideTempAvg?.takeIf(Double::isFinite),
        insideTempAvg = visible.insideTempAvg?.takeIf(Double::isFinite),
        energyConsumed = net, efficiency = efficiency,
        energySource = net?.let {
            if (metric?.method == "drive_power_integral") "power_samples" else "api"
        },
        energyCoverageSeconds = metric?.coverageSeconds
            ?.takeIf { it.isFinite() && it >= 0.0 }?.toLong() ?: 0L,
        energyCoverageRatio = metric?.coverageRatio
            ?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.0,
        apiEvidence = receipt
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

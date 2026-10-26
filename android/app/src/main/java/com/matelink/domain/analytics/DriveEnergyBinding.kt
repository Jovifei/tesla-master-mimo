package com.matelink.domain.analytics

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.api.models.legacyScalarEnergyAllowed
import com.matelink.data.api.models.netValueForWindow
import com.matelink.data.api.models.driveEnergySource
import com.matelink.util.parseIsoInstant
import com.matelink.data.local.entity.DriveSummary

/** The same path is used by foreground details and background Room enrichment. */
data class ResolvedDriveEnergy(val estimate: DriveEnergyEstimate, val evidence: EnergyMetric)

@JvmOverloads
fun DriveDetail.resolveDriveEnergy(
    batteryCapacityKwh: Double? = null,
    model: String? = null,
    trimBadging: String? = null,
    capacityBasis: String? = null
): ResolvedDriveEnergy {
    val supplied = energyContract
    if (supplied != null) {
        val metric = supplied.netEnergy
        val value = netEnergyKwh
        val classification = when {
            value == null -> DriveEnergySource.UNAVAILABLE
            else -> metric.driveEnergySource()?.let { DriveEnergySource.valueOf(it.uppercase()) } ?: DriveEnergySource.UNAVAILABLE
        }
        val seconds = metric?.coverageSeconds?.takeIf { it.isFinite() && it >= 0.0 }
        return ResolvedDriveEnergy(
            DriveEnergyEstimate(value, value?.let { e -> distanceKm?.takeIf { it.isFinite() && it > 0.0 }?.let { (e / it * 1000).takeIf(Double::isFinite) } },
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
    val estimate = DriveEnergyResolver.resolve(
        apiEnergyKwh = scalar,
        distanceKm = distanceKm,
        samples = samples.map { DrivePowerSample(it.date, it.power) },
        durationSeconds = durationMin?.toLong()?.times(60),
        startDate = startDate,
        endDate = endDate,
        startEnergyRemaining = null,
        endEnergyRemaining = null,
        startSoc = startBatteryLevel,
        endSoc = endBatteryLevel,
        batteryCapacityKwh = batteryCapacityKwh,
        model = model,
        trimBadging = trimBadging
    )
    val power = estimate.source == DriveEnergySource.POWER_SAMPLES
    val partialPower = estimate.source == DriveEnergySource.UNAVAILABLE && estimate.observedEnergyKwh != null
    val endpoints = estimate.source in setOf(DriveEnergySource.ENERGY_REMAINING_DELTA, DriveEnergySource.SOC_CAPACITY_ESTIMATE)
    val capacity = if (estimate.source == DriveEnergySource.SOC_CAPACITY_ESTIMATE)
        DriveEnergyResolver.resolveBatteryCapacity(model, trimBadging, batteryCapacityKwh) else null
    val selectedCapacityBasis = capacity?.let { capacityBasis ?: when {
        batteryCapacityKwh?.let { it.isFinite() && it > 0.0 } == true -> "provided_capacity"
        model?.trim()?.uppercase() in setOf("S", "X", "3", "Y") && !trimBadging.isNullOrBlank() -> "model_trim_assumption"
        model?.trim()?.uppercase() in setOf("S", "X", "3", "Y") -> "model_assumption"
        else -> "default_assumption"
    } }
    val metric = EnergyMetric(
        valueKwh = estimate.energyKwh,
        method = when (estimate.source) {
            DriveEnergySource.POWER_SAMPLES -> "drive_power_integral"
            DriveEnergySource.ENERGY_REMAINING_DELTA -> "energy_remaining_delta"
            DriveEnergySource.SOC_CAPACITY_ESTIMATE -> "soc_capacity_estimate"
            else -> if (partialPower) "drive_power_integral" else "api_reported_net"
        },
        measurementPoint = when (estimate.source) {
            DriveEnergySource.POWER_SAMPLES -> "drive_power"
            DriveEnergySource.ENERGY_REMAINING_DELTA -> "battery_energy_remaining"
            DriveEnergySource.SOC_CAPACITY_ESTIMATE -> "battery_soc"
            else -> if (partialPower) "drive_power" else "reported_net"
        },
        source = source ?: "legacy_api",
        sourceField = when (estimate.source) {
            DriveEnergySource.POWER_SAMPLES -> "drive_details.power"
            DriveEnergySource.ENERGY_REMAINING_DELTA -> "energy_remaining"
            DriveEnergySource.SOC_CAPACITY_ESTIMATE -> "battery_details"
            else -> if (partialPower) "drive_details.power" else "energy_consumed_net"
        },
        quality = when (estimate.source) {
            DriveEnergySource.API -> "reported"
            DriveEnergySource.POWER_SAMPLES,
            DriveEnergySource.ENERGY_REMAINING_DELTA,
            DriveEnergySource.SOC_CAPACITY_ESTIMATE -> "estimated"
            DriveEnergySource.UNAVAILABLE -> "unknown"
        },
        reason = estimate.qualityReason,
        startDate = startDate, endDate = endDate,
        // MQTT route timestamps are collector receipt instants; archived
        // TeslaMate points carry their recorded source sample instants.
        timeBasis = when {
            !(power || partialPower) -> "source_report"
            source == "telemetry_mqtt" -> "collector_received_at"
            source == "teslamate_archive" -> "source_sample_time"
            else -> "route_timestamp_unverified"
        },
        coverageKind = when {
            endpoints -> "endpoints"
            power || partialPower -> "time"
            else -> "provider_report"
        },
        coverageSeconds = if (power || partialPower) estimate.coverageSecondsExact else null,
        coverageRatio = if (endpoints) 1.0 else estimate.coverageRatio,
        coveredEnergyKwh = estimate.observedEnergyKwh,
        capacityKwh = capacity,
        capacityBasis = selectedCapacityBasis,
        capacitySourceField = when (selectedCapacityBasis) {
            "api_reported_current_capacity" -> "battery_health.current_capacity"
            "provided_capacity" -> "provided_capacity_kwh"
            "model_trim_assumption" -> "car_details.model+trim_badging"
            "model_assumption" -> "car_details.model"
            "default_assumption" -> "default_60_kwh"
            else -> null
        },
        capacityModel = capacity?.let { model }, capacityTrim = capacity?.let { trimBadging },
        calculationVersion = capacity?.let { 1 },
        startSoc = capacity?.let { startBatteryLevel }, endSoc = capacity?.let { endBatteryLevel }
    )
    return ResolvedDriveEnergy(estimate, metric)
}

/** Scalar and JSON evidence are updated together in the same Room upsert. */
fun DriveSummary.withResolvedDriveEnergy(detail: DriveDetail, resolved: ResolvedDriveEnergy): DriveSummary {
    require(detail.driveId == driveId) { "history_detail_id_mismatch" }
    val rawPrevious = toRawAnalysisDriveData()
    require(rawPrevious.source == null || detail.source == null || rawPrevious.source == detail.source) { "history_detail_source_mismatch" }
    require(sameChargeSourceInstance(rawPrevious.sourceInstanceId, rawPrevious.sourceRecordIdentity,
        detail.sourceInstanceId, detail.sourceRecordIdentity)) { "history_detail_source_record_mismatch" }
    val current = toAnalysisDriveData()
    // Room dates retain the last verified detail window, including subsecond precision.
    val nextStart = detail.startDate ?: startDate
    val nextEnd = detail.endDate ?: endDate
    val sameWindow = parseIsoInstant(nextStart) != null &&
        parseIsoInstant(nextStart) == parseIsoInstant(startDate) &&
        parseIsoInstant(nextEnd) == parseIsoInstant(endDate)
    val priorMetric = current.energyContract?.netEnergy
    fun capacityRank(metric: EnergyMetric?): Int = when (metric?.capacityBasis) {
        "api_reported_current_capacity", "provided_capacity" -> 3
        "model_trim_assumption" -> 2
        "model_assumption" -> 1
        else -> 0
    }
    // Capacity belongs to this verified source/vehicle; recompute corrected SOC, never carry old kWh.
    val chosen = if (detail.energyContract == null && resolved.estimate.source == DriveEnergySource.SOC_CAPACITY_ESTIMATE &&
        sameWindow && rawPrevious.source == detail.source &&
        current.netEnergyKwh != null && priorMetric?.method == "soc_capacity_estimate" &&
        capacityRank(priorMetric) >= capacityRank(resolved.evidence)) {
        detail.resolveDriveEnergy(priorMetric.capacityKwh, priorMetric.capacityModel,
            priorMetric.capacityTrim, priorMetric.capacityBasis)
    } else resolved
    val estimate = chosen.estimate
    val oldProof = current.energyContract
    val carried = current.netEnergyKwh?.takeIf { v ->
        detail.energyContract == null && estimate.energyKwh == null && sameWindow &&
        oldProof?.netValueForWindow(nextStart, nextEnd) == v &&
        (priorMetric?.method != "soc_capacity_estimate" ||
            ((detail.startBatteryLevel == null || detail.startBatteryLevel == priorMetric?.startSoc) &&
                (detail.endBatteryLevel == null || detail.endBatteryLevel == priorMetric?.endSoc))) &&
        (detail.source == null || detail.source == rawPrevious.source)
    }
    val proof = when {
        detail.energyContract != null -> detail.energyContract
        estimate.energyKwh != null -> EnergyContract(netEnergy = chosen.evidence)
        carried != null && oldProof != null -> oldProof
        else -> EnergyContract(netEnergy = chosen.evidence)
    }
    // Current metadata is a separate scoped display snapshot, never a raw
    // Tesla receipt. Explicit nulls retain genuinely unknown SOC.
    val sameLengthUnit = com.matelink.data.api.models.sameDriveLengthUnit(current.lengthUnit, detail.lengthUnit)
    val hasTemperatures = detail.outsideTempAvg != null || detail.insideTempAvg != null
    val sameTemperatureUnit = current.temperatureUnit == detail.temperatureUnit
    val presentation = current.copy(
        startDate = nextStart, endDate = nextEnd,
        startAddress = detail.startAddress ?: current.startAddress,
        endAddress = detail.endAddress ?: current.endAddress,
        odometerDetails = detail.odometerDetails ?: current.odometerDetails.takeIf { sameLengthUnit },
        lengthUnit = detail.lengthUnit,
        durationMin = detail.durationMin ?: current.durationMin,
        durationStr = detail.durationStr ?: current.durationStr,
        speedMax = detail.speedMax ?: current.speedMax.takeIf { sameLengthUnit },
        speedAvg = detail.speedAvg ?: current.speedAvg.takeIf { sameLengthUnit },
        speedMetricsSource = if (detail.speedAvg != null) detail.speedMetricsSource else current.speedMetricsSource,
        speedSampleCount = if (detail.speedAvg != null) detail.speedSampleCount else current.speedSampleCount,
        powerMax = detail.powerMax ?: current.powerMax,
        powerMin = detail.powerMin ?: current.powerMin,
        batteryDetails = detail.batteryDetails ?: current.batteryDetails,
        rangeIdeal = detail.rangeIdeal ?: current.rangeIdeal.takeIf { sameLengthUnit },
        rangeRated = detail.rangeRated ?: current.rangeRated.takeIf { sameLengthUnit },
        outsideTempAvg = detail.outsideTempAvg ?: current.outsideTempAvg.takeIf { !hasTemperatures || sameTemperatureUnit },
        insideTempAvg = detail.insideTempAvg ?: current.insideTempAvg.takeIf { !hasTemperatures || sameTemperatureUnit },
        temperatureUnit = if (hasTemperatures) detail.temperatureUnit else current.temperatureUnit,
        startLatitude = detail.startLatitude ?: current.startLatitude,
        startLongitude = detail.startLongitude ?: current.startLongitude,
        endLatitude = detail.endLatitude ?: current.endLatitude,
        endLongitude = detail.endLongitude ?: current.endLongitude,
        source = detail.source ?: rawPrevious.source,
        sourceInstanceId = detail.sourceInstanceId, sourceId = detail.sourceId, sourceRecordId = detail.sourceRecordId,
        energyConsumedNet = null, consumptionNet = null, energyContract = null
    )
    val receipt = HistorySummaryEvidenceCodec.withDetail(
        apiEvidence, rawPrevious, proof, detail.energyConsumedNet,
        presentation.source, nextStart, nextEnd, carId, presentation
    )
    val net = proof.netValueForWindow(nextStart, nextEnd)
        ?.takeIf { presentation.source == null ||
            proof.netEnergy?.source == presentation.source }
    val distanceKm = presentation.distanceKm?.takeIf { it.isFinite() && it > 0.0 }
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
        distance = visible.distanceKm?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
        speedMax = visible.speedMaxKmh?.toInt() ?: 0,
        speedAvg = visible.speedAvgKmh?.toInt() ?: 0,
        powerMax = visible.powerMax ?: powerMax,
        powerMin = visible.powerMin ?: powerMin,
        startBatteryLevel = visible.startBatteryLevel ?: 0,
        endBatteryLevel = visible.endBatteryLevel ?: 0,
        outsideTempAvg = visible.outsideTempAvg?.takeIf(Double::isFinite),
        insideTempAvg = visible.insideTempAvg?.takeIf(Double::isFinite),
        energyConsumed = net, efficiency = efficiency,
        energySource = net?.let { metric.driveEnergySource() },
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
    sourceInstanceId = sourceInstanceId, sourceId = sourceId, sourceRecordId = sourceRecordId,
    startLatitude = startLatitude, startLongitude = startLongitude,
    endLatitude = endLatitude, endLongitude = endLongitude, energyContract = energyContract,
    lengthUnit = lengthUnit, temperatureUnit = temperatureUnit, speedMetricsSource = speedMetricsSource, speedSampleCount = speedSampleCount
)

/** Normalize legacy scalar consumers without throwing away the original provenance. */
fun DriveData.withQualifiedEnergy(): DriveData = copy(energyConsumedNet = netEnergyKwh, consumptionNet = efficiencyWhKm)

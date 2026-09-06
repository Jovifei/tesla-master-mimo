package com.matelink.data.repository

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class UnifiedHistory(
    val context: VehicleContext,
    val drives: List<DriveData>,
    val charges: List<ChargeData>,
    val drivesFromRemote: Boolean,
    val chargesFromRemote: Boolean,
    val fetchedAt: Instant = Instant.now()
)

internal const val HISTORY_IDENTITY_UNAVAILABLE = "history_identity_unavailable"

internal fun historyIdentityUnavailableError(): ApiResult.Error =
    ApiResult.Error(
        message = HISTORY_IDENTITY_UNAVAILABLE,
        details = HISTORY_IDENTITY_UNAVAILABLE,
        kind = ApiErrorKind.CONFIGURATION
    )

/** One read path for remote history plus the vehicle-scoped Room cache. */
@Singleton
class UnifiedHistoryRepository @Inject constructor(
    private val teslamateRepository: TeslamateRepository,
    private val vehicleContextRepository: VehicleContextRepository,
    private val driveSummaryDao: DriveSummaryDao,
    private val chargeSummaryDao: ChargeSummaryDao
) {
    suspend fun load(
        remoteApiCarId: Int,
        startDate: String? = null,
        endDate: String? = null
    ): ApiResult<UnifiedHistory> {
        val carResult = teslamateRepository.getCars()
        val car = when (carResult) {
            is ApiResult.Success -> carResult.data.firstOrNull { it.carId == remoteApiCarId }
            is ApiResult.Error -> null
        }
        if (car == null) {
            return when (carResult) {
                is ApiResult.Error -> carResult
                is ApiResult.Success -> ApiResult.Error(message = "vehicle_not_found", code = 404)
            }
        }
        val context = try {
            vehicleContextRepository.resolve(car)
        } catch (_: HistoryIdentityUnavailableException) {
            return historyIdentityUnavailableError()
        }
        val localDrives = if (startDate != null && endDate != null) {
            driveSummaryDao.getDrivesInRange(context.localHistoryCarId, startDate, endDate)
        } else {
            driveSummaryDao.getAllChronological(context.localHistoryCarId)
        }
        val localCharges = if (startDate != null && endDate != null) {
            chargeSummaryDao.getChargesInRange(context.localHistoryCarId, startDate, endDate)
        } else {
            chargeSummaryDao.getAllForCar(context.localHistoryCarId)
        }

        val remoteDrives = teslamateRepository.getDrives(context.remoteApiCarId, startDate, endDate)
        val remoteCharges = teslamateRepository.getCharges(context.remoteApiCarId, startDate, endDate)
        val drives = when (remoteDrives) {
            is ApiResult.Success -> {
                val merged = mergeDrives(remoteDrives.data, localDrives.map { it.toAnalysisDriveData() })
                merged.mapNotNull { it.toLocalSummary(context.localHistoryCarId) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { driveSummaryDao.upsertAll(it) }
                merged
            }
            is ApiResult.Error -> localDrives.map { it.toAnalysisDriveData() }.sortedByDescending { it.startDate }
        }
        val charges = when (remoteCharges) {
            is ApiResult.Success -> {
                val merged = mergeCharges(remoteCharges.data, localCharges.map { it.toAnalysisChargeData() })
                merged.mapNotNull { it.toLocalSummary(context.localHistoryCarId) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { chargeSummaryDao.upsertAll(it) }
                merged
            }
            is ApiResult.Error -> localCharges.map { it.toAnalysisChargeData() }.sortedByDescending { it.startDate }
        }

        if (drives.isEmpty() && charges.isEmpty() && carResult is ApiResult.Error) return carResult
        if (drives.isEmpty() && charges.isEmpty() && remoteDrives is ApiResult.Error) return remoteDrives
        if (drives.isEmpty() && charges.isEmpty() && remoteCharges is ApiResult.Error) return remoteCharges
        return ApiResult.Success(
            UnifiedHistory(
                context = context,
                drives = drives,
                charges = charges,
                drivesFromRemote = remoteDrives is ApiResult.Success,
                chargesFromRemote = remoteCharges is ApiResult.Success
            )
        )
    }

    companion object {
        /** A successful empty response is not allowed to hide a local archive. */
        fun remoteEmptyKeepsLocal(
            remote: List<DriveData>,
            local: List<DriveData>
        ): List<DriveData> = mergeDrives(remote, local)

        fun mergeDrives(remote: List<DriveData>, local: List<DriveData>): List<DriveData> {
            val localById = local.associateBy { it.driveId }
            val merged = remote.map { it.mergeWith(localById[it.driveId]) }
            return (merged + local.filter { cached -> remote.none { it.driveId == cached.driveId } })
                .sortedByDescending { it.startDate }
        }

        fun mergeCharges(remote: List<ChargeData>, local: List<ChargeData>): List<ChargeData> {
            val localById = local.associateBy { it.chargeId }
            val merged = remote.map { it.mergeWith(localById[it.chargeId]) }
            return (merged + local.filter { cached -> remote.none { it.chargeId == cached.chargeId } })
                .sortedByDescending { it.startDate }
        }
    }
}

private fun DriveData.mergeWith(cached: DriveData?): DriveData = cached?.let {
    copy(
        startDate = startDate ?: it.startDate,
        endDate = endDate ?: it.endDate,
        startAddress = startAddress ?: it.startAddress,
        endAddress = endAddress ?: it.endAddress,
        odometerDetails = odometerDetails.mergeWith(it.odometerDetails),
        durationMin = durationMin ?: it.durationMin,
        durationStr = durationStr ?: it.durationStr,
        speedMax = speedMax ?: it.speedMax,
        speedAvg = speedAvg ?: it.speedAvg,
        powerMax = powerMax ?: it.powerMax,
        powerMin = powerMin ?: it.powerMin,
        batteryDetails = batteryDetails.mergeWith(it.batteryDetails),
        rangeIdeal = rangeIdeal.mergeWith(it.rangeIdeal),
        rangeRated = rangeRated.mergeWith(it.rangeRated),
        outsideTempAvg = outsideTempAvg ?: it.outsideTempAvg,
        insideTempAvg = insideTempAvg ?: it.insideTempAvg,
        energyConsumedNet = energyConsumedNet ?: it.energyConsumedNet,
        consumptionNet = consumptionNet ?: it.consumptionNet,
        source = source ?: it.source,
        qualityState = qualityState ?: it.qualityState,
        qualityReason = qualityReason ?: it.qualityReason,
        startLatitude = startLatitude ?: it.startLatitude,
        startLongitude = startLongitude ?: it.startLongitude,
        endLatitude = endLatitude ?: it.endLatitude,
        endLongitude = endLongitude ?: it.endLongitude
    )
} ?: this

private fun ChargeData.mergeWith(cached: ChargeData?): ChargeData = cached?.let {
    copy(
        startDate = startDate ?: it.startDate,
        endDate = endDate ?: it.endDate,
        address = address ?: it.address,
        chargeEnergyAdded = chargeEnergyAdded ?: it.chargeEnergyAdded,
        chargeEnergyUsed = chargeEnergyUsed ?: it.chargeEnergyUsed,
        cost = cost ?: it.cost,
        durationMin = durationMin ?: it.durationMin,
        durationStr = durationStr ?: it.durationStr,
        batteryDetails = batteryDetails.mergeWith(it.batteryDetails),
        rangeIdeal = rangeIdeal.mergeWith(it.rangeIdeal),
        rangeRated = rangeRated.mergeWith(it.rangeRated),
        outsideTempAvg = outsideTempAvg ?: it.outsideTempAvg,
        odometer = odometer ?: it.odometer,
        latitude = latitude ?: it.latitude,
        longitude = longitude ?: it.longitude,
        source = source ?: it.source,
        qualityState = qualityState ?: it.qualityState,
        qualityReason = qualityReason ?: it.qualityReason
    )
} ?: this

private fun com.matelink.data.api.models.DriveOdometerDetails?.mergeWith(
    cached: com.matelink.data.api.models.DriveOdometerDetails?
) = when {
    this == null -> cached
    cached == null -> this
    else -> copy(
        odometerStart = odometerStart ?: cached.odometerStart,
        odometerEnd = odometerEnd ?: cached.odometerEnd,
        distance = distance ?: cached.distance
    )
}

private fun com.matelink.data.api.models.DriveBatteryDetails?.mergeWith(
    cached: com.matelink.data.api.models.DriveBatteryDetails?
) = when {
    this == null -> cached
    cached == null -> this
    else -> copy(
        startBatteryLevel = startBatteryLevel ?: cached.startBatteryLevel,
        endBatteryLevel = endBatteryLevel ?: cached.endBatteryLevel,
        isRangeIdeal = isRangeIdeal ?: cached.isRangeIdeal
    )
}

private fun com.matelink.data.api.models.DriveRange?.mergeWith(
    cached: com.matelink.data.api.models.DriveRange?
) = when {
    this == null -> cached
    cached == null -> this
    else -> copy(
        startRange = startRange ?: cached.startRange,
        endRange = endRange ?: cached.endRange,
        rangeDiff = rangeDiff ?: cached.rangeDiff
    )
}

private fun com.matelink.data.api.models.ChargeBatteryDetails?.mergeWith(
    cached: com.matelink.data.api.models.ChargeBatteryDetails?
) = when {
    this == null -> cached
    cached == null -> this
    else -> copy(
        startBatteryLevel = startBatteryLevel ?: cached.startBatteryLevel,
        endBatteryLevel = endBatteryLevel ?: cached.endBatteryLevel,
        currentBatteryLevel = currentBatteryLevel ?: cached.currentBatteryLevel
    )
}

private fun com.matelink.data.api.models.ChargeRange?.mergeWith(
    cached: com.matelink.data.api.models.ChargeRange?
) = when {
    this == null -> cached
    cached == null -> this
    else -> copy(
        startRange = startRange ?: cached.startRange,
        endRange = endRange ?: cached.endRange
    )
}

private fun DriveData.toLocalSummary(historyCarId: Int): DriveSummary? {
    val start = startDate ?: return null
    val end = endDate ?: return null
    val duration = durationMin ?: return null
    val odometer = odometerDetails ?: return null
    val distance = odometer.distance ?: return null
    val maxSpeed = speedMax ?: return null
    val averageSpeed = speedAvg ?: return null
    val maxPower = powerMax ?: return null
    val minPower = powerMin ?: return null
    val startBattery = batteryDetails?.startBatteryLevel ?: return null
    val endBattery = batteryDetails.endBatteryLevel ?: return null
    return DriveSummary(
        driveId = driveId,
        carId = historyCarId,
        startDate = start,
        endDate = end,
        durationMin = duration,
        startAddress = startAddress?.takeIf { !it.contains("°N") && it != "30.27°N, 120.15°E" && it != "杭州市西湖区西溪路" } ?: "",
        endAddress = endAddress?.takeIf { !it.contains("°N") && it != "30.27°N, 120.15°E" && it != "杭州市西湖区西溪路" } ?: "",
        distance = distance,
        speedMax = maxSpeed,
        speedAvg = averageSpeed.toInt(),
        powerMax = maxPower,
        powerMin = minPower,
        startBatteryLevel = startBattery,
        endBatteryLevel = endBattery,
        outsideTempAvg = outsideTempAvg,
        insideTempAvg = insideTempAvg,
        energyConsumed = energyConsumedNet,
        efficiency = efficiencyWhKm,
        energySource = energyConsumedNet?.takeIf { it.isFinite() && it >= 0.0 }?.let { "api" },
        apiEvidence = HistorySummaryEvidenceCodec.encode(this),
        qualityState = qualityState ?: "incomplete",
        qualityReason = qualityReason ?: "remote_quality_unavailable"
    )
}

private fun ChargeData.toLocalSummary(historyCarId: Int): ChargeSummary? {
    val start = startDate ?: return null
    val end = endDate ?: return null
    val duration = durationMin ?: return null
    val latitudeValue = latitude ?: return null
    val longitudeValue = longitude ?: return null
    val energy = chargeEnergyAdded ?: return null
    val startBattery = batteryDetails?.startBatteryLevel ?: return null
    val endBattery = batteryDetails.endBatteryLevel ?: return null
    val odometerValue = odometer ?: return null
    return ChargeSummary(
        chargeId = chargeId,
        carId = historyCarId,
        startDate = start,
        endDate = end,
        durationMin = duration,
        address = address ?: "",
        latitude = latitudeValue,
        longitude = longitudeValue,
        energyAdded = energy,
        energyUsed = chargeEnergyUsed,
        cost = cost,
        startBatteryLevel = startBattery,
        endBatteryLevel = endBattery,
        outsideTempAvg = outsideTempAvg,
        odometer = odometerValue,
        apiEvidence = HistorySummaryEvidenceCodec.encode(this),
        qualityState = qualityState ?: "incomplete",
        qualityReason = qualityReason ?: "remote_quality_unavailable"
    )
}

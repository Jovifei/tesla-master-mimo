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
    val fetchedAt: Instant = Instant.now(),
    val drivesSyncError: String? = null,
    val chargesSyncError: String? = null
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
        val readScope = try { vehicleContextRepository.captureReadScope() }
            catch (_: HistoryIdentityUnavailableException) { return historyIdentityUnavailableError() }
        suspend fun scopeUnchanged(): Boolean = try {
            vehicleContextRepository.captureReadScope() == readScope
        } catch (_: HistoryIdentityUnavailableException) { false }
        val carResult = teslamateRepository.getCars()
        if (!scopeUnchanged()) return historyIdentityUnavailableError()
        val car = when (carResult) {
            is ApiResult.Success -> carResult.data.firstOrNull { it.carId == remoteApiCarId }
            is ApiResult.Error -> null
        }
        val context = try {
            if (car != null) {
                vehicleContextRepository.resolve(car, readScope)
            } else {
                vehicleContextRepository.cachedContextForRemote(remoteApiCarId, readScope)
                    ?: return when (carResult) {
                        is ApiResult.Error -> carResult
                        is ApiResult.Success -> ApiResult.Error(message = "vehicle_not_found", code = 404)
                    }
            }
        } catch (_: HistoryIdentityUnavailableException) {
            return historyIdentityUnavailableError()
        }
        // History lists include incomplete legacy summaries. Analytics-only DAO
        // range queries must not silently hide those records on an offline phone.
        val localDrives = driveSummaryDao.getAllChronological(context.localHistoryCarId)
            .filter { historyInRange(it.startDate, startDate, endDate) }
        val localCharges = chargeSummaryDao.getAllForCar(context.localHistoryCarId)
            .filter { historyInRange(it.startDate, startDate, endDate) }
        val unavailable = ApiResult.Error("vehicle_discovery_unavailable", kind = ApiErrorKind.NETWORK)
        val remoteDrives = if (car != null) {
            loadHistoryPages(id = DriveData::driveId) { page ->
                if (!scopeUnchanged()) return@loadHistoryPages historyIdentityUnavailableError()
                teslamateRepository.getDrives(context.remoteApiCarId, startDate, endDate, page = page, show = 50)
            }
        } else HistoryPageLoad<DriveData>(emptyList(), unavailable)
        val remoteCharges = if (car != null) {
            loadHistoryPages(id = ChargeData::chargeId) { page ->
                if (!scopeUnchanged()) return@loadHistoryPages historyIdentityUnavailableError()
                teslamateRepository.getCharges(context.remoteApiCarId, startDate, endDate, page = page, show = 50)
            }
        } else HistoryPageLoad<ChargeData>(emptyList(), unavailable)

        if (!scopeUnchanged()) return historyIdentityUnavailableError()
        val drives = mergeDrives(remoteDrives.items.map { it.withLegacyRemoteQuality() }, localDrives.map { it.toAnalysisDriveData() })
        val charges = mergeCharges(remoteCharges.items.map { it.withLegacyRemoteQuality() }, localCharges.map { it.toAnalysisChargeData() })
        // Never delete local history just because it is outside the cloud window.
        // Persist even successfully downloaded pages preceding a later failure.
        driveSummaryDao.upsertPreservingEvidence(drives.mapNotNull { it.toLocalSummary(context.localHistoryCarId) })
        chargeSummaryDao.upsertPreservingEvidence(charges.mapNotNull { it.toLocalSummary(context.localHistoryCarId) })

        if (drives.isEmpty() && charges.isEmpty() && carResult is ApiResult.Error) return carResult
        if (drives.isEmpty() && charges.isEmpty()) {
            remoteDrives.error?.let { return it }
            remoteCharges.error?.let { return it }
        }
        return ApiResult.Success(
            UnifiedHistory(
                context = context,
                drives = drives,
                charges = charges,
                drivesFromRemote = remoteDrives.error == null,
                chargesFromRemote = remoteCharges.error == null,
                drivesSyncError = remoteDrives.error?.let { if (remoteDrives.items.isEmpty()) "history_cached" else "history_partial" },
                chargesSyncError = remoteCharges.error?.let { if (remoteCharges.items.isEmpty()) "history_cached" else "history_partial" }
            )
        )
    }

    companion object {
        /** A successful empty response is not allowed to hide a local archive. */
        fun remoteEmptyKeepsLocal(
            remote: List<DriveData>,
            local: List<DriveData>
        ): List<DriveData> {
            val merged = mergeDrives(remote, local)
            return merged
        }

        fun remoteEmptyKeepsLocalCharges(remote: List<ChargeData>, local: List<ChargeData>): List<ChargeData> {
            val merged = mergeCharges(remote, local)
            return merged
        }

        fun mergeDrives(remote: List<DriveData>, local: List<DriveData>): List<DriveData> {
            val localById = local.associateBy { it.driveId }
            val merged = remote.map { drive ->
                drive.mergeWith(localById[drive.driveId] ?: local.firstOrNull { drive.sameSession(it) })
            }
            return (merged + local.filter { cached -> remote.none { it.driveId == cached.driveId || it.sameSession(cached) } })
                .sortedByDescending { it.startDate }
        }

        fun mergeCharges(remote: List<ChargeData>, local: List<ChargeData>): List<ChargeData> {
            val localById = local.associateBy { it.chargeId }
            val merged = remote.map { charge ->
                charge.mergeWith(localById[charge.chargeId] ?: local.firstOrNull { charge.sameSession(it) })
            }
            return (merged + local.filter { cached -> remote.none { it.chargeId == cached.chargeId || it.sameSession(cached) } })
                .sortedByDescending { it.startDate }
        }
    }
}

private fun DriveData.sameSession(other: DriveData): Boolean =
    sameHistorySession(startDate, endDate, other.startDate, other.endDate)

private fun ChargeData.sameSession(other: ChargeData): Boolean =
    sameHistorySession(startDate, endDate, other.startDate, other.endDate)

private fun DriveData.withLegacyRemoteQuality(): DriveData =
    if (qualityState == null && source != "local_import") copy(qualityState = "observed", qualityReason = "legacy_remote_api") else this

private fun ChargeData.withLegacyRemoteQuality(): ChargeData =
    if (qualityState == null && source != "local_import") copy(qualityState = "observed", qualityReason = "legacy_remote_api") else this

private fun DriveData.mergeWith(cached: DriveData?): DriveData = cached?.let {
    if (qualityState == "quarantined") return this
    if (hasTrustedHistoryEvidence(it.qualityState, it.source) && !hasTrustedHistoryEvidence(qualityState, source)) return it.copy(driveId = driveId)
    if (hasTrustedHistoryEvidence(qualityState, source) && !hasTrustedHistoryEvidence(it.qualityState, it.source)) return this
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
    if (qualityState == "quarantined") return this
    if (hasTrustedHistoryEvidence(it.qualityState, it.source) && !hasTrustedHistoryEvidence(qualityState, source)) return it.copy(chargeId = chargeId)
    if (hasTrustedHistoryEvidence(qualityState, source) && !hasTrustedHistoryEvidence(it.qualityState, it.source)) return this
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

internal fun DriveData.toLocalSummary(historyCarId: Int): DriveSummary? {
    val start = startDate ?: return null
    val end = endDate ?: return null
    return DriveSummary(
        driveId = driveId,
        carId = historyCarId,
        startDate = start,
        endDate = end,
        durationMin = durationMin ?: 0,
        startAddress = startAddress?.takeIf { !it.contains("°N") && it != "30.27°N, 120.15°E" && it != "杭州市西湖区西溪路" } ?: "",
        endAddress = endAddress?.takeIf { !it.contains("°N") && it != "30.27°N, 120.15°E" && it != "杭州市西湖区西溪路" } ?: "",
        distance = odometerDetails?.distance ?: 0.0,
        speedMax = speedMax ?: 0,
        speedAvg = speedAvg?.toInt() ?: 0,
        powerMax = powerMax ?: 0,
        powerMin = powerMin ?: 0,
        startBatteryLevel = batteryDetails?.startBatteryLevel ?: 0,
        endBatteryLevel = batteryDetails?.endBatteryLevel ?: 0,
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

internal fun ChargeData.toLocalSummary(historyCarId: Int): ChargeSummary? {
    val start = startDate ?: return null
    val end = endDate ?: return null
    return ChargeSummary(
        chargeId = chargeId,
        carId = historyCarId,
        startDate = start,
        endDate = end,
        durationMin = durationMin ?: 0,
        address = address ?: "",
        latitude = latitude ?: 0.0,
        longitude = longitude ?: 0.0,
        energyAdded = chargeEnergyAdded ?: 0.0,
        energyUsed = chargeEnergyUsed,
        cost = cost,
        startBatteryLevel = batteryDetails?.startBatteryLevel ?: 0,
        endBatteryLevel = batteryDetails?.endBatteryLevel ?: 0,
        outsideTempAvg = outsideTempAvg,
        odometer = odometer ?: 0.0,
        apiEvidence = HistorySummaryEvidenceCodec.encode(this),
        qualityState = qualityState ?: "incomplete",
        qualityReason = qualityReason ?: "remote_quality_unavailable"
    )
}

private fun hasTrustedHistoryEvidence(quality: String?, source: String?): Boolean =
    quality in setOf("observed", "derived") || (quality == null && source != "local_import")

/** Shared by foreground restore and background sync; @Upsert must not downgrade evidence. */
internal fun mergeStoredDrive(incoming: DriveSummary, cached: DriveSummary?): DriveSummary {
    if (cached == null) return incoming
    require(incoming.carId == cached.carId && incoming.driveId == cached.driveId)
    val data = UnifiedHistoryRepository.mergeDrives(listOf(incoming.toAnalysisDriveData()), listOf(cached.toAnalysisDriveData())).single()
    val merged = data.toLocalSummary(incoming.carId) ?: return cached
    return if (merged.energyConsumed != null && merged.energyConsumed == cached.energyConsumed) {
        merged.copy(energySource = cached.energySource ?: merged.energySource,
            energyCoverageSeconds = cached.energyCoverageSeconds, energyCoverageRatio = cached.energyCoverageRatio)
    } else merged
}

internal fun mergeStoredCharge(incoming: ChargeSummary, cached: ChargeSummary?): ChargeSummary {
    if (cached == null) return incoming
    require(incoming.carId == cached.carId && incoming.chargeId == cached.chargeId)
    return UnifiedHistoryRepository.mergeCharges(listOf(incoming.toAnalysisChargeData()), listOf(cached.toAnalysisChargeData()))
        .single().toLocalSummary(incoming.carId) ?: cached
}

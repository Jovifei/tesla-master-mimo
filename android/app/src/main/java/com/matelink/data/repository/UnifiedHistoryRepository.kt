package com.matelink.data.repository

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.HistoryContextData
import com.matelink.data.api.models.isValidFor
import com.matelink.data.api.models.validHistoryVehicleUid
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.analytics.withQualifiedEnergy
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import android.util.Log
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
    val chargesSyncError: String? = null,
    val localArchiveLinkPending: Boolean = false
)

internal const val HISTORY_IDENTITY_UNAVAILABLE = "history_identity_unavailable"

internal fun historyIdentityUnavailableError(): ApiResult.Error =
    ApiResult.Error(
        message = HISTORY_IDENTITY_UNAVAILABLE,
        details = HISTORY_IDENTITY_UNAVAILABLE,
        kind = ApiErrorKind.CONFIGURATION
    )

internal data class VerifiedHistoryReadContext(
    val scope: HistoryReadScope,
    val context: VehicleContext,
    val remoteAuthorized: Boolean,
    val identityError: ApiResult.Error?,
    val localArchiveLinkPending: Boolean
)

/** Narrow side-effect ports allow the actual load orchestration to be tested without Android services. */
internal data class HistoryReadDependencies(
    val captureScope: suspend () -> HistoryReadScope,
    val getCars: suspend () -> ApiResult<List<CarData>>,
    val getHistoryContext: suspend (Int) -> ApiResult<HistoryContextData>,
    val resolveCar: (CarData, HistoryReadScope) -> VehicleContext,
    val cachedContext: (Int, HistoryReadScope) -> VehicleContext?,
    val localDrives: suspend (Int) -> List<DriveSummary>,
    val localCharges: suspend (Int) -> List<ChargeSummary>,
    val getDrives: suspend (Int, String?, String?, Int) -> ApiResult<List<DriveData>>,
    val getCharges: suspend (Int, String?, String?, Int) -> ApiResult<List<ChargeData>>,
    val persistDrives: suspend (List<DriveSummary>) -> Unit,
    val persistCharges: suspend (List<ChargeSummary>) -> Unit,
    val reportFailure: (String, Instant, ApiResult.Error) -> Unit = { _, _, _ -> },
    val legacyLinkPending: (Int, HistoryReadScope, VehicleContext) -> Boolean = { _, _, _ -> false }
)

/** One read path for remote history plus the vehicle-scoped Room cache. */
@Singleton
class UnifiedHistoryRepository internal constructor(private val reads: HistoryReadDependencies) {
    @Inject constructor(
        teslamateRepository: TeslamateRepository,
        vehicleContextRepository: VehicleContextRepository,
        driveSummaryDao: DriveSummaryDao,
        chargeSummaryDao: ChargeSummaryDao
    ) : this(HistoryReadDependencies(
        captureScope = { vehicleContextRepository.captureReadScope() },
        getCars = { teslamateRepository.getCars() },
        getHistoryContext = { teslamateRepository.getHistoryContext(it) },
        resolveCar = { car, scope -> vehicleContextRepository.resolveVerifiedHistoryCar(car, scope) },
        cachedContext = { id, scope -> vehicleContextRepository.cachedVerifiedHistoryContext(id, scope) },
        localDrives = { driveSummaryDao.getAllChronological(it) },
        localCharges = { chargeSummaryDao.getAllForCar(it) },
        getDrives = { id, start, end, page -> teslamateRepository.getDrives(id, start, end, page = page, show = 50) },
        getCharges = { id, start, end, page -> teslamateRepository.getCharges(id, start, end, page = page, show = 50) },
        persistDrives = { driveSummaryDao.upsertPreservingEvidence(it) },
        persistCharges = { chargeSummaryDao.upsertPreservingEvidence(it) },
        reportFailure = { stage, requestedAt, error ->
            Log.w("HistorySync", historyFailureDiagnostic(stage, requestedAt, error))
        },
        legacyLinkPending = { id, scope, context -> vehicleContextRepository.hasUnlinkedLegacyHistoryContext(id, scope, context) }
    ))

    /** Identity-only read shared by lists and details; never loads history pages or aggregates. */
    internal suspend fun resolveContext(remoteApiCarId: Int): ApiResult<VerifiedHistoryReadContext> {
        currentCoroutineContext().ensureActive()
        val readScope = try { reads.captureScope() }
            catch (_: HistoryIdentityUnavailableException) { return historyIdentityUnavailableError() }
        suspend fun scopeUnchanged(): Boolean = try {
            currentCoroutineContext().ensureActive()
            reads.captureScope() == readScope
        } catch (_: HistoryIdentityUnavailableException) { false }
        fun recordFailure(stage: String, requestedAt: Instant, error: ApiResult.Error?) {
            if (error == null) return
            reads.reportFailure(stage, requestedAt, error)
        }
        suspend fun discoverCars(): ApiResult<List<CarData>> {
            val requestedAt = Instant.now()
            val result = reads.getCars()
            recordFailure("cars", requestedAt, result as? ApiResult.Error)
            return result
        }
        val discovered = if (readScope.source == HistoryConnectionSource.CLOUD) {
            val requestedAt = Instant.now()
            val result = reads.getHistoryContext(remoteApiCarId)
            if (!scopeUnchanged()) return historyIdentityUnavailableError()
            recordFailure("history_context", requestedAt, result as? ApiResult.Error)
            when (result) {
                is ApiResult.Success -> if (result.data.isValidFor(remoteApiCarId)) {
                    ApiResult.Success(listOf(CarData(remoteApiCarId, vehicleUid = result.data.vehicleUid)))
                } else {
                    ApiResult.Error("history_identity_response_invalid", code = 200, kind = ApiErrorKind.INVALID_RESPONSE)
                        .also { recordFailure("history_context", requestedAt, it) }
                }
                is ApiResult.Error -> if (result.code == 404) discoverCars() else result
            }
        } else discoverCars()
        val carResult = if (readScope.source == HistoryConnectionSource.CLOUD && discovered is ApiResult.Success &&
            discovered.data.any { it.carId == remoteApiCarId && !validHistoryVehicleUid(it.vehicleUid) }) {
            ApiResult.Error("history_identity_response_invalid", kind = ApiErrorKind.INVALID_RESPONSE)
        } else discovered
        if (!scopeUnchanged()) return historyIdentityUnavailableError()
        val car = when (carResult) {
            is ApiResult.Success -> carResult.data.firstOrNull { it.carId == remoteApiCarId }
            is ApiResult.Error -> null
        }
        val context = try {
            if (car != null) {
                reads.resolveCar(car, readScope)
            } else {
                reads.cachedContext(remoteApiCarId, readScope)
                    ?: return when (carResult) {
                        is ApiResult.Error -> carResult
                        is ApiResult.Success -> ApiResult.Error(message = "vehicle_not_found", code = 404)
                    }
            }
        } catch (_: HistoryIdentityUnavailableException) {
            return historyIdentityUnavailableError()
        }
        if (!scopeUnchanged()) return historyIdentityUnavailableError()
        return ApiResult.Success(VerifiedHistoryReadContext(readScope, context, car != null,
            carResult as? ApiResult.Error, reads.legacyLinkPending(remoteApiCarId, readScope, context)))
    }

    internal suspend fun isContextCurrent(resolved: VerifiedHistoryReadContext): Boolean {
        currentCoroutineContext().ensureActive()
        return try {
            reads.captureScope() == resolved.scope &&
                reads.cachedContext(resolved.context.remoteApiCarId, resolved.scope)?.localHistoryCarId == resolved.context.localHistoryCarId
        } catch (_: HistoryIdentityUnavailableException) { false }
    }

    suspend fun load(
        remoteApiCarId: Int,
        startDate: String? = null,
        endDate: String? = null
    ): ApiResult<UnifiedHistory> {
        val resolved = when (val result = resolveContext(remoteApiCarId)) {
            is ApiResult.Error -> return result
            is ApiResult.Success -> result.data
        }
        val context = resolved.context
        suspend fun scopeUnchanged() = isContextCurrent(resolved)
        fun recordFailure(stage: String, requestedAt: Instant, error: ApiResult.Error?) {
            if (error != null) reads.reportFailure(stage, requestedAt, error)
        }
        // History lists include incomplete legacy summaries. Analytics-only DAO
        // range queries must not silently hide those records on an offline phone.
        val localDrives = reads.localDrives(context.localHistoryCarId)
            .filter { historyInRange(it.startDate, startDate, endDate) }
        val localCharges = reads.localCharges(context.localHistoryCarId)
            .filter { historyInRange(it.startDate, startDate, endDate) }
        // Only a vehicle identity verified in this read authorizes remote history. Cached contexts
        // are origin-scoped and may preserve offline display, never authorize a numeric-ID fallback.
        val canReadHistory = resolved.remoteAuthorized
        val unavailable = resolved.identityError
            ?: ApiResult.Error("vehicle_discovery_unavailable", code = 404, kind = ApiErrorKind.CONFIGURATION)
        val remoteDrives = if (canReadHistory) {
            loadHistoryPages(id = DriveData::driveId) { page ->
                if (!scopeUnchanged()) return@loadHistoryPages historyIdentityUnavailableError()
                val requestedAt = Instant.now()
                val response = reads.getDrives(context.remoteApiCarId, startDate, endDate, page)
                recordFailure("drives", requestedAt, response as? ApiResult.Error)
                if (!scopeUnchanged()) return@loadHistoryPages historyIdentityUnavailableError()
                response
            }
        } else HistoryPageLoad<DriveData>(emptyList(), unavailable)
        val remoteCharges = if (canReadHistory) {
            loadHistoryPages(id = ChargeData::chargeId) { page ->
                if (!scopeUnchanged()) return@loadHistoryPages historyIdentityUnavailableError()
                val requestedAt = Instant.now()
                val response = reads.getCharges(context.remoteApiCarId, startDate, endDate, page)
                recordFailure("charges", requestedAt, response as? ApiResult.Error)
                if (!scopeUnchanged()) return@loadHistoryPages historyIdentityUnavailableError()
                response
            }
        } else HistoryPageLoad<ChargeData>(emptyList(), unavailable)

        if (!scopeUnchanged()) return historyIdentityUnavailableError()
        val drives = mergeDrives(remoteDrives.items.map { it.withLegacyRemoteQuality() }, localDrives.map { it.toAnalysisDriveData() })
        val charges = mergeCharges(remoteCharges.items.map { it.withLegacyRemoteQuality() }, localCharges.map { it.toAnalysisChargeData() })
        // Never delete local history just because it is outside the cloud window.
        // Persist even successfully downloaded pages preceding a later failure.
        reads.persistDrives(drives.mapNotNull { it.toLocalSummary(context.localHistoryCarId) })
        if (!scopeUnchanged()) return historyIdentityUnavailableError()
        reads.persistCharges(charges.mapNotNull { it.toLocalSummary(context.localHistoryCarId) })
        if (!scopeUnchanged()) return historyIdentityUnavailableError()

        if (drives.isEmpty() && charges.isEmpty() && !canReadHistory) resolved.identityError?.let { return it }
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
                chargesSyncError = remoteCharges.error?.let { if (remoteCharges.items.isEmpty()) "history_cached" else "history_partial" },
                localArchiveLinkPending = resolved.localArchiveLinkPending
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
            val canonical = mutableListOf<DriveData>()
            for (row in merged + local) {
                val index = canonical.indexOfFirst { it.driveId == row.driveId || it.sameSession(row) }
                if (index < 0) canonical += row else canonical[index] = canonical[index].mergeWith(row)
            }
            return canonical.map { it.withQualifiedEnergy() }.sortedByDescending { historyTimestamp(it.startDate) }
        }

        fun mergeCharges(remote: List<ChargeData>, local: List<ChargeData>): List<ChargeData> {
            val localById = local.associateBy { it.chargeId }
            val merged = remote.map { charge ->
                charge.mergeWith(localById[charge.chargeId] ?: local.firstOrNull { charge.sameSession(it) })
            }
            val canonical = mutableListOf<ChargeData>()
            for (row in merged + local) {
                val index = canonical.indexOfFirst { it.chargeId == row.chargeId || it.sameSession(row) }
                if (index < 0) canonical += row else canonical[index] = canonical[index].mergeWith(row)
            }
            return canonical.map { it.withQualifiedEnergy() }.sortedByDescending { historyTimestamp(it.startDate) }
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
        energyContract = energyContract ?: it.energyContract,
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
        energyContract = energyContract ?: it.energyContract,
        chargeType = chargeType ?: it.chargeType,
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

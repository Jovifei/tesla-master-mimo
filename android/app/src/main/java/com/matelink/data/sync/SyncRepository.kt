package com.matelink.data.sync

import android.util.Log
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.ConnectionModeStore
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.CompletedTripNotificationProcessor
import com.matelink.data.local.TripNotificationStateStore
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.domain.analytics.PaginationGuard
import com.matelink.domain.analytics.DriveEnergyResolver
import com.matelink.domain.analytics.DrivePowerSample
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.allowsExternalGeocoding
import com.matelink.data.repository.GeocodingRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.notification.TripNotificationManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates syncing of drives and charges data from TeslaMate API to local database.
 */
internal class DriveSummarySyncAccumulator {
    private var seenIds = emptySet<Int>()
    private val collectedSummaries = mutableListOf<DriveSummary>()

    val summaries: List<DriveSummary>
        get() = collectedSummaries

    /** Returns whether the synchronizer must fetch another page. */
    fun addPage(pageIds: List<Int>, pageSummaries: List<DriveSummary>): Boolean {
        collectedSummaries += pageSummaries
        val decision = PaginationGuard.evaluate(
            pageSize = 50,
            seenIds = seenIds,
            pageIds = pageIds
        )
        seenIds = decision.seenIds
        return !decision.stop
    }
}

internal sealed interface DriveSummaryPageResult {
    data class Success(
        val sourceIds: List<Int>,
        val summaries: List<DriveSummary>
    ) : DriveSummaryPageResult

    data object Failure : DriveSummaryPageResult
}

/** Runs the page-completion boundary before completed-trip notifications are considered. */
internal class DriveSummarySyncRunner(
    private val fetchPage: suspend (page: Int) -> DriveSummaryPageResult,
    private val persistPage: suspend (List<DriveSummary>) -> Unit,
    private val onCompleted: suspend (List<DriveSummary>) -> Unit
) {
    suspend fun sync(): Boolean {
        val accumulator = DriveSummarySyncAccumulator()
        var page = 1
        var hasMore = true
        while (hasMore) {
            when (val result = fetchPage(page)) {
                DriveSummaryPageResult.Failure -> return false
                is DriveSummaryPageResult.Success -> {
                    if (result.sourceIds.isEmpty()) {
                        hasMore = false
                    } else {
                        persistPage(result.summaries)
                        hasMore = accumulator.addPage(result.sourceIds, result.summaries)
                        if (hasMore) page++
                    }
                }
            }
        }
        onCompleted(accumulator.summaries)
        return true
    }
}

@Singleton
class SyncRepository @Inject constructor(
    private val teslamateRepository: TeslamateRepository,
    private val driveSummaryDao: DriveSummaryDao,
    private val chargeSummaryDao: ChargeSummaryDao,
    private val aggregateDao: AggregateDao,
    private val syncManager: SyncManager,
    private val geocodingRepository: GeocodingRepository,
    private val connectionModeStore: ConnectionModeStore,
    private val historyMetadataStore: HistoryMetadataStore,
    private val tripNotificationStateStore: TripNotificationStateStore,
    private val tripNotificationManager: TripNotificationManager,
    private val vehicleContextRepository: VehicleContextRepository
) {
    companion object {
        private const val TAG = "SyncRepository"
    }

    private val tripNotificationProcessor = CompletedTripNotificationProcessor(
        tripNotificationStateStore,
        tripNotificationManager
    )

    /**
     * Sync all data for a car. Returns true if successful, false on network error.
     */
    suspend fun syncCar(carId: Int): Boolean {
        val car = when (val result = teslamateRepository.getCars()) {
            is ApiResult.Success -> result.data.firstOrNull { it.carId == carId }
            is ApiResult.Error -> null
        } ?: return false
        val context = vehicleContextRepository.resolve(car)
        val historyCarId = context.localHistoryCarId
        Log.d(TAG, "Starting sync for car $historyCarId")

        // Phase 0: Upload local history to the cloud (Tesla Cloud mode only) so a
        // re-login can sync previously-collected data back. Best-effort: a failed
        // upload must not block the cloud→local pull.
        if (connectionModeStore.current() == com.matelink.data.local.ConnectionMode.TESLA_CLOUD) {
            try {
                uploadLocalHistory(context.remoteApiCarId, historyCarId)
            } catch (e: Exception) {
                Log.w(TAG, "History upload failed for car $historyCarId", e)
            }
        }

        // Phase 1: Sync summaries
        syncManager.updateSummaryProgress(historyCarId)

        val drivesSynced = syncDriveSummaries(context.remoteApiCarId, historyCarId)
        if (!drivesSynced) return false

        val chargesSynced = syncChargeSummaries(context.remoteApiCarId, historyCarId)
        if (!chargesSynced) return false

        syncManager.markSummariesComplete(historyCarId)

        // Phase 2: Sync details
        syncDriveDetails(context.remoteApiCarId, historyCarId)
        syncChargeDetails(context.remoteApiCarId, historyCarId)

        // Phase 3: Enqueue geocoding for new locations
        enqueueGeocoding(historyCarId)

        syncManager.markSyncComplete(historyCarId)
        Log.d(TAG, "Sync complete for car $historyCarId")
        return true
    }

    private suspend fun syncDriveSummaries(remoteApiCarId: Int, historyCarId: Int): Boolean {
        return try {
            DriveSummarySyncRunner(
                fetchPage = { page ->
                    when (val result = teslamateRepository.getDrives(remoteApiCarId, page = page, show = 50)) {
                    is ApiResult.Success -> {
                        result.metadata?.let { historyMetadataStore.updateDrives(historyCarId, it) }
                        val drives = result.data
                        DriveSummaryPageResult.Success(
                            sourceIds = drives.map { it.id },
                            summaries = drives.mapNotNull { it.toSyncSummary(historyCarId) }
                        )
                    }
                    is ApiResult.Error -> {
                        Log.e(TAG, "Failed to sync drive summaries: ${result.message}")
                        DriveSummaryPageResult.Failure
                    }
                }
                },
                persistPage = driveSummaryDao::upsertAll,
                onCompleted = { summaries -> notifyCompletedDriveUpdates(historyCarId, summaries) }
            ).sync()
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing drive summaries", e)
            false
        }
    }

    private suspend fun notifyCompletedDriveUpdates(carId: Int, summaries: List<DriveSummary>) {
        tripNotificationProcessor.process(carId, summaries)
    }

    private suspend fun syncChargeSummaries(remoteApiCarId: Int, historyCarId: Int): Boolean {
        return try {
            var page = 1
            var hasMore = true
            var seenIds = emptySet<Int>()

            while (hasMore) {
                when (val result = teslamateRepository.getCharges(remoteApiCarId, page = page, show = 50)) {
                    is ApiResult.Success -> {
                        result.metadata?.let { historyMetadataStore.updateCharges(historyCarId, it) }
                        val charges = result.data
                        if (charges.isEmpty()) {
                            hasMore = false
                        } else {
                            val summaries = charges.mapNotNull { it.toSyncSummary(historyCarId) }
                            chargeSummaryDao.upsertAll(summaries)
                            val decision = PaginationGuard.evaluate(
                                pageSize = 50,
                                seenIds = seenIds,
                                pageIds = charges.map { it.chargeId }
                            )
                            seenIds = decision.seenIds
                            hasMore = !decision.stop
                            if (hasMore) page++
                        }
                    }
                    is ApiResult.Error -> {
                        Log.e(TAG, "Failed to sync charge summaries: ${result.message}")
                        return false
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing charge summaries", e)
            false
        }
    }

    private suspend fun syncDriveDetails(remoteApiCarId: Int, historyCarId: Int) {
        try {
            val unprocessedIds = driveSummaryDao.getUnprocessedDriveIds(historyCarId, com.matelink.data.local.entity.SchemaVersion.CURRENT)
            for (driveId in unprocessedIds) {
                val summary = driveSummaryDao.get(historyCarId, driveId) ?: continue
                try {
                    when (val result = teslamateRepository.getDriveDetail(remoteApiCarId, summary.driveId)) {
                        is ApiResult.Success -> {
                            val detail = result.data
                            val energy = DriveEnergyResolver.resolve(
                                apiEnergyKwh = detail.energyConsumedNet ?: summary.energyConsumed,
                                distanceKm = detail.distance ?: summary.distance,
                                samples = detail.positions.orEmpty().map {
                                    DrivePowerSample(it.date, it.power?.toDouble())
                                }
                            )
                            val coverageRatio = if ((detail.durationMin ?: summary.durationMin) > 0) {
                                (energy.coverageSeconds.toDouble() /
                                    ((detail.durationMin ?: summary.durationMin) * 60.0)).coerceIn(0.0, 1.0)
                            } else {
                                0.0
                            }
                            driveSummaryDao.upsert(summary.copy(
                                startAddress = detail.startAddress ?: summary.startAddress,
                                endAddress = detail.endAddress ?: summary.endAddress,
                                outsideTempAvg = detail.outsideTempAvg ?: summary.outsideTempAvg,
                                speedMax = detail.speedMax ?: summary.speedMax,
                                powerMax = detail.powerMax ?: summary.powerMax,
                                powerMin = detail.powerMin ?: summary.powerMin,
                                startBatteryLevel = detail.startBatteryLevel ?: summary.startBatteryLevel,
                                endBatteryLevel = detail.endBatteryLevel ?: summary.endBatteryLevel,
                                energyConsumed = energy.energyKwh ?: summary.energyConsumed,
                                efficiency = energy.efficiencyWhKm ?: summary.efficiency,
                                energySource = energy.source.name.lowercase(),
                                energyCoverageSeconds = energy.coverageSeconds,
                                energyCoverageRatio = coverageRatio
                            ))
                            aggregateDao.upsertDriveAggregate(
                                detail.toAggregate(carId = historyCarId, computedAt = System.currentTimeMillis())
                            )
                            syncManager.updateDriveDetailProgress(historyCarId, summary.driveId)
                        }
                        is ApiResult.Error -> {
                            Log.w(TAG, "Failed to sync drive detail ${summary.driveId}: ${result.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error syncing drive detail ${summary.driveId}", e)
                }
            }
            syncManager.markDriveDetailsComplete(historyCarId)
        } catch (e: Exception) {
            Log.e(TAG, "Error in drive detail sync", e)
        }
    }

    private suspend fun syncChargeDetails(remoteApiCarId: Int, historyCarId: Int) {
        try {
            val unprocessedIds = chargeSummaryDao.getUnprocessedChargeIds(historyCarId, com.matelink.data.local.entity.SchemaVersion.CURRENT)
            for (chargeId in unprocessedIds) {
                val summary = chargeSummaryDao.get(historyCarId, chargeId) ?: continue
                try {
                    when (val result = teslamateRepository.getChargeDetail(remoteApiCarId, summary.chargeId)) {
                        is ApiResult.Success -> {
                            val detail = result.data
                            chargeSummaryDao.upsert(summary.copy(
                                address = detail.address ?: summary.address,
                                outsideTempAvg = detail.outsideTempAvg ?: summary.outsideTempAvg,
                                startBatteryLevel = detail.startBatteryLevel ?: summary.startBatteryLevel,
                                endBatteryLevel = detail.endBatteryLevel ?: summary.endBatteryLevel
                            ))
                            aggregateDao.upsertChargeAggregate(
                                detail.toAggregate(carId = historyCarId, computedAt = System.currentTimeMillis())
                            )
                            syncManager.updateChargeDetailProgress(historyCarId, summary.chargeId)
                        }
                        is ApiResult.Error -> {
                            Log.w(TAG, "Failed to sync charge detail ${summary.chargeId}: ${result.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error syncing charge detail ${summary.chargeId}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in charge detail sync", e)
        }
    }

    private suspend fun enqueueGeocoding(carId: Int) {
        try {
            if (!allowsExternalGeocoding(connectionModeStore.current())) {
                Log.d(TAG, "Skipping external geocoding for non-self-hosted mode")
                return
            }

            val driveLocations = aggregateDao.getDriveLocationsNeedingGeocode(carId)
            val chargeLocations = aggregateDao.getChargeLocationsNeedingGeocode(carId)
            val locations = (driveLocations + chargeLocations).map { it.toLatLon() }
            if (locations.isNotEmpty()) {
                geocodingRepository.enqueueLocationsForCar(carId, locations)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enqueuing geocoding", e)
        }
    }

    /**
     * Uploads locally-collected history (drive/charge summaries) to the cloud so
     * a later re-login can sync it back. Runs only in Tesla Cloud mode; in
     * self-hosted mode the server already owns the data and there is nothing to
     * upload. Route points are not persisted locally by design, so the payload
     * carries summaries only — trajectories are re-collected by Fleet Telemetry.
     */
    suspend fun uploadLocalHistory(remoteApiCarId: Int, historyCarId: Int): Boolean {
        if (connectionModeStore.current() != com.matelink.data.local.ConnectionMode.TESLA_CLOUD) {
            Log.d(TAG, "Skipping history upload: not in Tesla Cloud mode")
            return true
        }
        val drives = driveSummaryDao.getAllChronological(historyCarId).mapNotNull { it.toImportSession("drive") }
        val charges = chargeSummaryDao.getAllForCar(historyCarId).mapNotNull { it.toImportSession("charge") }
        if (drives.isEmpty() && charges.isEmpty()) {
            Log.d(TAG, "No local history to upload for car $historyCarId")
            return true
        }
        val request = com.matelink.data.api.models.HistoryImportRequest(
            drives = drives,
            charges = charges
        )
        return when (val result = teslamateRepository.uploadLocalHistory(remoteApiCarId, request)) {
            is ApiResult.Success -> {
                Log.d(TAG, "Uploaded ${result.data.importedDrives} drives, ${result.data.importedCharges} charges for car $historyCarId")
                true
            }
            is ApiResult.Error -> {
                Log.w(TAG, "History upload failed for car $historyCarId: ${result.message}")
                false
            }
        }
    }

}

internal fun DriveData.toSyncSummary(carId: Int): DriveSummary? {
    val start = startDate ?: return null
    val end = endDate ?: return null
    return DriveSummary(
        driveId = id,
        carId = carId,
        startDate = start,
        endDate = end,
        distance = distance ?: 0.0,
        durationMin = durationMin ?: 0,
        startAddress = startAddress ?: "",
        endAddress = endAddress ?: "",
        speedMax = speedMax ?: 0,
        speedAvg = speedAvg?.toInt() ?: 0,
        powerMax = powerMax ?: 0,
        powerMin = powerMin ?: 0,
        startBatteryLevel = startBatteryLevel ?: 0,
        endBatteryLevel = endBatteryLevel ?: 0,
        outsideTempAvg = outsideTempAvg,
        insideTempAvg = insideTempAvg,
        energyConsumed = energyConsumedNet,
        efficiency = efficiencyWhKm,
        energySource = energyConsumedNet?.takeIf { it > 0.0 }?.let { "api" },
        energyCoverageSeconds = 0,
        energyCoverageRatio = 0.0,
        apiEvidence = HistorySummaryEvidenceCodec.encode(this)
    )
}

internal fun ChargeData.toSyncSummary(carId: Int): ChargeSummary? {
    val start = startDate ?: return null
    val end = endDate ?: return null
    return ChargeSummary(
        chargeId = chargeId,
        carId = carId,
        startDate = start,
        endDate = end,
        durationMin = durationMin ?: 0,
        address = address ?: "",
        latitude = latitude ?: 0.0,
        longitude = longitude ?: 0.0,
        energyAdded = chargeEnergyAdded ?: 0.0,
        energyUsed = chargeEnergyUsed,
        cost = cost,
        startBatteryLevel = startBatteryLevel ?: 0,
        endBatteryLevel = endBatteryLevel ?: 0,
        outsideTempAvg = outsideTempAvg,
        odometer = odometer ?: 0.0,
        apiEvidence = HistorySummaryEvidenceCodec.encode(this)
    )
}

internal fun DriveSummary.toImportSession(kind: String): com.matelink.data.api.models.HistoryImportSession? {
    val started = normalizeImportTimestamp(startDate) ?: return null
    val ended = normalizeImportTimestamp(endDate) ?: return null
    return com.matelink.data.api.models.HistoryImportSession(
        sessionId = "local-$kind-$driveId",
        startedAt = started,
        endedAt = ended,
        odometerStart = null,
        odometerEnd = null,
        energyAdded = if (kind == "charge") null else energyConsumed,
        route = emptyList()
    )
}

internal fun ChargeSummary.toImportSession(kind: String): com.matelink.data.api.models.HistoryImportSession? {
    val started = normalizeImportTimestamp(startDate) ?: return null
    val ended = normalizeImportTimestamp(endDate) ?: return null
    return com.matelink.data.api.models.HistoryImportSession(
        sessionId = "local-$kind-$chargeId",
        startedAt = started,
        endedAt = ended,
        odometerStart = null,
        odometerEnd = odometer.takeIf { it > 0.0 },
        energyAdded = energyAdded,
        route = emptyList()
    )
}

/**
 * Normalizes a locally-stored timestamp string into RFC3339 for upload. The
 * local cache stores API-provided strings (usually ISO 8601), but some legacy
 * rows may carry offsets or no timezone; default to UTC when ambiguous.
 */
private fun normalizeImportTimestamp(value: String): String? {
    if (value.isBlank()) return null
    return try {
        val instant = java.time.Instant.parse(value)
        instant.toString()
    } catch (e: Exception) {
        // Fall back to OffsetDateTime parsing, then LocalDateTime assumed UTC.
        try {
            java.time.OffsetDateTime.parse(value).toInstant().toString()
        } catch (e2: Exception) {
            try {
                val local = java.time.LocalDateTime.parse(value)
                local.atOffset(java.time.ZoneOffset.UTC).toInstant().toString()
            } catch (e3: Exception) {
                null
            }
        }
    }
}

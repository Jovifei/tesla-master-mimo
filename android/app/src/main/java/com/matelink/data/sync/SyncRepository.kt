package com.matelink.data.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.ConnectionModeStore
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.TripNotificationStateStore
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.domain.analytics.PaginationGuard
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.resolveDriveEnergy
import com.matelink.domain.analytics.withResolvedDriveEnergy
import com.matelink.domain.analytics.withDetailEvidence
import com.matelink.domain.analytics.withQualifiedEnergy
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.GeocodingRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.notification.TripNotificationManager
import javax.inject.Inject
import javax.inject.Singleton

internal class DriveSummarySyncAccumulator {
    private var seenIds = emptySet<Int>()
    private val collectedSummaries = mutableListOf<DriveSummary>()
    val summaries: List<DriveSummary> get() = collectedSummaries
    fun addPage(pageIds: List<Int>, pageSummaries: List<DriveSummary>): Boolean {
        collectedSummaries += pageSummaries
        val decision = PaginationGuard.evaluate(pageSize = 50, seenIds = seenIds, pageIds = pageIds)
        seenIds = decision.seenIds
        return !decision.stop
    }
}
internal sealed interface DriveSummaryPageResult {
    data class Success(val sourceIds: List<Int>, val summaries: List<DriveSummary>) : DriveSummaryPageResult
    data object Failure : DriveSummaryPageResult
}
/** Completion publication remains behind the full-page success boundary. */
internal class DriveSummarySyncRunner(
    private val fetchPage: suspend (page: Int) -> DriveSummaryPageResult,
    private val persistPage: suspend (List<DriveSummary>) -> Unit,
    private val onCompleted: suspend (List<DriveSummary>) -> Unit
) {
    suspend fun sync(): Boolean {
        val accumulator = DriveSummarySyncAccumulator()
        var page = 1
        while (true) {
            when (val result = fetchPage(page)) {
                DriveSummaryPageResult.Failure -> return false
                is DriveSummaryPageResult.Success -> {
                    if (result.sourceIds.isEmpty()) break
                    persistPage(result.summaries)
                    if (!accumulator.addPage(result.sourceIds, result.summaries)) break
                    page++
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
    private val vehicleContextRepository: VehicleContextRepository,
    private val chargeEventStore: com.matelink.data.local.CompletedChargeEventStore,
    private val chargeNotifier: com.matelink.notification.CompletedChargeNotificationManager
) {
    companion object { private const val TAG = "SyncRepository" }
    private val chargeDeliveryMutex = Mutex()
    private suspend fun requireScope(expected: HistoryReadScope) {
        val actual = runCatching { vehicleContextRepository.captureReadScope() }.getOrNull()
        if (actual != expected) throw CancellationException("History account or server changed")
    }
    suspend fun syncCar(carId: Int): Boolean {
        val scope = vehicleContextRepository.captureReadScope()
        val car = when (val result = teslamateRepository.getCars()) {
            is ApiResult.Success -> result.data.firstOrNull { it.carId == carId }
            is ApiResult.Error -> null
        } ?: return false
        requireScope(scope)
        val context = vehicleContextRepository.resolve(car, scope)
        val historyCarId = context.localHistoryCarId
        // Existing cloud archive path; no new historical repair/backfill.
        if (connectionModeStore.current() == com.matelink.data.local.ConnectionMode.TESLA_CLOUD) {
            try { uploadLocalHistory(context.remoteApiCarId, historyCarId) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { Log.w(TAG, "event=history_upload_failed") }
        }
        syncManager.updateSummaryProgress(historyCarId)
        val drivesSynced = syncDriveSummaries(context.remoteApiCarId, historyCarId, scope)
        val chargesSynced = syncChargeSummaries(context.remoteApiCarId, historyCarId, scope)
        if (!drivesSynced || !chargesSynced) return false
        syncManager.markSummariesComplete(historyCarId)
        val driveDetailsSynced = syncDriveDetails(context.remoteApiCarId, historyCarId, scope)
        val chargeDetailsSynced = syncChargeDetails(context.remoteApiCarId, historyCarId, scope)
        requireScope(scope)
        enqueueGeocoding(historyCarId)
        if (!driveDetailsSynced || !chargeDetailsSynced) return false
        syncManager.markSyncComplete(historyCarId)
        return true
    }
    private suspend fun syncDriveSummaries(remoteApiCarId: Int, historyCarId: Int, scope: HistoryReadScope): Boolean = try {
        DriveSummarySyncRunner(
            fetchPage = { page ->
                requireScope(scope)
                val result = teslamateRepository.getDrives(remoteApiCarId, page = page, show = 50)
                requireScope(scope)
                when (result) {
                    is ApiResult.Success -> {
                        result.metadata?.let { historyMetadataStore.updateDrives(historyCarId, it) }
                        DriveSummaryPageResult.Success(result.data.map { it.id }, result.data.mapNotNull { it.toSyncSummary(historyCarId) })
                    }
                    is ApiResult.Error -> DriveSummaryPageResult.Failure
                }
            },
            persistPage = { rows -> requireScope(scope); driveSummaryDao.upsertPreservingEvidence(rows) },
            onCompleted = { summaries -> notifyCompletedDriveUpdates(historyCarId, remoteApiCarId, summaries, scope) }
        ).sync()
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        Log.w(TAG, "event=drive_summary_sync_failed")
        false
    }
    private suspend fun notifyCompletedDriveUpdates(carId: Int, remoteCarId: Int, summaries: List<DriveSummary>, scope: HistoryReadScope) {
        requireScope(scope)
        chargeEventStore.record(carId, remoteCarId, summaries.filter { it.qualityState != "quarantined" }
            .map { it.driveId to it.endDate }, "drive")
        chargeDeliveryMutex.withLock {
            chargeEventStore.events(carId, "drive").first().filter { !it.systemConsumed }.forEach { event ->
                requireScope(scope)
                val summary = summaries.firstOrNull { it.driveId == event.chargeId } ?: return@forEach
                if (tripNotificationManager.showCompletedDrive(carId, summary)) chargeEventStore.consume(carId, event.chargeId, true, "drive")
            }
        }
    }
    private suspend fun syncChargeSummaries(remoteApiCarId: Int, historyCarId: Int, scope: HistoryReadScope): Boolean {
        return try {
            var page = 1
            var seenIds = emptySet<Int>()
            val completed = mutableListOf<ChargeSummary>()
            while (true) {
                requireScope(scope)
                val result = teslamateRepository.getCharges(remoteApiCarId, page = page, show = 50)
                requireScope(scope)
                when (result) {
                    is ApiResult.Success -> {
                        result.metadata?.let { historyMetadataStore.updateCharges(historyCarId, it) }
                        val charges = result.data
                        if (charges.isEmpty()) break
                        val summaries = charges.mapNotNull { it.toSyncSummary(historyCarId) }
                        completed += summaries
                        chargeSummaryDao.upsertPreservingEvidence(summaries)
                        val decision = PaginationGuard.evaluate(pageSize = 50, seenIds = seenIds, pageIds = charges.map { it.chargeId })
                        seenIds = decision.seenIds
                        if (decision.stop) break
                        page++
                    }
                    is ApiResult.Error -> return false
                }
            }
            requireScope(scope)
            chargeEventStore.record(historyCarId, remoteApiCarId, completed.filter {
                it.qualityState != "quarantined" && runCatching { java.time.Instant.parse(it.endDate) <= java.time.Instant.now() }.getOrDefault(false)
            }.map { it.chargeId to it.endDate })
            chargeDeliveryMutex.withLock {
                chargeEventStore.events(historyCarId).first().filter { !it.systemConsumed }.forEach { event ->
                    requireScope(scope)
                    if (chargeNotifier.show(event)) chargeEventStore.consume(historyCarId, event.chargeId, true)
                }
            }
            true
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            Log.w(TAG, "event=charge_summary_sync_failed")
            false
        }
    }
    private suspend fun syncDriveDetails(remoteApiCarId: Int, historyCarId: Int, scope: HistoryReadScope): Boolean = try {
        var complete = true
        val ids = driveSummaryDao.getUnprocessedDriveIds(historyCarId, com.matelink.data.local.entity.SchemaVersion.CURRENT)
        for (id in ids) {
            requireScope(scope)
            val summary = driveSummaryDao.get(historyCarId, id) ?: continue
            try {
                val result = teslamateRepository.getDriveDetail(remoteApiCarId, id)
                requireScope(scope)
                when (result) {
                    is ApiResult.Success -> {
                        val detail = result.data
                        val resolved = detail.resolveDriveEnergy()
                        driveSummaryDao.upsert(summary.withResolvedDriveEnergy(detail, resolved))
                        requireScope(scope)
                        aggregateDao.upsertDriveAggregate(detail.toAggregate(carId = historyCarId, computedAt = System.currentTimeMillis()))
                        syncManager.updateDriveDetailProgress(historyCarId, id)
                    }
                    is ApiResult.Error -> complete = false
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                complete = false
                Log.w(TAG, "event=drive_detail_sync_failed")
            }
        }
        if (complete) syncManager.markDriveDetailsComplete(historyCarId)
        complete
    } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    private suspend fun syncChargeDetails(remoteApiCarId: Int, historyCarId: Int, scope: HistoryReadScope): Boolean = try {
        var complete = true
        val ids = chargeSummaryDao.getUnprocessedChargeIds(historyCarId, com.matelink.data.local.entity.SchemaVersion.CURRENT)
        for (id in ids) {
            requireScope(scope)
            val summary = chargeSummaryDao.get(historyCarId, id) ?: continue
            try {
                val result = teslamateRepository.getChargeDetail(remoteApiCarId, id)
                requireScope(scope)
                when (result) {
                    is ApiResult.Success -> {
                        val detail = result.data.withQualifiedEnergy()
                        chargeSummaryDao.upsert(summary.withDetailEvidence(detail))
                        requireScope(scope)
                        aggregateDao.upsertChargeAggregate(detail.toAggregate(carId = historyCarId, computedAt = System.currentTimeMillis()))
                        syncManager.updateChargeDetailProgress(historyCarId, id)
                    }
                    is ApiResult.Error -> complete = false
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                complete = false
                Log.w(TAG, "event=charge_detail_sync_failed")
            }
        }
        complete
    } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    private suspend fun enqueueGeocoding(carId: Int) {
        try {
            if (!geocodingRepository.isExternalAllowed()) return
            val locations = (aggregateDao.getDriveLocationsNeedingGeocode(carId) + aggregateDao.getChargeLocationsNeedingGeocode(carId)).map { it.toLatLon() }
            if (locations.isNotEmpty()) geocodingRepository.enqueueLocationsForCar(carId, locations)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Log.w(TAG, "event=geocoding_enqueue_failed") }
    }
    /** Existing archive upload remains source-scoped; it cannot prove a Fleet event. */
    suspend fun uploadLocalHistory(remoteApiCarId: Int, historyCarId: Int): Boolean {
        val scope = vehicleContextRepository.captureReadScope()
        if (connectionModeStore.current() != com.matelink.data.local.ConnectionMode.TESLA_CLOUD) return true
        if (vehicleContextRepository.cachedContextForRemote(remoteApiCarId, scope)?.localHistoryCarId != historyCarId) return false
        val allDrives = driveSummaryDao.getAllChronological(historyCarId).mapNotNull { it.toImportSession("drive") }
        val allCharges = chargeSummaryDao.getAllForCar(historyCarId).mapNotNull { it.toImportSession("charge") }
        val bounded = HistoryUploadFilter.boundToLatestTwoDataDays(allDrives, allCharges)
        for (batch in HistoryUploadFilter.batchesForUpload(bounded.drives, bounded.charges)) {
            requireScope(scope)
            val result = teslamateRepository.uploadLocalHistory(remoteApiCarId,
                com.matelink.data.api.models.HistoryImportRequest(drives = batch.drives, charges = batch.charges))
            requireScope(scope)
            if (result is ApiResult.Error) return false
        }
        return true
    }
}

internal fun DriveData.toSyncSummary(carId: Int): DriveSummary? {
    val start = startDate ?: return null
    val end = endDate ?: return null
    val normalized = withQualifiedEnergy()
    val metric = energyContract?.netEnergy
    return DriveSummary(
        driveId = id, carId = carId, startDate = start, endDate = end,
        distance = distance?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0, durationMin = durationMin ?: 0,
        startAddress = startAddress.orEmpty(), endAddress = endAddress.orEmpty(),
        speedMax = speedMax ?: 0, speedAvg = speedAvg?.takeIf(Double::isFinite)?.toInt() ?: 0,
        powerMax = powerMax ?: 0, powerMin = powerMin ?: 0,
        startBatteryLevel = startBatteryLevel ?: 0, endBatteryLevel = endBatteryLevel ?: 0,
        outsideTempAvg = outsideTempAvg?.takeIf(Double::isFinite), insideTempAvg = insideTempAvg?.takeIf(Double::isFinite),
        energyConsumed = normalized.netEnergyKwh, efficiency = normalized.efficiencyWhKm,
        energySource = normalized.netEnergyKwh?.let { if (metric?.method == "drive_power_integral") "power_samples" else "api" },
        energyCoverageSeconds = metric?.coverageSeconds?.takeIf { it.isFinite() && it >= 0.0 }?.toLong() ?: 0L,
        energyCoverageRatio = metric?.coverageRatio?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.0,
        apiEvidence = HistorySummaryEvidenceCodec.encode(normalized),
        qualityState = qualityState ?: if (source == "local_import") "incomplete" else "observed",
        qualityReason = qualityReason ?: if (source == "local_import") "local_import_unverified" else "legacy_remote_api"
    )
}
internal fun ChargeData.toSyncSummary(carId: Int): ChargeSummary? {
    val start = startDate ?: return null
    val end = endDate ?: return null
    val normalized = withQualifiedEnergy()
    return ChargeSummary(
        chargeId = chargeId, carId = carId, startDate = start, endDate = end, durationMin = durationMin ?: 0,
        address = address.orEmpty(), latitude = latitude ?: 0.0, longitude = longitude ?: 0.0,
        energyAdded = normalized.batteryInputKwh ?: 0.0, energyUsed = normalized.inputEnergyKwh, cost = normalized.cost,
        startBatteryLevel = startBatteryLevel ?: 0, endBatteryLevel = endBatteryLevel ?: 0,
        outsideTempAvg = outsideTempAvg?.takeIf(Double::isFinite), odometer = odometer ?: 0.0,
        apiEvidence = HistorySummaryEvidenceCodec.encode(normalized),
        qualityState = qualityState ?: if (source == "local_import") "incomplete" else "observed",
        qualityReason = qualityReason ?: if (source == "local_import") "local_import_unverified" else "legacy_remote_api"
    )
}
internal fun DriveSummary.toImportSession(kind: String): com.matelink.data.api.models.HistoryImportSession? {
    if (qualityState != "observed" && qualityState != "derived") return null
    if (apiEvidence.isNullOrBlank() || energySource.isNullOrBlank()) return null
    val evidence = HistorySummaryEvidenceCodec.decodeDrive(apiEvidence) ?: return null
    if (evidence.source in setOf("telemetry_mqtt", "local_import")) return null
    val started = normalizeImportTimestamp(startDate) ?: return null
    val ended = normalizeImportTimestamp(endDate) ?: return null
    return com.matelink.data.api.models.HistoryImportSession(
        sessionId = "local-$kind-$driveId", startedAt = started, endedAt = ended,
        odometerStart = null, odometerEnd = null,
        // Legacy import has no net-discharge field. Drive net energy is not charged energy.
        energyAdded = null, route = emptyList()
    )
}
internal fun ChargeSummary.toImportSession(kind: String): com.matelink.data.api.models.HistoryImportSession? {
    if (qualityState != "observed" && qualityState != "derived") return null
    val evidence = apiEvidence?.let(HistorySummaryEvidenceCodec::decodeCharge) ?: return null
    if (evidence.source in setOf("telemetry_mqtt", "local_import")) return null
    val started = normalizeImportTimestamp(startDate) ?: return null
    val ended = normalizeImportTimestamp(endDate) ?: return null
    return com.matelink.data.api.models.HistoryImportSession(
        sessionId = "local-$kind-$chargeId", startedAt = started, endedAt = ended,
        odometerStart = null, odometerEnd = odometer.takeIf { it.isFinite() && it > 0.0 },
        energyAdded = evidence.batteryInputKwh, route = emptyList()
    )
}
/** Existing archive compatibility only, never used as live event time. */
private fun normalizeImportTimestamp(value: String): String? {
    if (value.isBlank()) return null
    return runCatching { java.time.Instant.parse(value).toString() }.getOrElse {
        runCatching { java.time.OffsetDateTime.parse(value).toInstant().toString() }.getOrElse {
            runCatching { java.time.LocalDateTime.parse(value).atOffset(java.time.ZoneOffset.UTC).toInstant().toString() }.getOrNull()
        }
    }
}

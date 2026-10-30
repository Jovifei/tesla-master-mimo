package com.matelink.data.sync

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.completionScopeKey
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
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.withDetailEvidence
import com.matelink.domain.analytics.withQualifiedEnergy
import com.matelink.domain.analytics.acceptsDetail
import com.matelink.domain.history.isAnalysisEligible
import com.matelink.domain.telemetry.usableVehicleCoordinates
import com.matelink.data.api.models.driveEnergySource
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.HistoryDetailRepository
import com.matelink.data.repository.HistoryDetailPointSaveRejected
import com.matelink.data.repository.persistVerifiedChargeDetail
import com.matelink.data.repository.persistVerifiedDriveDetail
import com.matelink.data.repository.GeocodingRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.analytics.toRawAnalysisChargeData
import com.matelink.domain.analytics.toRawAnalysisDriveData
import com.matelink.util.parseIsoInstant
import com.matelink.notification.TripNotificationManager
import javax.inject.Inject
import javax.inject.Singleton

internal class DriveSummarySyncAccumulator(private val guard: CompletionPageGuard = CompletionPageGuard()) {
    private val collectedSummaries = mutableListOf<DriveSummary>()
    val summaries: List<DriveSummary> get() = collectedSummaries
    var completed: Boolean = false
        private set
    fun addPage(pageIds: List<Int>, pageSummaries: List<DriveSummary>): Boolean {
        collectedSummaries += pageSummaries
        val decision = guard.accept(pageIds)
        completed = decision == CompletionPageDecision.COMPLETE
        return decision == CompletionPageDecision.CONTINUE
    }
}
internal sealed interface DriveSummaryPageResult {
    data class Success(val sourceIds: List<Int>, val summaries: List<DriveSummary>) : DriveSummaryPageResult
    data object Failure : DriveSummaryPageResult
}
/** Only a successful terminal page, not a repeated page/budget stop, publishes completion. */
internal class DriveSummarySyncRunner(
    private val fetchPage: suspend (page: Int) -> DriveSummaryPageResult,
    private val persistPage: suspend (List<DriveSummary>) -> Unit,
    private val onCompleted: suspend (List<DriveSummary>) -> Unit,
    private val maxPages: Int = 1000
) {
    suspend fun sync(): Boolean {
        val accumulator = DriveSummarySyncAccumulator(CompletionPageGuard(maxPages = maxPages))
        var page = 1
        while (true) {
            when (val result = fetchPage(page)) {
                DriveSummaryPageResult.Failure -> return false
                is DriveSummaryPageResult.Success -> {
                    if (result.sourceIds.isEmpty() && result.summaries.isNotEmpty()) return false
                    if (result.summaries.isNotEmpty()) persistPage(result.summaries)
                    if (!accumulator.addPage(result.sourceIds, result.summaries)) break
                    page++
                }
            }
        }
        if (!accumulator.completed) return false
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
    private val chargeNotifier: com.matelink.notification.CompletedChargeNotificationManager,
    private val statsDatabase: StatsDatabase,
    private val historyDetailStore: HistoryDetailRepository
) {
    companion object { private const val TAG = "SyncRepository" }
    private val chargeDeliveryMutex = Mutex()
    private suspend fun requireScope(expected: HistoryReadScope) {
        val actual = try { vehicleContextRepository.captureReadScope() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (actual != expected) throw CancellationException("History account or server changed")
    }
    private suspend fun requireIdentity(scope: HistoryReadScope, historyCarId: Int, remoteCarId: Int) {
        requireScope(scope)
        if (vehicleContextRepository.cachedContextForRemote(remoteCarId, scope)?.localHistoryCarId != historyCarId)
            throw CancellationException("History vehicle identity changed")
    }
    private suspend fun withSyncRun(run: SyncRun?, action: suspend (SyncRun) -> CarSyncResult): CarSyncResult {
        val execution = run ?: syncManager.newRun()
        return try {
            action(execution).also { if (it is CarSyncResult.Failed) syncManager.failRun(execution, "history_sync_failed") }
        } catch (e: CancellationException) {
            syncManager.cancelRun(execution)
            throw e
        } catch (e: Exception) {
            syncManager.failRun(execution, "history_sync_failed")
            throw e
        }
    }

    internal suspend fun syncCar(carId: Int, detailBudget: DriveDetailRequestBudget = DriveDetailRequestBudget(),
        summariesOnly: Boolean = false, run: SyncRun? = null): CarSyncResult = withSyncRun(run) {
        syncCarInRun(carId, detailBudget, summariesOnly, it)
    }

    private suspend fun syncCarInRun(carId: Int, detailBudget: DriveDetailRequestBudget,
        summariesOnly: Boolean, run: SyncRun): CarSyncResult {
        val scope = vehicleContextRepository.captureReadScope()
        val car = vehicleContextRepository.discoverHistoryCar(carId, scope)
            ?: return CarSyncResult.Failed(ApiResult.Error("history_vehicle_identity_unavailable"))
        requireScope(scope)
        val context = vehicleContextRepository.resolve(car, scope)
        val historyCarId = context.localHistoryCarId
        syncManager.claimCar(run, historyCarId)
        // Existing cloud archive path; no new historical repair/backfill.
        if (connectionModeStore.current() == com.matelink.data.local.ConnectionMode.TESLA_CLOUD) {
            try { uploadLocalHistory(context.remoteApiCarId, historyCarId) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { Log.w(TAG, "event=history_upload_failed") }
        }
        syncManager.updateSummaryProgress(historyCarId, run = run)
        val drivesSynced = syncDriveSummaries(context.remoteApiCarId, historyCarId, scope)
        val chargesSynced = syncChargeSummaries(context.remoteApiCarId, historyCarId, scope)
        if (!drivesSynced || !chargesSynced) return CarSyncResult.Failed(ApiResult.Error("history_summary_sync_incomplete"))
        syncManager.markSummariesComplete(historyCarId, run)
        if (summariesOnly) return CarSyncResult.Deferred(pendingDetails(historyCarId))
        return syncDetailsOnly(carId, detailBudget, run)
    }
    internal suspend fun captureContinuationFence(): String = vehicleContextRepository.captureContinuationFence()
    internal suspend fun claimRunCars(run: SyncRun, remoteIds: List<Int>) {
        val scope = vehicleContextRepository.captureReadScope()
        val contexts = remoteIds.map { id -> vehicleContextRepository.cachedContextForRemote(id, scope)
            ?: throw CancellationException("History vehicle proof unavailable") }
        requireScope(scope)
        contexts.forEach { syncManager.claimCar(run, it.localHistoryCarId) }
    }
    internal suspend fun continuationCarProof(carIds: List<Int>): String {
        val scope = vehicleContextRepository.captureReadScope()
        val contexts = carIds.map { id -> vehicleContextRepository.cachedContextForRemote(id, scope)
            ?: throw CancellationException("History vehicle proof unavailable") }
        requireScope(scope)
        val value = contexts.joinToString("\u0000") { "${it.remoteApiCarId}:${it.localHistoryCarId}:${it.stableIdentity}" }
        return java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    private suspend fun pendingDetails(car: Int): Int =
        driveSummaryDao.countUnprocessedDrives(car, com.matelink.data.local.entity.SchemaVersion.DRIVE_ENERGY) +
        chargeSummaryDao.countUnprocessedCharges(car, com.matelink.data.local.entity.SchemaVersion.CURRENT)

    /** Cached verified identity only: continuation never uploads archives or fetches summary pages. */
    internal suspend fun syncDetailsOnly(carId: Int, budget: DriveDetailRequestBudget,
        run: SyncRun? = null): CarSyncResult = withSyncRun(run) { syncDetailsInRun(carId, budget, it) }

    private suspend fun syncDetailsInRun(carId: Int, budget: DriveDetailRequestBudget, run: SyncRun): CarSyncResult {
        val scope = vehicleContextRepository.captureReadScope()
        val context = vehicleContextRepository.cachedContextForRemote(carId, scope)
            ?: return CarSyncResult.Failed(ApiResult.Error("history_vehicle_identity_unavailable", kind = com.matelink.data.repository.ApiErrorKind.INVALID_RESPONSE))
        val historyCarId = context.localHistoryCarId
        syncManager.claimCar(run, historyCarId)
        val drive = syncDriveDetails(context.remoteApiCarId, historyCarId, scope, context, budget, 1, run)
        drive.failure?.let { return CarSyncResult.Failed(it) }
        val chargeFailure = syncChargeDetails(context.remoteApiCarId, historyCarId, scope, context, budget, 1, run)
        chargeFailure?.let { return CarSyncResult.Failed(it) }
        requireIdentity(scope, historyCarId, context.remoteApiCarId)
        val pending = pendingDetails(historyCarId)
        if (pending > 0) return CarSyncResult.Deferred(pending)
        enqueueGeocoding(historyCarId)
        syncManager.markSyncComplete(historyCarId, run)
        return CarSyncResult.Complete
    }
    private suspend fun syncDriveSummaries(remoteApiCarId: Int, historyCarId: Int, scope: HistoryReadScope): Boolean = try {
        DriveSummarySyncRunner(
            fetchPage = { page ->
                requireIdentity(scope, historyCarId, remoteApiCarId)
                val result = teslamateRepository.getDrives(remoteApiCarId, page = page, show = 50)
                requireIdentity(scope, historyCarId, remoteApiCarId)
                when (result) {
                    is ApiResult.Success -> {
                        result.metadata?.let { historyMetadataStore.updateDrives(historyCarId, it) }
                        DriveSummaryPageResult.Success(result.data.map { it.id }, result.data.mapNotNull { it.toSyncSummary(historyCarId) })
                    }
                    is ApiResult.Error -> DriveSummaryPageResult.Failure
                }
            },
            persistPage = { rows -> requireIdentity(scope, historyCarId, remoteApiCarId); driveSummaryDao.upsertPreservingEvidence(rows) },
            onCompleted = { summaries -> notifyCompletedDriveUpdates(historyCarId, remoteApiCarId, summaries, scope) }
        ).sync()
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        Log.w(TAG, "event=drive_summary_sync_failed")
        false
    }
    private suspend fun notifyCompletedDriveUpdates(carId: Int, remoteCarId: Int, summaries: List<DriveSummary>, scope: HistoryReadScope) {
        requireIdentity(scope, carId, remoteCarId)
        val now = java.time.Instant.now()
        val key = completionScopeKey(scope, carId, remoteCarId)
        chargeEventStore.record(carId, remoteCarId,
            summaries.mapNotNull { it.completionReceipt(scope.source, now) }, key, "drive")
        chargeDeliveryMutex.withLock {
            deliverPendingCompletions(
                events = chargeEventStore.events(carId, key, "drive").first(),
                scopeStillCurrent = { requireIdentity(scope, carId, remoteCarId); true },
                resolve = { event -> resolvePendingCompletion(event, carId, remoteCarId, key,
                    { requireIdentity(scope, carId, remoteCarId); true }, { driveSummaryDao.get(carId, event.chargeId) },
                    { it.canPublishCompletion(scope.source, now) && event.matchesDrive(it) }) },
                post = { _, row -> tripNotificationManager.showCompletedDrive(carId, row) },
                acknowledge = { event -> chargeEventStore.consume(event, true) }
            )
        }
    }
    private suspend fun syncChargeSummaries(remoteApiCarId: Int, historyCarId: Int, scope: HistoryReadScope): Boolean {
        return try {
            var page = 1
            val guard = CompletionPageGuard()
            val completed = mutableListOf<ChargeSummary>()
            while (true) {
                requireIdentity(scope, historyCarId, remoteApiCarId)
                val result = teslamateRepository.getCharges(remoteApiCarId, page = page, show = 50)
                requireIdentity(scope, historyCarId, remoteApiCarId)
                when (result) {
                    is ApiResult.Success -> {
                        result.metadata?.let { historyMetadataStore.updateCharges(historyCarId, it) }
                        val charges = result.data
                        val summaries = charges.mapNotNull { it.toSyncSummary(historyCarId) }
                        completed += summaries
                        if (summaries.isNotEmpty()) chargeSummaryDao.upsertPreservingEvidence(summaries)
                        when (guard.accept(charges.map { it.chargeId })) {
                            CompletionPageDecision.COMPLETE -> break
                            CompletionPageDecision.INCOMPLETE -> {
                                Log.w(TAG, "event=charge_page_walk_incomplete")
                                return false
                            }
                            CompletionPageDecision.CONTINUE -> page++
                        }
                    }
                    is ApiResult.Error -> return false
                }
            }
            requireIdentity(scope, historyCarId, remoteApiCarId)
            val now = java.time.Instant.now()
            val key = completionScopeKey(scope, historyCarId, remoteApiCarId)
            chargeEventStore.record(historyCarId, remoteApiCarId,
                completed.mapNotNull { it.completionReceipt(scope.source, now) }, key)
            chargeDeliveryMutex.withLock {
                deliverPendingCompletions(
                    events = chargeEventStore.events(historyCarId, key).first(),
                    scopeStillCurrent = { requireIdentity(scope, historyCarId, remoteApiCarId); true },
                    resolve = { event -> resolvePendingCompletion(event, historyCarId, remoteApiCarId, key,
                        { requireIdentity(scope, historyCarId, remoteApiCarId); true }, { chargeSummaryDao.get(historyCarId, event.chargeId) },
                        { it.canPublishCompletion(scope.source, now) && event.matchesCharge(it) }) },
                    post = { event, _ -> chargeNotifier.show(event) },
                    acknowledge = { event -> chargeEventStore.consume(event, true) }
                )
            }
            true
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            Log.w(TAG, "event=charge_summary_sync_failed")
            false
        }
    }
    private suspend fun syncDriveDetails(remoteApiCarId: Int, historyCarId: Int, scope: HistoryReadScope,
        context: VehicleContext,
        budget: DriveDetailRequestBudget, maxRequests: Int = 20, run: SyncRun? = null): DriveDetailBatchResult {
        val version = com.matelink.data.local.entity.SchemaVersion.DRIVE_ENERGY
        val receipts = mutableMapOf<Int, String?>()
        val result = runDriveDetailBatch(budget,
            selectIds = { limit -> driveSummaryDao.getUnprocessedDriveBatchIds(historyCarId, version, limit) },
            fetch = { id ->
                receipts[id] = driveSummaryDao.get(historyCarId, id)?.apiEvidence
                requireIdentity(scope, historyCarId, remoteApiCarId)
                teslamateRepository.getDriveDetail(remoteApiCarId, id)
            },
            persist = { detail ->
                val before = driveSummaryDao.get(historyCarId, detail.driveId)
                val rawSource = before?.toRawAnalysisDriveData()?.source
                val legacyWindow = before != null && rawSource == null &&
                    detail.source == "teslamate_archive" &&
                    parseIsoInstant(before.startDate) == parseIsoInstant(detail.startDate) &&
                    parseIsoInstant(before.endDate) == parseIsoInstant(detail.endDate)
                val saved = if (legacyWindow) {
                    // Older untagged summaries may still feed Deep Stats, but cannot certify raw points.
                    driveSummaryDao.mergeResolvedDetail(historyCarId, detail) {
                        requireIdentity(scope, historyCarId, remoteApiCarId)
                    }
                } else persistVerifiedDriveDetail(statsDatabase, historyDetailStore,
                    context, detail) { requireIdentity(scope, historyCarId, remoteApiCarId) }?.summary
                if (saved == null) false else {
                    requireIdentity(scope, historyCarId, remoteApiCarId)
                    val aggregate = detail.toAggregate(carId = historyCarId, computedAt = System.currentTimeMillis())
                    val coordinates = usableVehicleCoordinates(aggregate.startLatitude, aggregate.startLongitude)
                    val location = coordinates?.let { geocodingRepository.getFromCache(it.first, it.second) }
                    requireIdentity(scope, historyCarId, remoteApiCarId)
                    aggregateDao.upsertDriveAggregate(aggregate.copy(startCountryCode = location?.countryCode,
                        startCountryName = location?.countryName, startRegionName = location?.regionName, startCity = location?.city))
                    requireIdentity(scope, historyCarId, remoteApiCarId)
                    syncManager.updateDriveDetailProgress(historyCarId, detail.driveId, run)
                    true
                }
            },
            pendingCount = { driveSummaryDao.countUnprocessedDrives(historyCarId, version) },
            ensureCurrent = { requireIdentity(scope, historyCarId, remoteApiCarId) },
            markComplete = {}, maxRequests = maxRequests,
            terminal404 = { id ->
                requireIdentity(scope, historyCarId, remoteApiCarId)
                driveSummaryDao.markDetailTerminal404(historyCarId, id, receipts[id]) {
                    requireIdentity(scope, historyCarId, remoteApiCarId)
                }
            })
        return result
    }
    private suspend fun syncChargeDetails(remoteApiCarId: Int, historyCarId: Int, scope: HistoryReadScope,
        context: VehicleContext,
        budget: DriveDetailRequestBudget, maxRequests: Int, run: SyncRun? = null): ApiResult.Error? {
        val limit = minOf(budget.remaining, maxRequests)
        requireIdentity(scope, historyCarId, remoteApiCarId)
        val ids = chargeSummaryDao.getUnprocessedChargeBatchIds(historyCarId, com.matelink.data.local.entity.SchemaVersion.CURRENT, limit)
        for (id in ids.take(limit)) {
            requireIdentity(scope, historyCarId, remoteApiCarId)
            val summary = chargeSummaryDao.get(historyCarId, id) ?: continue
            if (!budget.consume()) break
            val result = try { teslamateRepository.getChargeDetail(remoteApiCarId, id) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { return ApiResult.Error("charge_detail_sync_failed") }
            requireIdentity(scope, historyCarId, remoteApiCarId)
            when (result) {
                is ApiResult.Success -> {
                    if (result.data.chargeId != id) return ApiResult.Error("charge_detail_identity_invalid",
                        kind = com.matelink.data.repository.ApiErrorKind.INVALID_RESPONSE)
                    val detail = result.data.withQualifiedEnergy()
                    val rawSource = summary.toRawAnalysisChargeData().source
                    val legacyWindow = rawSource == null && result.data.source == "teslamate_archive" &&
                        parseIsoInstant(summary.startDate) == parseIsoInstant(result.data.startDate) &&
                        parseIsoInstant(summary.endDate) == parseIsoInstant(result.data.endDate)
                    val saved = try {
                        if (legacyWindow) {
                            chargeSummaryDao.mergeDetail(historyCarId, result.data) {
                                requireIdentity(scope, historyCarId, remoteApiCarId)
                            }
                        } else persistVerifiedChargeDetail(statsDatabase, historyDetailStore,
                            context, result.data) {
                            requireIdentity(scope, historyCarId, remoteApiCarId)
                        } != null
                    } catch (e: CancellationException) { throw e }
                    catch (_: HistoryDetailPointSaveRejected) {
                        return ApiResult.Error("charge_detail_points_not_saved",
                            kind = com.matelink.data.repository.ApiErrorKind.INVALID_RESPONSE)
                    }
                    if (!saved) return ApiResult.Error("charge_detail_write_rejected",
                        kind = com.matelink.data.repository.ApiErrorKind.INVALID_RESPONSE)
                    requireIdentity(scope, historyCarId, remoteApiCarId)
                    val aggregate = detail.toAggregate(carId = historyCarId, computedAt = System.currentTimeMillis())
                    val rawSummary = summary.toRawAnalysisChargeData()
                    val currentCoordinates = usableVehicleCoordinates(detail.latitude, detail.longitude)
                    val earlierCoordinates = if (isAnalysisEligible(summary.qualityState, summary.qualityReason) &&
                        summary.acceptsDetail(result.data)) usableVehicleCoordinates(rawSummary.latitude, rawSummary.longitude) else null
                    // A complete genuine same-record pair may fill a missing pair; never mix components or old names.
                    val coordinates = currentCoordinates ?: earlierCoordinates
                    val location = coordinates?.let { geocodingRepository.getFromCache(it.first, it.second) }
                    requireIdentity(scope, historyCarId, remoteApiCarId)
                    aggregateDao.upsertChargeAggregate(aggregate.copy(countryCode = location?.countryCode,
                        countryName = location?.countryName, regionName = location?.regionName, city = location?.city))
                    requireIdentity(scope, historyCarId, remoteApiCarId)
                    syncManager.updateChargeDetailProgress(historyCarId, id, run)
                }
                is ApiResult.Error -> if (result.code == 404) chargeSummaryDao.markDetailTerminal404(historyCarId, id, summary.apiEvidence) {
                    requireIdentity(scope, historyCarId, remoteApiCarId)
                } else return result
            }
        }
        return null
    }
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
            requireIdentity(scope, historyCarId, remoteApiCarId)
            val result = teslamateRepository.uploadLocalHistory(remoteApiCarId,
                com.matelink.data.api.models.HistoryImportRequest(drives = batch.drives, charges = batch.charges))
            requireIdentity(scope, historyCarId, remoteApiCarId)
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
        distance = distanceKm?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0, durationMin = durationMin ?: 0,
        startAddress = startAddress.orEmpty(), endAddress = endAddress.orEmpty(),
        speedMax = speedMaxKmh?.toInt() ?: 0, speedAvg = speedAvgKmh?.toInt() ?: 0,
        powerMax = powerMax ?: 0, powerMin = powerMin ?: 0,
        startBatteryLevel = startBatteryLevel ?: 0, endBatteryLevel = endBatteryLevel ?: 0,
        outsideTempAvg = outsideTempAvg?.takeIf(Double::isFinite), insideTempAvg = insideTempAvg?.takeIf(Double::isFinite),
        energyConsumed = normalized.netEnergyKwh, efficiency = normalized.efficiencyWhKm,
        energySource = normalized.netEnergyKwh?.let { metric.driveEnergySource() },
        energyCoverageSeconds = metric?.coverageSeconds?.takeIf { it.isFinite() && it >= 0.0 }?.toLong() ?: 0L,
        energyCoverageRatio = metric?.coverageRatio?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 0.0,
        // Store unmodified API evidence. Numeric Room columns above are
        // qualified projections; never encode those projections as raw truth.
        apiEvidence = HistorySummaryEvidenceCodec.encode(this),
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
        // Store unmodified API evidence. Numeric Room columns above are
        // qualified projections; never encode those projections as raw truth.
        apiEvidence = HistorySummaryEvidenceCodec.encode(this),
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

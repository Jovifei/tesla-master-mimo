package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.HistoryFamilies
import com.matelink.data.repository.UnifiedHistory
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.HistoryIdentityUnavailableException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.matelink.domain.history.isAnalysisEligible
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

data class AnalysisHistorySnapshot(
    val drives: List<DriveData>,
    val charges: List<ChargeData>,
    val fetchedAt: Instant,
    val coverage: HistoryCoverage,
    val freshness: HistoryFreshness = HistoryFreshness.FRESH,
    val staleReason: String? = null,
    val context: VehicleContext? = null
)

/** The pure fence retains origin, account, source, stable mapping and ABA generations. */
internal data class AnalysisPageIdentity(val context: VehicleContext, val stillCurrent: () -> Boolean)

internal suspend fun VehicleContextRepository.captureAnalysisPageIdentity(remoteCarId: Int): AnalysisPageIdentity {
    val scope = captureReadScope()
    val context = cachedContextForRemote(remoteCarId, scope) ?: resolveRemote(remoteCarId)
    currentCoroutineContext().ensureActive()
    if (!isHistoryContextCurrentNow(scope, context)) throw HistoryIdentityUnavailableException()
    return AnalysisPageIdentity(context) { isHistoryContextCurrentNow(scope, context) }
}

internal fun buildPersistedHistorySnapshot(
    drives: List<com.matelink.data.local.entity.DriveSummary>,
    charges: List<com.matelink.data.local.entity.ChargeSummary>,
    fetchedAt: Instant,
    reason: String
): AnalysisHistorySnapshot? {
    if (drives.isEmpty() && charges.isEmpty()) return null
    val eligibleDrives = drives.map { it.toAnalysisDriveData() }.filter { isAnalysisEligible(it.qualityState, it.qualityReason) }
    val eligibleCharges = charges.map { it.toAnalysisChargeData() }.filter {
        isAnalysisEligible(it.qualityState, it.qualityReason) ||
            (!it.qualityState.equals("quarantined", ignoreCase = true) && it.partialBatteryInputKwh != null)
    }
    return AnalysisHistorySnapshot(
        drives = eligibleDrives,
        charges = eligibleCharges,
        fetchedAt = fetchedAt,
        coverage = HistoryCoverage(
            driveCount = eligibleDrives.size,
            chargeCount = eligibleCharges.size
        ),
        freshness = HistoryFreshness.STALE,
        staleReason = reason
    )
}

internal class AnalysisHistorySnapshotCache {
    private val snapshots = ConcurrentHashMap<String, AnalysisHistorySnapshot>()

    fun put(stableIdentity: String, snapshot: AnalysisHistorySnapshot) {
        snapshots[stableIdentity] = snapshot
    }

    fun stale(stableIdentity: String, reason: String): AnalysisHistorySnapshot? =
        snapshots[stableIdentity]?.copy(
            freshness = HistoryFreshness.STALE,
            staleReason = reason
        )
}

@Singleton
class AnalysisHistoryRepository @Inject constructor(
    private val historyRepository: UnifiedHistoryRepository,
    private val vehicleContexts: VehicleContextRepository
) {
    private val cache = AnalysisHistorySnapshotCache()

    suspend fun load(carId: Int): ApiResult<AnalysisHistorySnapshot> =
        project(carId, historyRepository.load(carId))

    /** Non-blocking page preload from verified Room receipts, always STALE. */
    suspend fun loadCached(carId: Int): ApiResult<AnalysisHistorySnapshot> =
        project(carId, historyRepository.loadCached(carId))

    internal suspend fun loadDrives(carId: Int): ApiResult<AnalysisHistorySnapshot> =
        project(carId, historyRepository.loadDrives(carId), HistoryFamilies.DRIVES)

    internal suspend fun loadCharges(carId: Int): ApiResult<AnalysisHistorySnapshot> =
        project(carId, historyRepository.loadCharges(carId), HistoryFamilies.CHARGES)

    internal suspend fun loadCachedDrives(carId: Int): ApiResult<AnalysisHistorySnapshot> =
        project(carId, historyRepository.loadCached(carId, HistoryFamilies.DRIVES), HistoryFamilies.DRIVES)

    internal suspend fun loadCachedCharges(carId: Int): ApiResult<AnalysisHistorySnapshot> =
        project(carId, historyRepository.loadCached(carId, HistoryFamilies.CHARGES), HistoryFamilies.CHARGES)

    private suspend fun project(
        carId: Int,
        result: ApiResult<UnifiedHistory>,
        families: HistoryFamilies = HistoryFamilies.ALL
    ): ApiResult<AnalysisHistorySnapshot> {
        currentCoroutineContext().ensureActive()
        return when (result) {
            is ApiResult.Success -> {
                val history = result.data
                val proof = history.verifiedReadContext
                if (proof == null || proof.context != history.context || history.context.remoteApiCarId != carId ||
                    !vehicleContexts.isHistoryContextCurrentNow(proof.scope, proof.context))
                    return com.matelink.data.repository.historyIdentityUnavailableError()
                val eligibleDrives = history.drives.filter { isAnalysisEligible(it.qualityState, it.qualityReason) }
                val eligibleCharges = history.charges.filter {
                    isAnalysisEligible(it.qualityState, it.qualityReason) ||
                        (!it.qualityState.equals("quarantined", ignoreCase = true) && it.partialBatteryInputKwh != null)
                }
                val snapshot = AnalysisHistorySnapshot(
                    drives = eligibleDrives,
                    charges = eligibleCharges,
                    fetchedAt = history.fetchedAt,
                    coverage = HistoryCoverage(
                        driveCount = eligibleDrives.size,
                        chargeCount = eligibleCharges.size,
                        reason = classifyEmptyHistory(
                            driveCount = eligibleDrives.size,
                            chargeCount = eligibleCharges.size
                        )
                    ),
                    freshness = history.analysisFreshness(families),
                    staleReason = if (history.analysisFreshness(families) == HistoryFreshness.FRESH)
                        null else "retained local evidence or refresh unavailable",
                    context = history.context
                )
                currentCoroutineContext().ensureActive()
                if (!vehicleContexts.isHistoryContextCurrentNow(proof.scope, proof.context))
                    return com.matelink.data.repository.historyIdentityUnavailableError()
                cache.put(history.context.stableIdentity, snapshot)
                ApiResult.Success(snapshot)
            }
            is ApiResult.Error -> result
        }
    }
}

/** Request success and receipt coverage are separate; absence never deletes the archive. */
internal fun UnifiedHistory.analysisFreshness(families: HistoryFamilies): HistoryFreshness {
    val drivesCurrent = !families.drives || (drivesFromRemote && !drivesIncludeCachedEvidence)
    val chargesCurrent = !families.charges || (chargesFromRemote && !chargesIncludeCachedEvidence)
    return if (drivesCurrent && chargesCurrent) HistoryFreshness.FRESH else HistoryFreshness.STALE
}

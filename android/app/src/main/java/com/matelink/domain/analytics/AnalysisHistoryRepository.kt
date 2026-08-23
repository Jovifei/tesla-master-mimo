package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.dao.SyncStateDao
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.ApiResponseMetadata
import com.matelink.data.repository.TeslamateRepository
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
    val staleReason: String? = null
)

internal fun buildPersistedHistorySnapshot(
    drives: List<com.matelink.data.local.entity.DriveSummary>,
    charges: List<com.matelink.data.local.entity.ChargeSummary>,
    fetchedAt: Instant,
    reason: String
): AnalysisHistorySnapshot? {
    if (drives.isEmpty() && charges.isEmpty()) return null
    return AnalysisHistorySnapshot(
        drives = drives.map { it.toAnalysisDriveData() },
        charges = charges.map { it.toAnalysisChargeData() },
        fetchedAt = fetchedAt,
        coverage = HistoryCoverage(
            driveCount = drives.size,
            chargeCount = charges.size
        ),
        freshness = HistoryFreshness.STALE,
        staleReason = reason
    )
}

internal class AnalysisHistorySnapshotCache {
    private val snapshots = ConcurrentHashMap<Int, AnalysisHistorySnapshot>()

    fun put(carId: Int, snapshot: AnalysisHistorySnapshot) {
        snapshots[carId] = snapshot.copy(
            freshness = HistoryFreshness.FRESH,
            staleReason = null
        )
    }

    fun stale(carId: Int, reason: String): AnalysisHistorySnapshot? =
        snapshots[carId]?.copy(
            freshness = HistoryFreshness.STALE,
            staleReason = reason
        )
}

@Singleton
class AnalysisHistoryRepository @Inject constructor(
    private val teslamateRepository: TeslamateRepository,
    private val driveSummaryDao: DriveSummaryDao,
    private val chargeSummaryDao: ChargeSummaryDao,
    private val syncStateDao: SyncStateDao
) {
    private val cache = AnalysisHistorySnapshotCache()

    suspend fun load(carId: Int): ApiResult<AnalysisHistorySnapshot> {
        val freshResult = loadFresh(carId)
        if (freshResult is ApiResult.Success) {
            cache.put(carId, freshResult.data)
            return freshResult
        }

        val error = freshResult as ApiResult.Error
        return cache.stale(carId, error.message)?.let { ApiResult.Success(it) }
            ?: loadPersisted(carId, error.message)
            ?: error
    }

    /**
     * The normal sync path already persists summaries in Room. Reuse them
     * after process death when the network is unavailable, and label the
     * result stale so the UI never presents it as a fresh server response.
     */
    private suspend fun loadPersisted(
        carId: Int,
        reason: String
    ): ApiResult.Success<AnalysisHistorySnapshot>? = runCatching {
        val drives = driveSummaryDao.getAllChronological(carId)
        val charges = chargeSummaryDao.getAllForCar(carId)
        if (drives.isEmpty() && charges.isEmpty()) return@runCatching null

        val syncState = syncStateDao.get(carId)
        val lastSyncAt = maxOf(
            syncState?.lastDriveSyncAt ?: 0L,
            syncState?.lastChargeSyncAt ?: 0L
        )
        buildPersistedHistorySnapshot(
            drives = drives,
            charges = charges,
            fetchedAt = Instant.ofEpochMilli(lastSyncAt),
            reason = reason
        )?.let { snapshot -> ApiResult.Success(snapshot) }
    }.getOrNull()

    private suspend fun loadFresh(carId: Int): ApiResult<AnalysisHistorySnapshot> {
        val drivesResult = loadDrives(carId)
        val drives = when (drivesResult) {
            is ApiResult.Success -> drivesResult.data.records
            is ApiResult.Error -> return drivesResult
        }
        val chargesResult = loadCharges(carId)
        val charges = when (chargesResult) {
            is ApiResult.Success -> chargesResult.data.records
            is ApiResult.Error -> return chargesResult
        }
        val driveMetadata = drivesResult.data.metadata
        val chargeMetadata = chargesResult.data.metadata
        return ApiResult.Success(
            AnalysisHistorySnapshot(
                drives = drives,
                charges = charges,
                fetchedAt = Instant.now(),
                coverage = HistoryCoverage(
                    driveCount = drives.size,
                    chargeCount = charges.size,
                    reason = classifyEmptyHistory(
                        driveCount = drives.size,
                        chargeCount = charges.size,
                        driveAvailability = driveMetadata?.availability,
                        chargeAvailability = chargeMetadata?.availability
                    )
                )
            )
        )
    }

    private suspend fun loadDrives(carId: Int): ApiResult<PagedHistory<DriveData>> =
        loadPages { page -> teslamateRepository.getDrives(carId, page = page, show = PAGE_SIZE) }

    private suspend fun loadCharges(carId: Int): ApiResult<PagedHistory<ChargeData>> =
        loadPages { page -> teslamateRepository.getCharges(carId, page = page, show = PAGE_SIZE) }

    private data class PagedHistory<T>(
        val records: List<T>,
        val metadata: ApiResponseMetadata?
    )

    private suspend fun <T : Any> loadPages(
        fetch: suspend (page: Int) -> ApiResult<List<T>>
    ): ApiResult<PagedHistory<T>> {
        val records = LinkedHashMap<Int, T>()
        var metadata: ApiResponseMetadata? = null
        var page = 1
        while (true) {
            val result = fetch(page)
            if (result is ApiResult.Error) return result
            val success = result as ApiResult.Success
            metadata = metadata ?: success.metadata
            val pageRecords = success.data
            if (pageRecords.isEmpty()) break
            val sizeBefore = records.size
            pageRecords.forEachIndexed { index, item ->
                val sourceId = sourceIdOf(item, page, index)
                records.putIfAbsent(sourceId, item)
            }
            if (pageRecords.size < PAGE_SIZE) break
            page++
            if (page > MAX_PAGES || records.size == sizeBefore) break
        }
        return ApiResult.Success(PagedHistory(records.values.toList(), metadata), metadata)
    }

    private fun sourceIdOf(item: Any, page: Int, index: Int): Int = when (item) {
        is DriveData -> item.driveId
        is ChargeData -> item.chargeId
        else -> page * PAGE_SIZE + index
    }

    private companion object {
        const val PAGE_SIZE = 5000
        const val MAX_PAGES = 200
    }
}

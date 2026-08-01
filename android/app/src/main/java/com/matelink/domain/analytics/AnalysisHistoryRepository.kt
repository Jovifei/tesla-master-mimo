package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class AnalysisHistorySnapshot(
    val drives: List<DriveData>,
    val charges: List<ChargeData>,
    val fetchedAt: Instant,
    val coverage: HistoryCoverage
)

@Singleton
class AnalysisHistoryRepository @Inject constructor(
    private val teslamateRepository: TeslamateRepository
) {
    suspend fun load(carId: Int): ApiResult<AnalysisHistorySnapshot> {
        val drives = when (val result = loadDrives(carId)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> return result
        }
        val charges = when (val result = loadCharges(carId)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> return result
        }
        return ApiResult.Success(
            AnalysisHistorySnapshot(
                drives = drives,
                charges = charges,
                fetchedAt = Instant.now(),
                coverage = HistoryCoverage(
                    driveCount = drives.size,
                    chargeCount = charges.size,
                    reason = if (drives.isEmpty() && charges.isEmpty()) NoDataReason.NO_RECORDS else null
                )
            )
        )
    }

    private suspend fun loadDrives(carId: Int): ApiResult<List<DriveData>> =
        loadPages { page -> teslamateRepository.getDrives(carId, page = page, show = PAGE_SIZE) }

    private suspend fun loadCharges(carId: Int): ApiResult<List<ChargeData>> =
        loadPages { page -> teslamateRepository.getCharges(carId, page = page, show = PAGE_SIZE) }

    private suspend fun <T : Any> loadPages(
        fetch: suspend (page: Int) -> ApiResult<List<T>>
    ): ApiResult<List<T>> {
        val records = LinkedHashMap<Int, T>()
        var page = 1
        while (true) {
            val result = fetch(page)
            if (result is ApiResult.Error) return result
            val pageRecords = (result as ApiResult.Success).data
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
        return ApiResult.Success(records.values.toList())
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

package com.matelink.ui.screens.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.UpdateData
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.ui.screens.stats.ScopedUiLoader
import com.matelink.util.parseIsoInstant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

data class UpdatesStats(
    val totalUpdates: Int = 0,
    val meanDaysBetweenUpdates: Double? = null,
    val oldestVersion: String? = null,
    val newestVersion: String? = null
)

data class MonthlyUpdateCount(
    val yearMonth: YearMonth,
    val count: Int
)

data class SoftwareVersionsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val updates: List<SoftwareVersionItem> = emptyList(),
    val stats: UpdatesStats = UpdatesStats(),
    val monthlyData: List<MonthlyUpdateCount> = emptyList(),
    val longestInstalledId: Int? = null,
    val error: String? = null,
    val filterMonths: Int? = null  // null = All time, 6 = Last 6 months, 12 = Last year
)

internal fun SoftwareVersionsUiState.withProjection(projection: SoftwareVersionsProjection) = copy(
    isLoading = false,
    isRefreshing = false,
    updates = projection.items,
    stats = projection.stats,
    monthlyData = projection.monthlyData,
    longestInstalledId = projection.longestInstalledId,
    error = null
)

data class SoftwareVersionItem(
    val id: Int,
    val version: String,
    val installDate: LocalDateTime?,
    val endDate: LocalDateTime?,
    val updateDurationMinutes: Long?,  // Duration of the update process (start to end)
    val daysInstalled: Long?,          // Days the version was installed
    val isCurrent: Boolean
)

@HiltViewModel
class SoftwareVersionsViewModel @Inject constructor(
    private val repository: TeslamateRepository,
    private val vehicleContextRepository: VehicleContextRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SoftwareVersionsUiState())
    val uiState: StateFlow<SoftwareVersionsUiState> = _uiState.asStateFlow()

    private var carId: Int? = null
    private var allUpdates: List<UpdateData> = emptyList()
    private var dataScope: HistoryReadScope? = null
    private var activeScope: HistoryReadScope? = null
    private var identityEpoch = 0L
    private var identityJob: Job? = null
    private val loader = ScopedUiLoader(viewModelScope)

    init {
        viewModelScope.launch {
            vehicleContextRepository.historyScopeChanges.collect { scope ->
                val id = carId ?: return@collect
                if (activeScope != null && activeScope != scope) activate(id)
                else if (activeScope == null && identityJob?.isActive != true) activate(id)
            }
        }
    }

    fun setCarId(id: Int) {
        if (carId != id) {
            activate(id)
        }
    }

    private fun activate(id: Int) {
        carId = id
        val epoch = ++identityEpoch
        identityJob?.cancel()
        loader.invalidate()
        activeScope = null
        dataScope = null
        allUpdates = emptyList()
        _uiState.value = SoftwareVersionsUiState(filterMonths = _uiState.value.filterMonths)
        identityJob = viewModelScope.launch {
            try {
                val scope = vehicleContextRepository.captureReadScope()
                if (carId != id || epoch != identityEpoch) return@launch
                activeScope = scope
                loadUpdates(id, scope)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (carId == id && epoch == identityEpoch) _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun setFilter(months: Int?) {
        _uiState.update { it.copy(filterMonths = months) }
        val scope = dataScope ?: return
        val epoch = identityEpoch
        viewModelScope.launch {
            try {
                if (epoch == identityEpoch && activeScope == scope &&
                    vehicleContextRepository.captureReadScope() == scope
                ) applyFilter(months)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A stale or unavailable identity cannot project cached updates.
            }
        }
    }

    fun refresh() {
        carId?.let { id ->
            _uiState.update { it.copy(isRefreshing = true) }
            activeScope?.let { loadUpdates(id, it) } ?: activate(id)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun loadUpdates(id: Int, scope: HistoryReadScope) {
        if (!_uiState.value.isRefreshing) _uiState.update { it.copy(isLoading = true) }
        loader.load(
            proof = { { carId == id && activeScope == scope && vehicleContextRepository.captureReadScope() == scope } },
            fetch = { repository.getUpdates(id) },
            onSuccess = { result -> when (result) {
                is ApiResult.Success -> {
                    allUpdates = result.data
                    dataScope = scope
                    applyFilter(_uiState.value.filterMonths)
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = result.message
                        )
                    }
                }
            } },
            onFailure = { error -> _uiState.update {
                it.copy(isLoading = false, isRefreshing = false, error = error.message)
            } }
        )
    }

    private fun applyFilter(months: Int?) {
        val projection = projectSoftwareVersions(allUpdates, months)
        _uiState.update { it.withProjection(projection) }
    }
}

internal data class SoftwareVersionsProjection(
    val items: List<SoftwareVersionItem>,
    val stats: UpdatesStats,
    val monthlyData: List<MonthlyUpdateCount>,
    val longestInstalledId: Int?
)

/** Actual Software Versions page projection; keep receipt timestamps unchanged. */
internal fun projectSoftwareVersions(
    updates: List<UpdateData>,
    filterMonths: Int?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault()
): SoftwareVersionsProjection {
    data class Entry(val data: UpdateData, val start: Instant?, val end: Instant?, val order: Int)
    val sorted = updates.mapIndexed { index, data ->
        Entry(data, parseIsoInstant(data.startDate?.replace(" ", "T")),
            parseIsoInstant(data.endDate?.replace(" ", "T")), index)
    }.sortedWith(compareByDescending<Entry> { it.start ?: Instant.MIN }.thenBy { it.order })
    val cutoff = filterMonths?.takeIf { it > 0 }
        ?.let { now.atZone(zone).toLocalDate().minusMonths(it.toLong()) }
    val filtered = sorted.filter { entry ->
        cutoff == null || (entry.start?.atZone(zone)?.toLocalDate()?.let { !it.isBefore(cutoff) } == true)
    }
    val items = filtered.mapIndexed { index, entry ->
        val start = entry.start
        val end = entry.end
        val replacingStart = if (index == 0) now else filtered[index - 1].start
        SoftwareVersionItem(
            id = entry.data.id ?: 0,
            version = cleanVersion(entry.data.version),
            installDate = start?.atZone(zone)?.toLocalDateTime(),
            endDate = end?.atZone(zone)?.toLocalDateTime(),
            updateDurationMinutes = if (start != null && end != null && !end.isBefore(start))
                Duration.between(start, end).toMinutes() else null,
            daysInstalled = if (start != null && replacingStart != null && !replacingStart.isBefore(start))
                Duration.between(start, replacingStart).toDays() else null,
            isCurrent = index == 0 && start != null
        )
    }
    val longestId = items.filter { (it.daysInstalled ?: -1) > 0 }
        .maxByOrNull { it.daysInstalled!! }?.id
    val stats = calculateStats(items, filtered.map { it.start })
    val monthly = calculateMonthlyData(items, filterMonths, now, zone)
    return SoftwareVersionsProjection(items, stats, monthly, longestId)
}

private fun calculateStats(items: List<SoftwareVersionItem>, instants: List<Instant?>): UpdatesStats {
    if (items.isEmpty()) return UpdatesStats()
    val knownDates = instants.filterNotNull()
    val datedVersions = items.zip(instants).filter { it.second != null }.map { it.first.version }
    val meanDaysBetween = if (knownDates.size >= 2) {
        knownDates.zipWithNext { newer, older ->
              Duration.between(older, newer).toMillis() / 86_400_000.0
        }.average()
    } else null
    return UpdatesStats(
        totalUpdates = items.size,
        meanDaysBetweenUpdates = meanDaysBetween,
        oldestVersion = datedVersions.lastOrNull(),
        newestVersion = datedVersions.firstOrNull()
    )
}

private fun calculateMonthlyData(
    items: List<SoftwareVersionItem>,
    filterMonths: Int?,
    now: Instant,
    zone: ZoneId
): List<MonthlyUpdateCount> {
    val dates = items.mapNotNull { it.installDate }
    if (dates.isEmpty()) return emptyList()
    val monthCounts = dates.groupingBy { YearMonth.from(it) }.eachCount()
    val maxMonths = filterMonths?.takeIf { it > 0 } ?: 24
    val start = YearMonth.from(now.atZone(zone)).minusMonths(maxMonths.toLong() - 1)
    return (0 until maxMonths).map { offset ->
        val month = start.plusMonths(offset.toLong())
        MonthlyUpdateCount(month, monthCounts[month] ?: 0)
    }
}

private fun cleanVersion(version: String?): String =
    version?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: "Unknown"

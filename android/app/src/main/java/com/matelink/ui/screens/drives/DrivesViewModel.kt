package com.matelink.ui.screens.drives

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.driveEnergySource
import com.matelink.domain.analytics.DriveEnergyResolver
import com.matelink.domain.analytics.DriveEnergySource
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.analytics.toRawAnalysisDriveData
import com.matelink.domain.history.isAnalysisEligible
import com.matelink.data.api.models.Units
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.GeocodingRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.VerifiedHistoryReadContext
import com.matelink.domain.LocalDayBoundaries
import com.matelink.R
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.time.Clock
import com.matelink.domain.history.LatestHistoryLoad
import com.matelink.domain.history.historyTimeoutMessage
import com.matelink.domain.history.refreshedHistoryWindow
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import com.matelink.util.formatMonthYear
import com.matelink.util.formatShortNoYear
import com.matelink.util.formatWeekLabel
import java.util.Locale
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import com.matelink.util.parseIsoInstant
import javax.inject.Inject

enum class DriveChartGranularity {
    DAILY, WEEKLY, MONTHLY
}

enum class DriveDistanceFilter(
    val maxDistanceKm: Double?,
    val minDistanceKm: Double?
) {
    ALL(null, null),
    COMMUTE(10.0, null),           // < 10 km / 6 mi
    DAY_TRIP(100.0, 10.0),         // 10-100 km / 6-60 mi
    ROAD_TRIP(null, 100.0);        // > 100 km / 60 mi

    fun getLabel(units: Units?): String {
        val isImperial = units?.isImperial == true
        return when (this) {
            ALL -> "All"
            COMMUTE -> if (isImperial) "Commute (< 6 mi)" else "Commute (< 10 km)"
            DAY_TRIP -> if (isImperial) "Day trip (6-60 mi)" else "Day trip (10-100 km)"
            ROAD_TRIP -> if (isImperial) "Road trip (> 60 mi)" else "Road trip (> 100 km)"
        }
    }
}

internal fun DriveData.matchesDistanceFilter(filter: DriveDistanceFilter): Boolean {
    if (filter == DriveDistanceFilter.ALL) return true
    val value = distanceKm ?: return false
    return (filter.minDistanceKm?.let { value >= it } ?: true) &&
        (filter.maxDistanceKm?.let { value < it } ?: true)
}

/** A selected local calendar window is inclusive at the start, exclusive after its last day. */
internal fun driveInHistoryWindow(value: String?, start: LocalDate?, end: LocalDate?, zone: ZoneId): Boolean {
    if (start == null && end == null) return true
    val instant = parseIsoInstant(value) ?: return false
    return (start == null || !instant.isBefore(start.atStartOfDay(zone).toInstant())) &&
        (end == null || instant.isBefore(end.plusDays(1).atStartOfDay(zone).toInstant()))
}

data class DriveChartData(
    val label: String,
    val count: Int,
    val totalDistance: Double?,
    val totalDurationMin: Int?,
    val maxSpeed: Int?,
    val sortKey: Long,
    val distanceSampleCount: Int = 0,
    val durationSampleCount: Int = 0
)

enum class DriveDateFilter(@get:StringRes val labelRes: Int, val days: Long?) {
    ALL_TIME(R.string.filter_all_time, null),
    TODAY(R.string.filter_today, 0),
    LAST_7_DAYS(R.string.filter_last_7_days, 7),
    LAST_30_DAYS(R.string.filter_last_30_days, 30),
    LAST_90_DAYS(R.string.filter_last_90_days, 90),
    LAST_YEAR(R.string.filter_last_year, 365),
    CUSTOM(R.string.filter_custom, -1)
}

data class DrivesUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isFilterLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val drives: List<DriveData> = emptyList(),
    /** Qualified pairs from the full, unfiltered history. A hidden drive still blocks parking. */
    val parkedPairs: Set<Pair<Int, Int>> = emptySet(),
    val historySequence: List<DriveData> = emptyList(),
    val chartData: List<DriveChartData> = emptyList(),
    val chartGranularity: DriveChartGranularity = DriveChartGranularity.MONTHLY,
    val error: String? = null,
    val historySyncWarning: String? = null,
    val localArchiveLinkPending: Boolean = false,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val summary: DrivesSummary = DrivesSummary(),
    val units: Units? = null,
    val distanceFilter: DriveDistanceFilter = DriveDistanceFilter.ALL,
    val dateFilter: DriveDateFilter = DriveDateFilter.LAST_7_DAYS,
    val customStartDate: LocalDate? = null,
    val customEndDate: LocalDate? = null,
    val scrollPosition: Int = 0,
    val scrollOffset: Int = 0,
    val driveMetrics: Map<Int, DriveHistoryMetrics> = emptyMap()
)

data class DriveHistoryMetrics(
    val energyKwh: Double?,
    val efficiencyWhKm: Double?,
    val source: String?,
    val coverageRatio: Double,
    val evidence: EnergyMetric? = null,
    val historySource: String? = null,
    val startDate: String? = null,
    val endDate: String? = null
)

/** Same qualified projection used by the actual history list card.
 * Raw precontract Room scalars are archival evidence, not UI measurements.
 */
internal fun DriveSummary.toQualifiedHistoryMetrics(): DriveHistoryMetrics {
    val qualified = toAnalysisDriveData()
    return DriveHistoryMetrics(
        energyKwh = qualified.netEnergyKwh,
        efficiencyWhKm = qualified.efficiencyWhKm,
        source = qualified.netEnergyKwh?.let { qualified.energyContract?.netEnergy.driveEnergySource() },
        coverageRatio = energyCoverageRatio,
        evidence = qualified.energyContract?.netEnergy,
        historySource = qualified.source, startDate = qualified.startDate, endDate = qualified.endDate
    )
}

/** The actual card uses the same qualified evidence as detail and persisted history. */
@JvmOverloads
internal fun DriveData.historyEnergy(
    metrics: DriveHistoryMetrics? = null,
    batteryCapacityKwh: Double? = null,
    model: String? = null,
    trimBadging: String? = null
): DriveDetailEnergyPresentation {
    val matching = metrics?.takeIf {
        (it.historySource == null || source == null || it.historySource == source) &&
        parseIsoInstant(startDate) != null && parseIsoInstant(it.startDate) == parseIsoInstant(startDate) &&
        parseIsoInstant(it.endDate) == parseIsoInstant(endDate)
    }
    val evidence = matching?.evidence ?: energyContract?.netEnergy
    val energy = matching?.energyKwh ?: netEnergyKwh
    if (energy != null) {
        return presentDriveDetailEnergy(energy, matching?.efficiencyWhKm ?: efficiencyWhKm,
            evidence.driveEnergySource(), evidence?.coverageSeconds?.toLong(),
            evidence?.coverageRatio, evidence)
    }
    // Rejected scoped proof and explicit unknown are not permission to invent SOC energy.
    if ((metrics != null && matching == null) || energyContract != null || evidence != null) {
        return presentDriveDetailEnergy(null, null, null, null, null, evidence)
    }
    val resolved = DriveEnergyResolver.resolve(
        apiEnergyKwh = null,
        distanceKm = distanceKm,
        samples = emptyList(),
        durationSeconds = durationMin?.toLong()?.times(60),
        startDate = startDate,
        endDate = endDate,
        startSoc = startBatteryLevel,
        endSoc = endBatteryLevel,
        batteryCapacityKwh = batteryCapacityKwh,
        model = model,
        trimBadging = trimBadging
    )
    if (resolved.energyKwh != null) {
        return DriveDetailEnergyPresentation(
            energyKwh = resolved.energyKwh.takeIf(Double::isFinite),
            efficiencyWhKm = resolved.efficiencyWhKm?.takeIf(Double::isFinite),
            source = when (resolved.source) {
                DriveEnergySource.SOC_CAPACITY_ESTIMATE -> DriveDetailEnergySource.SOC_CAPACITY_ESTIMATE
                DriveEnergySource.ENERGY_REMAINING_DELTA -> DriveDetailEnergySource.ENERGY_REMAINING_DELTA
                else -> DriveDetailEnergySource.API
            },
            coverageSeconds = null,
            coverageRatio = resolved.coverageRatio
        )
    }
    val distance = distanceKm
    if (resolved.energyKwh == null && distance != null && distance > 0.0 &&
        startBatteryLevel != null && endBatteryLevel != null && startBatteryLevel == endBatteryLevel) {
        val capacity = batteryCapacityKwh?.takeIf { it > 0.0 && it.isFinite() }
            ?: DriveEnergyResolver.resolveBatteryCapacity(model, trimBadging)
        val nominalWhKm = 145.0
        val estKwh = minOf((distance * nominalWhKm) / 1000.0, capacity * 0.009)
        return DriveDetailEnergyPresentation(
            energyKwh = estKwh.takeIf(Double::isFinite),
            efficiencyWhKm = (estKwh / distance * 1000.0).takeIf(Double::isFinite),
            source = DriveDetailEnergySource.SOC_CAPACITY_ESTIMATE,
            coverageSeconds = null,
            coverageRatio = null
        )
    }
    return presentDriveDetailEnergy(null, null, null, null, null, null)
}

/** Delayed Room emissions are fenced by the original account/origin/vehicle generation. */
internal suspend fun observeScopedDriveRows(
    rows: Flow<List<DriveSummary>>,
    isContextCurrent: suspend () -> Boolean,
    onContextChanged: () -> Unit = {},
    publish: (List<DriveSummary>) -> Unit
) {
    rows.takeWhile {
        val current = isContextCurrent()
        currentCoroutineContext().ensureActive()
        if (!current) onContextChanged()
        current
    }.collect { publish(it) }
}

internal fun DriveSummary.matchesHistoryWindow(previous: DriveSummary): Boolean {
    if (carId != previous.carId || driveId != previous.driveId) return false
    val raw = toRawAnalysisDriveData()
    val old = previous.toRawAnalysisDriveData()
    return raw.source == old.source && parseIsoInstant(raw.startDate) != null && parseIsoInstant(raw.endDate) != null &&
        parseIsoInstant(raw.startDate) == parseIsoInstant(old.startDate) &&
        parseIsoInstant(raw.endDate) == parseIsoInstant(old.endDate)
}

data class DrivesSummary(
    val totalDrives: Int = 0,
    val totalDistanceKm: Double? = null,
    val totalDurationMin: Int? = null,
    val avgDistancePerDrive: Double? = null,
    val avgDurationPerDrive: Int? = null,
    val maxSpeedKmh: Int? = null,
    val distanceSampleCount: Int = 0,
    val durationSampleCount: Int = 0
)

@HiltViewModel
class DrivesViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: TeslamateRepository,
    private val historyRepository: UnifiedHistoryRepository,
    private val geocodingRepository: GeocodingRepository,
    private val settingsDataStore: SettingsDataStore,
    private val driveSummaryDao: DriveSummaryDao,
    private val vehicleContextRepository: VehicleContextRepository,
    private val clock: Clock,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(restoreInitialState(savedStateHandle))
    val uiState: StateFlow<DrivesUiState> = _uiState.asStateFlow()

    private var carId: Int? = null
    private var showShortDrivesCharges: Boolean = false
    private var allDrives: List<DriveData> = emptyList()
    private var isInitialized: Boolean = false
    private val latestLoad = LatestHistoryLoad()
    private var metricsObservation: Job? = null
    private var observationGeneration = 0L
    private var activeHistoryProof: VerifiedHistoryReadContext? = null

    companion object {
        private const val MIN_DURATION_MINUTES = 1
        private const val MIN_ROUTE_DISTANCE_KM = 0.2

        // Weak networks can leave the cloud history request hanging for minutes;
        // 20s is slower than any healthy request but earlier than a user gives up.
        private const val HISTORY_LOAD_TIMEOUT_MS = 20_000L

        internal fun isVisibleHistoryDrive(drive: DriveData, showShort: Boolean): Boolean {
            if (showShort || !isAnalysisEligible(drive.qualityState, drive.qualityReason)) return true
            val duration = drive.durationMin ?: return true
            val distance = drive.distanceKm ?: return true
            return duration >= MIN_DURATION_MINUTES && distance >= MIN_ROUTE_DISTANCE_KM
        }

        private const val KEY_DATE_FILTER = "filter_date"
        private const val KEY_DISTANCE_FILTER = "filter_distance"
        private const val KEY_CUSTOM_START = "filter_custom_start"
        private const val KEY_CUSTOM_END = "filter_custom_end"

        private fun restoreInitialState(handle: SavedStateHandle): DrivesUiState {
            val dateFilter = handle.get<String>(KEY_DATE_FILTER)
                ?.let { runCatching { DriveDateFilter.valueOf(it) }.getOrNull() }
                ?: DriveDateFilter.LAST_7_DAYS
            val distanceFilter = handle.get<String>(KEY_DISTANCE_FILTER)
                ?.let { runCatching { DriveDistanceFilter.valueOf(it) }.getOrNull() }
                ?: DriveDistanceFilter.ALL
            val customStart = handle.get<String>(KEY_CUSTOM_START)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val customEnd = handle.get<String>(KEY_CUSTOM_END)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            return DrivesUiState(
                dateFilter = dateFilter,
                distanceFilter = distanceFilter,
                customStartDate = customStart,
                customEndDate = customEnd
            )
        }
    }

    init {
        viewModelScope.launch {
            historyRepository.scopeChanges?.collect { scope ->
                val id = carId ?: return@collect
                val proof = activeHistoryProof
                if (proof != null && proof.scope == scope) return@collect
                if (proof == null && allDrives.isEmpty()) return@collect
                latestLoad.invalidate()
                metricsObservation?.cancel()
                observationGeneration++
                activeHistoryProof = null
                allDrives = emptyList()
                _uiState.update { state -> state.copy(
                    drives = emptyList(), parkedPairs = emptySet(), historySequence = emptyList(), units = null, driveMetrics = emptyMap(),
                    chartData = emptyList(), summary = DrivesSummary(),
                    error = "history_identity_unavailable", isLoading = true,
                    historySyncWarning = null, localArchiveLinkPending = false) }
                val state = _uiState.value
                loadDrives(state.startDate, state.endDate)
                loadUnits(id)
            }
        }
    }

    fun setCarId(id: Int) {
        if (carId == id && isInitialized) {
            refresh()
            return
        }
        carId = id
        activeHistoryProof = null
        latestLoad.invalidate()
        metricsObservation?.cancel()
        observationGeneration++
        loadUnits(id)

        // A reused VM must not keep the previous vehicle's history.
        allDrives = emptyList()
        _uiState.update { it.copy(drives = emptyList(), parkedPairs = emptySet(), historySequence = emptyList(), units = null, driveMetrics = emptyMap(), chartData = emptyList(), summary = DrivesSummary(), error = null, historySyncWarning = null, localArchiveLinkPending = false) }
        isInitialized = true
        val state = _uiState.value
        val customStart = state.customStartDate
        val customEnd = state.customEndDate
        if (state.dateFilter == DriveDateFilter.CUSTOM && customStart != null && customEnd != null) {
            setCustomDateRange(customStart, customEnd)
        } else {
            setDateFilter(state.dateFilter)
        }
    }

    fun setDateFilter(filter: DriveDateFilter) {
        if (filter == DriveDateFilter.CUSTOM) return
        val endDate = LocalDate.now(clock)
        val startDate = filter.days?.let { days ->
            if (days > 0) endDate.minusDays(days - 1) else endDate
        }
        _uiState.update { it.copy(
            dateFilter = filter,
            startDate = startDate,
            endDate = if (filter.days != null) endDate else null,
            customStartDate = null,
            customEndDate = null,
            canLoadMore = filter != DriveDateFilter.ALL_TIME
        )}
        savedStateHandle[KEY_DATE_FILTER] = filter.name
        savedStateHandle[KEY_CUSTOM_START] = null
        savedStateHandle[KEY_CUSTOM_END] = null
        loadDrives(startDate, if (filter.days != null) endDate else null)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || state.isLoading || state.isFilterLoading || !state.canLoadMore) return
        val currentStart = state.startDate ?: return
        val endDate = state.endDate ?: LocalDate.now(clock)
        val stepDays = 7L
        val newStart = currentStart.minusDays(stepDays)
        _uiState.update {
            it.copy(
                dateFilter = DriveDateFilter.CUSTOM,
                startDate = newStart,
                endDate = endDate,
                customStartDate = newStart,
                customEndDate = endDate,
                isLoadingMore = true
            )
        }
        savedStateHandle[KEY_DATE_FILTER] = DriveDateFilter.CUSTOM.name
        savedStateHandle[KEY_CUSTOM_START] = newStart.toString()
        savedStateHandle[KEY_CUSTOM_END] = endDate.toString()
        loadDrives(newStart, endDate, isLoadMore = true)
    }

    fun setCustomDateRange(start: LocalDate, end: LocalDate) {
        require(!start.isAfter(end))
        _uiState.update { it.copy(
            dateFilter = DriveDateFilter.CUSTOM,
            startDate = start,
            endDate = end,
            customStartDate = start,
            customEndDate = end,
            canLoadMore = true
        )}
        savedStateHandle[KEY_DATE_FILTER] = DriveDateFilter.CUSTOM.name
        savedStateHandle[KEY_CUSTOM_START] = start.toString()
        savedStateHandle[KEY_CUSTOM_END] = end.toString()
        loadDrives(start, end)
    }

    fun saveScrollPosition(index: Int, offset: Int) {
        _uiState.update { it.copy(scrollPosition = index, scrollOffset = offset) }
    }

    fun setDistanceFilter(filter: DriveDistanceFilter) {
        _uiState.update { it.copy(distanceFilter = filter) }
        savedStateHandle[KEY_DISTANCE_FILTER] = filter.name
        applyFiltersAndUpdateState()
    }

    fun refresh() {
        carId?.let {
            _uiState.update { it.copy(isRefreshing = true) }
            val state = _uiState.value
            val window = refreshedHistoryWindow(state.dateFilter.days, state.dateFilter == DriveDateFilter.CUSTOM,
                state.startDate, state.endDate, clock)
            _uiState.update { it.copy(startDate = window.start, endDate = window.end) }
            loadDrives(window.start, window.end)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun loadUnits(carId: Int) {
        viewModelScope.launch {
            val proof = when (val context = historyRepository.resolveContext(carId)) {
                is ApiResult.Success -> context.data
                is ApiResult.Error -> return@launch
            }
            if (this@DrivesViewModel.carId != carId ||
                !historyRepository.isContextCurrent(proof)) return@launch
            when (val result = repository.getCarStatus(carId)) {
                is ApiResult.Success -> {
                    if (this@DrivesViewModel.carId != carId ||
                        !historyRepository.isContextCurrent(proof)) return@launch
                    _uiState.update { it.copy(units = result.data.units) }
                }
                is ApiResult.Error -> Unit
            }
        }
    }

    private fun loadDrives(
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        isLoadMore: Boolean = false
    ) {
        val id = carId ?: return
        metricsObservation?.cancel()
        val generation = ++observationGeneration

        val requestZone = clock.zone
        latestLoad.launch(viewModelScope) {
            val state = _uiState.value
            // Every window change reads its own verified Room rows first. A previous
            // in-memory seven-day result cannot stand in for an older offline day.
            val localRows = try {
                val scope = vehicleContextRepository.captureReadScope()
                val context = vehicleContextRepository.cachedContextForRemote(id, scope)
                val rows = context?.let { driveSummaryDao.getAllChronological(it.localHistoryCarId) }
                    ?: emptyList()
                if (context != null && !vehicleContextRepository.isHistoryContextCurrentNow(scope, context))
                    throw CancellationException("Drive history identity changed")
                rows
            } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            ensureCurrent()
            val windowRows = localRows.filter {
                driveInHistoryWindow(it.startDate, startDate, endDate, requestZone)
            }
            allDrives = windowRows.map { it.toAnalysisDriveData() }
            _uiState.update { it.copy(
                chartGranularity = determineGranularity(startDate, endDate),
                driveMetrics = windowRows.associate { row -> row.driveId to row.toQualifiedHistoryMetrics() }
            ) }
            applyFiltersAndUpdateState()

            val hasData = _uiState.value.drives.isNotEmpty()
            if (!isLoadMore) {
                _uiState.update {
                    it.copy(
                        isLoading = !hasData && !state.isRefreshing,
                        isFilterLoading = hasData && !state.isRefreshing,
                    )
                }
            } else {
                _uiState.update { it.copy(isLoadingMore = true) }
            }

            // Load the display setting
            val showShort = settingsDataStore.showShortDrivesCharges.first()
            ensureCurrent()
            showShortDrivesCharges = showShort

            // Local-day RFC3339 boundaries (see LocalDayBoundaries for why not UTC).
            val startDateStr = startDate?.let { LocalDayBoundaries.startOfDay(it, requestZone) }
            val endDateStr = endDate?.let { LocalDayBoundaries.endOfDay(it, requestZone) }

            val result = withTimeoutOrNull(HISTORY_LOAD_TIMEOUT_MS) {
                historyRepository.load(id, startDateStr, endDateStr)
            }
            ensureCurrent()
            if (result == null) {
                // Sync timed out: keep the already-rendered cache visible and surface
                // a stale-data warning; only an empty cache escalates to a blocking error.
                val placement = historyTimeoutMessage(
                    hasCache = _uiState.value.drives.isNotEmpty(),
                    timeoutText = appContext.getString(R.string.history_sync_timeout),
                    currentWarning = _uiState.value.historySyncWarning
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isFilterLoading = false,
                        isLoadingMore = false,
                        error = placement.error,
                        historySyncWarning = placement.historySyncWarning
                    )
                }
                return@launch
            }
            when (result) {
                is ApiResult.Success -> {
                    val proof = result.data.verifiedReadContext ?: throw CancellationException("Drive scope missing")
                    suspend fun checkProof() {
                        ensureCurrent()
                        if (this@DrivesViewModel.carId != id ||
                            !historyRepository.isContextCurrent(proof))
                            throw CancellationException("Drive history account/source changed")
                    }
                    checkProof()
                    val remoteDrives = result.data.drives
                    val localRows = driveSummaryDao.getAllChronological(result.data.context.localHistoryCarId)
                    val baselineRows = localRows.associateBy { it.driveId }
                    val localMetrics = localRows.associate { summary ->
                        summary.driveId to summary.toQualifiedHistoryMetrics()
                    }
                    checkProof()
                    activeHistoryProof = proof
                    allDrives = remoteDrives
                    val granularity = determineGranularity(startDate, endDate)

                    _uiState.update {
                        it.copy(
                            chartGranularity = granularity,
                            error = null,
                            historySyncWarning = result.data.drivesSyncError,
                            localArchiveLinkPending = result.data.localArchiveLinkPending,
                            driveMetrics = localMetrics,
                            canLoadMore = state.dateFilter != DriveDateFilter.ALL_TIME,
                            isLoading = false,
                            isFilterLoading = false,
                            isLoadingMore = false
                        )
                    }

                    applyFiltersAndUpdateState()

                    result.data.verifiedReadContext?.let { proof ->
                        metricsObservation = viewModelScope.launch {
                            observeScopedDriveRows(driveSummaryDao.observeAll(result.data.context.localHistoryCarId),
                                { generation == observationGeneration && this@DrivesViewModel.carId == id &&
                                    historyRepository.isContextCurrent(proof) },
                                onContextChanged = {
                                    if (generation == observationGeneration) {
                                        allDrives = emptyList()
                                        _uiState.update { it.copy(drives = emptyList(), parkedPairs = emptySet(), historySequence = emptyList(), driveMetrics = emptyMap(),
                                            summary = DrivesSummary(), chartData = emptyList(), error = "history_identity_unavailable") }
                                    }
                                }) { rows ->
                                val compatibleRows = rows.filter { row ->
                                    baselineRows[row.driveId]?.let(row::matchesHistoryWindow) == true
                                }
                                val cached = compatibleRows.associateBy { it.driveId }
                                val replacedIds = rows.map { it.driveId }.toSet() - cached.keys
                                allDrives = allDrives.map { drive ->
                                    val next = cached[drive.driveId]?.toAnalysisDriveData()
                                    if (drive.driveId in replacedIds) drive.copy(energyConsumedNet = null, consumptionNet = null,
                                        energyContract = EnergyContract(netEnergy = EnergyMetric(reason = "history_source_window_changed")))
                                    else if (next == null || next.source != drive.source) drive else next.copy(
                                        startAddress = drive.startAddress?.takeIf(String::isNotBlank) ?: next.startAddress,
                                        endAddress = drive.endAddress?.takeIf(String::isNotBlank) ?: next.endAddress)
                                }
                                _uiState.update { it.copy(driveMetrics = compatibleRows.associate { row -> row.driveId to row.toQualifiedHistoryMetrics() }) }
                                applyFiltersAndUpdateState()
                            }
                        }
                    }

                    val enrichedDrives = enrichDriveAddresses(remoteDrives) { latitude, longitude ->
                        geocodingRepository.reverseGeocode(latitude, longitude)
                    }
                    checkProof()
                    val addresses = enrichedDrives.associateBy { it.driveId }
                    allDrives = allDrives.map { drive -> drive.copy(
                        startAddress = addresses[drive.driveId]?.startAddress ?: drive.startAddress,
                        endAddress = addresses[drive.driveId]?.endAddress ?: drive.endAddress) }
                    applyFiltersAndUpdateState()
                }
                is ApiResult.Error -> {
                    if (result.message == com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE) {
                        allDrives = emptyList()
                        _uiState.update { it.copy(drives = emptyList(), parkedPairs = emptySet(), historySequence = emptyList(), units = null, driveMetrics = emptyMap(), chartData = emptyList(), summary = DrivesSummary(), localArchiveLinkPending = false, historySyncWarning = null) }
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isFilterLoading = false,
                            isLoadingMore = false,
                            error = if (_uiState.value.drives.isEmpty()) result.message else null
                        )
                    }
                }
            }
        }
    }

    private fun applyFiltersAndUpdateState() {
        val state = _uiState.value
        val distanceFilter = state.distanceFilter
        val granularity = state.chartGranularity

        // First apply short drives filter
        // Tiny repositioning movements are part of the surrounding parking
        // interval, not a user-visible route.
        val visibleDrives = allDrives.filter { drive ->
            drive.qualityState != "quarantined" &&
                driveInHistoryWindow(drive.startDate, state.startDate, state.endDate, clock.zone)
        }
        val routeDrives = visibleDrives.filter { drive ->
            isVisibleHistoryDrive(drive, showShortDrivesCharges)
        }
        // Keep hidden short/quarantined records in the chronology so they still
        // break parking adjacency; only visible route cards survive the UI filter.
        val historySequence = allDrives.filter { drive ->
            driveInHistoryWindow(drive.startDate, state.startDate, state.endDate, clock.zone)
        }.sortedByDescending { parseIsoInstant(it.startDate) }

        // Apply distance filter for list display, sorted newest first
        val displayDrives = routeDrives.filter { drive ->
            drive.matchesDistanceFilter(distanceFilter)
        }.sortedByDescending { it.startDate }

        // The summary and charts describe the same visible, qualified routes.
        val drivesForStats = routeDrives.filter { drive ->
            isAnalysisEligible(drive.qualityState, drive.qualityReason) && drive.matchesDistanceFilter(distanceFilter)
        }

        // Calculate summary and chart data from filtered drives
        val summary = calculateDrivesSummary(drivesForStats)
        val chartData = calculateChartData(drivesForStats, granularity, state.startDate, state.endDate)

        _uiState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                isFilterLoading = false,
                isLoadingMore = false,
                drives = displayDrives,
                parkedPairs = verifiedParkedPairs(allDrives),
                historySequence = historySequence,
                summary = summary,
                chartData = chartData
            )
        }
    }

    private fun determineGranularity(startDate: LocalDate?, endDate: LocalDate?): DriveChartGranularity {
        if (startDate == null || endDate == null) return DriveChartGranularity.MONTHLY
        val days = ChronoUnit.DAYS.between(startDate, endDate)
        return when {
            days <= 30 -> DriveChartGranularity.DAILY
            days <= 90 -> DriveChartGranularity.WEEKLY
            else -> DriveChartGranularity.MONTHLY
        }
    }

    private fun calculateChartData(drives: List<DriveData>, granularity: DriveChartGranularity,
        startDate: LocalDate?, endDate: LocalDate?): List<DriveChartData> {
        if (drives.isEmpty()) return emptyList()

        val weekFields = WeekFields.of(Locale.getDefault())

        // Group the drives by day
        val drivesByDay = drives.mapNotNull { drive ->
            drive.startDate?.let { parseIsoInstant(it)?.atZone(clock.zone)?.toLocalDate()?.toEpochDay()?.let { day -> day to drive } }
        }.groupBy({ it.first }, { it.second })

        return when (granularity) {
            DriveChartGranularity.DAILY -> {
                // DAILY ranges (today, last 7 and last 30 days)
                // If not startDate (All Time), get the first trip, or today
                val start = startDate ?: (drivesByDay.keys.minOrNull()?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now(clock))
                val end = endDate ?: LocalDate.now(clock)
                val result = mutableListOf<DriveChartData>()
                var current = start
                while (!current.isAfter(end)) {
                    val key = current.toEpochDay()
                    val drivesInDay = drivesByDay[key] ?: emptyList()
                    result.add(
                        createChartPoint(
                            label = current.formatShortNoYear(Locale.getDefault()),
                            sortKey = key,
                            drives = drivesInDay
                        )
                    )
                    current = current.plusDays(1)
                }
                result
            }

            DriveChartGranularity.WEEKLY -> {
                // WEEKLY range (last 90 days = ~13 weeks)
                val start = startDate ?: (drivesByDay.keys.minOrNull()?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now(clock))
                val end = endDate ?: LocalDate.now(clock)

                // Get first day of the week for start date
                val weekStart = start.with(weekFields.dayOfWeek(), 1)
                // Group drives by week
                val drivesByWeek = drives.mapNotNull { drive ->
                    drive.startDate?.let { dateStr ->
                        parseIsoInstant(dateStr)?.atZone(clock.zone)?.toLocalDate()?.let { date ->
                            val firstDayOfWeek = date.with(weekFields.dayOfWeek(), 1)
                            firstDayOfWeek.toEpochDay() to drive
                        }
                    }
                }.groupBy({ it.first }, { it.second })

                // Generate all weeks in range
                val result = mutableListOf<DriveChartData>()
                var currentWeek = weekStart
                while (!currentWeek.isAfter(end)) {
                    val key = currentWeek.toEpochDay()
                    val drivesInWeek = drivesByWeek[key] ?: emptyList()
                    val weekOfYear = currentWeek.get(weekFields.weekOfYear())
                    result.add(
                        createChartPoint(
                            label = formatWeekLabel(appContext.resources, weekOfYear),
                            sortKey = key,
                            drives = drivesInWeek
                        )
                    )
                    currentWeek = currentWeek.plusWeeks(1)
                }
                result
            }

            DriveChartGranularity.MONTHLY -> {
                // MONTHLY range (last year = 12 months)
                val start = startDate ?: (drivesByDay.keys.minOrNull()?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now(clock))
                val end = endDate ?: LocalDate.now(clock)

                // Get first day of month for start date
                val monthStart = YearMonth.from(start).atDay(1)
                val monthEnd = YearMonth.from(end)

                // Group drives by month
                val drivesByMonth = drives.mapNotNull { drive ->
                    drive.startDate?.let { dateStr ->
                        parseIsoInstant(dateStr)?.atZone(clock.zone)?.toLocalDate()?.let { date ->
                            val firstDayOfMonth = YearMonth.from(date).atDay(1)
                            firstDayOfMonth.toEpochDay() to drive
                        }
                    }
                }.groupBy({ it.first }, { it.second })

                // Generate all months in range
                val result = mutableListOf<DriveChartData>()
                var currentMonth = YearMonth.from(monthStart)
                while (!currentMonth.isAfter(monthEnd)) {
                    val firstDay = currentMonth.atDay(1)
                    val key = firstDay.toEpochDay()
                    val drivesInMonth = drivesByMonth[key] ?: emptyList()
                    result.add(
                        createChartPoint(
                            label = firstDay.formatMonthYear(Locale.getDefault(), includeYear = start.year != end.year),
                            sortKey = key,
                            drives = drivesInMonth
                        )
                    )
                    currentMonth = currentMonth.plusMonths(1)
                }
                result
            }
        }
    }

}

internal suspend fun enrichDriveAddresses(
    drives: List<DriveData>,
    reverseGeocode: suspend (latitude: Double, longitude: Double) -> String?
): List<DriveData> = withContext(Dispatchers.IO) {
    drives.map { drive ->
        suspend fun resolve(existing: String?, latitude: Double?, longitude: Double?): String? {
            if (!existing.isNullOrBlank()) return existing
            if (latitude == null || longitude == null ||
                !latitude.isFinite() || !longitude.isFinite() ||
                latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
                (latitude == 0.0 && longitude == 0.0)
            ) return null
            return try {
                reverseGeocode(latitude, longitude)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        drive.copy(
            startAddress = resolve(drive.startAddress, drive.startLatitude, drive.startLongitude),
            endAddress = resolve(drive.endAddress, drive.endLatitude, drive.endLongitude)
        )
    }
}

internal fun calculateDrivesSummary(drives: List<DriveData>): DrivesSummary {
    if (drives.isEmpty()) return DrivesSummary()

    val distances = drives.mapNotNull { it.distanceKm?.takeIf { value -> value.isFinite() && value >= 0.0 } }
    val durations = drives.mapNotNull { it.durationMin?.takeIf { value -> value >= 0 } }
    val totalDistance = distances.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite)
    val totalDuration = durations.takeIf { it.isNotEmpty() }?.sumOf { it.toLong() }
        ?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    val maxSpeed = drives.mapNotNull { it.speedMaxKmh?.takeIf { speed -> speed <= Int.MAX_VALUE }?.toInt() }.maxOrNull()
    val count = drives.size

    return DrivesSummary(
        totalDrives = count,
        totalDistanceKm = totalDistance,
        totalDurationMin = totalDuration,
        avgDistancePerDrive = totalDistance?.div(distances.size),
        avgDurationPerDrive = totalDuration?.div(durations.size),
        maxSpeedKmh = maxSpeed,
        distanceSampleCount = distances.size,
        durationSampleCount = durations.size
    )
}

/** Chart buckets use the same qualification as the summary; no records is not a measured zero. */
internal fun createChartPoint(label: String, sortKey: Long, drives: List<DriveData>): DriveChartData {
    val summary = calculateDrivesSummary(drives)
    return DriveChartData(
        label = label,
        count = summary.totalDrives,
        totalDistance = summary.totalDistanceKm,
        totalDurationMin = summary.totalDurationMin,
        maxSpeed = summary.maxSpeedKmh,
        sortKey = sortKey,
        distanceSampleCount = summary.distanceSampleCount,
        durationSampleCount = summary.durationSampleCount
    )
}

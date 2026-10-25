package com.matelink.ui.screens.charges

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.domain.history.isAnalysisEligible
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.entity.ChargeCostOverride
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.data.model.Currency
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.VerifiedHistoryReadContext
import com.matelink.domain.LocalDayBoundaries
import com.matelink.domain.analytics.ChargeCostSource
import com.matelink.domain.analytics.EffectiveChargeCost
import com.matelink.domain.analytics.findChargeCostOverride
import com.matelink.domain.analytics.findOtherCurrencyChargeCostOverride
import com.matelink.domain.analytics.projectEffectiveChargeCost
import com.matelink.domain.analytics.observedCostSumOrNull
import com.matelink.domain.analytics.validManualChargeTotal
import android.content.Context
import com.matelink.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import com.matelink.util.parseIsoInstant
import java.util.Locale
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import javax.inject.Inject

enum class ChartGranularity {
    DAILY, WEEKLY, MONTHLY
}

enum class DateFilter(@get:StringRes val labelRes: Int, val days: Long?) {
    TODAY(R.string.filter_today, 0),
    LAST_7_DAYS(R.string.filter_last_7_days, 7),
    LAST_30_DAYS(R.string.filter_last_30_days, 30),
    LAST_90_DAYS(R.string.filter_last_90_days, 90),
    LAST_YEAR(R.string.filter_last_year, 365),
    ALL_TIME(R.string.filter_all_time, null),
    CUSTOM(R.string.filter_custom, -1)
}

enum class ChargeTypeFilter(val label: String) {
    ALL("All"),
    AC("AC"),
    DC("DC")
}

enum class CostFilter(@get:StringRes val labelRes: Int) {
    ALL(R.string.cost_filter_all),
    HAS_COST(R.string.cost_filter_has_cost),
    NO_COST(R.string.cost_filter_no_cost)
}

/** Include only events whose observed start falls inside the selected local calendar window. */
internal fun chargeInHistoryWindow(value: String?, start: LocalDate?, end: LocalDate?, zone: ZoneId): Boolean {
    if (start == null && end == null) return true
    val instant = parseIsoInstant(value) ?: return false
    return (start == null || !instant.isBefore(start.atStartOfDay(zone).toInstant())) &&
        (end == null || instant.isBefore(end.plusDays(1).atStartOfDay(zone).toInstant()))
}

data class LocationFilter(val name: String) // null name = All locations

data class ChargeChartData(
    val label: String,
    val count: Int,
    val totalEnergy: Double,
    val energyAc: Double,
    val energyDc: Double,
    val energyUnknown: Double = 0.0,
    val costAc: Double,
    val costDc: Double,
    val costUnknown: Double = 0.0,
    val countAc: Int,
    val countDc: Int,
    val countUnknown: Int = 0,
    val totalCost: Double,
    val sortKey: Long, // For sorting (epoch day, week number, or year-month)
    val costCoverage: Int = 0,
    val estimatedCostCount: Int = 0,
    val energyCoverage: Int = 0
)

data class ChargesUiState(
    val defaultChargePrice: Double = 1.14,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    // Set while a filter-driven reload is in flight. Distinct from `isLoading`,
    // which intentionally stays false on filter change to avoid the full-screen
    // flash. Drives a centered overlay spinner that lets the existing list show
    // through.
    val isFilterLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    val charges: List<ChargeData> = emptyList(),
    val dcChargeIds: Set<Int> = emptySet(),
    val availableLocations: List<String> = emptyList(),
    val selectedLocations: Set<String> = emptySet(),  // null = All locations
    val processedChargeIds: Set<Int> = emptySet(),  // Charges that have aggregate data
    val chartData: List<ChargeChartData> = emptyList(),
    val chartGranularity: ChartGranularity = ChartGranularity.MONTHLY,
    val error: String? = null,
    val historySyncWarning: String? = null,
    val localArchiveLinkPending: Boolean = false,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val selectedFilter: DateFilter = DateFilter.LAST_30_DAYS,  // Default to 30 days
    val chargeTypeFilter: ChargeTypeFilter = ChargeTypeFilter.ALL,
    val costFilter: CostFilter = CostFilter.ALL,
    val customStartDate: LocalDate? = null,
    val customEndDate: LocalDate? = null,
    val scrollPosition: Int = 0,  // First visible item index
    val scrollOffset: Int = 0,    // Scroll offset within first item
    val summary: ChargesSummary = ChargesSummary(),
    val currencySymbol: String = com.matelink.data.model.Currency.CNY.symbol,
    val currencyCode: String = "CNY",
    val teslamateBaseUrl: String = "",
    val freeSupercharging: Boolean = false,
    val priceOverrides: Map<Int, Double> = emptyMap(),
    val effectiveCosts: Map<Int, EffectiveChargeCost> = emptyMap(),
    val pendingLegacyAmounts: Map<Int, Double> = emptyMap(),
    val pendingBoundAmounts: Map<Int, Pair<String?, Double>> = emptyMap(),
    val otherCurrencyAmounts: Map<Int, Pair<String, Double>> = emptyMap()
)

data class ChargesSummary(
    val totalCharges: Int = 0,
    val totalEnergyAdded: Double? = null,
    val totalCost: Double? = null,
    val avgEnergyPerCharge: Double? = null,
    val avgCostPerCharge: Double? = null,
    val costCoverage: Int = 0,
    val estimatedCostCount: Int = 0
)

internal data class ProjectedChargeCosts(
    val total: Double?, val average: Double?, val coveredCount: Int, val estimatedCount: Int
)

/** List cards, selected-window summary and chart consume the same qualified cost projection. */
internal fun summarizeProjectedChargeCosts(values: List<EffectiveChargeCost>): ProjectedChargeCosts {
    val included = values.filter { it.source != ChargeCostSource.UNAVAILABLE &&
        it.cost?.let { cost -> cost.isFinite() && cost >= 0.0 } == true }
    val total = observedCostSumOrNull(included.map { it.cost!! })
    return ProjectedChargeCosts(
        total = total,
        average = total?.div(included.size),
        coveredCount = included.size,
        estimatedCount = included.count { it.source == ChargeCostSource.ESTIMATE }
    )
}

internal fun projectedCostMatchesFilter(cost: EffectiveChargeCost, filter: CostFilter): Boolean =
    when (filter) {
        CostFilter.ALL -> true
        CostFilter.HAS_COST -> cost.cost?.let { it > 0.0 } == true
        CostFilter.NO_COST -> cost.cost?.let { it > 0.0 } != true
    }

@HiltViewModel
class ChargesViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: TeslamateRepository,
    private val historyRepository: UnifiedHistoryRepository,
    private val chargeSummaryDao: ChargeSummaryDao,
    private val settingsDataStore: SettingsDataStore,
    private val chargeCostOverrideStore: ChargeCostOverrideStore,
    private val aggregateDao: AggregateDao,
    private val vehicleContextRepository: VehicleContextRepository,
    private val clock: Clock,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(restoreInitialState(savedStateHandle))
    val uiState: StateFlow<ChargesUiState> = _uiState.asStateFlow()

    private var carId: Int? = null
    private val latestLoad = LatestHistoryLoad()
    private var historyCarId: Int? = null
    private var activeHistoryProof: VerifiedHistoryReadContext? = null
    private var showShortDrivesCharges: Boolean = false
    private var allCharges: List<ChargeData> = emptyList()
    private var allCostRecords: List<ChargeCostOverride> = emptyList()

    companion object {
        private const val MIN_ENERGY_KWH = 0.1

        // Weak networks can leave the cloud history request hanging for minutes;
        // 20s is slower than any healthy request but earlier than a user gives up.
        private const val HISTORY_LOAD_TIMEOUT_MS = 20_000L

        private const val KEY_DATE_FILTER = "filter_date"
        private const val KEY_CHARGE_TYPE_FILTER = "filter_charge_type"
        private const val KEY_COST_FILTER = "filter_cost"
        private const val KEY_LOCATIONS = "filter_locations"
        private const val KEY_CUSTOM_START = "filter_custom_start"
        private const val KEY_CUSTOM_END = "filter_custom_end"

        private fun restoreInitialState(handle: SavedStateHandle): ChargesUiState {
            val dateFilter = handle.get<String>(KEY_DATE_FILTER)
                ?.let { runCatching { DateFilter.valueOf(it) }.getOrNull() }
                ?: DateFilter.LAST_30_DAYS
            val chargeTypeFilter = handle.get<String>(KEY_CHARGE_TYPE_FILTER)
                ?.let { runCatching { ChargeTypeFilter.valueOf(it) }.getOrNull() }
                ?: ChargeTypeFilter.ALL
            val costFilter = handle.get<String>(KEY_COST_FILTER)
                ?.let { runCatching { CostFilter.valueOf(it) }.getOrNull() }
                ?: CostFilter.ALL
            val locations = handle.get<ArrayList<String>>(KEY_LOCATIONS)?.toSet() ?: emptySet()
            val customStart = handle.get<String>(KEY_CUSTOM_START)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val customEnd = handle.get<String>(KEY_CUSTOM_END)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            return ChargesUiState(
                selectedFilter = dateFilter,
                chargeTypeFilter = chargeTypeFilter,
                costFilter = costFilter,
                selectedLocations = locations,
                customStartDate = customStart,
                customEndDate = customEnd
            )
        }
    }

    init {
        observeSettings()
        observeChargePriceOverrides()
        viewModelScope.launch {
            historyRepository.scopeChanges?.collect { scope ->
                val id = carId ?: return@collect
                val proof = activeHistoryProof
                if (proof != null && scope == proof.scope) return@collect
                if (proof == null && allCharges.isEmpty()) return@collect
                latestLoad.invalidate()
                activeHistoryProof = null
                historyCarId = null
                allCharges = emptyList()
                allCostRecords = emptyList()
                val state = _uiState.value
                _uiState.value = state.copy(isLoading = true, charges = emptyList(),
                    summary = ChargesSummary(), chartData = emptyList(),
                    dcChargeIds = emptySet(), processedChargeIds = emptySet(),
                    effectiveCosts = emptyMap(), priceOverrides = emptyMap(),
                    pendingLegacyAmounts = emptyMap(), pendingBoundAmounts = emptyMap(),
                    otherCurrencyAmounts = emptyMap(), freeSupercharging = false,
                    historySyncWarning = null, error = "history_identity_unavailable")
                loadCharges(state.startDate, state.endDate)
                loadCarSettings(id)
            }
        }
    }

    private fun observeChargePriceOverrides() {
        viewModelScope.launch {
            chargeCostOverrideStore.ensureLegacyMigrated()
            chargeCostOverrideStore.observeAllRecords().collect { records ->
                allCostRecords = records
                val proof = activeHistoryProof
                if (allCharges.isNotEmpty() && proof != null &&
                    historyRepository.isContextCurrent(proof)) applyFiltersAndUpdateState()
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsDataStore.settings.collect { settings ->
                val currency = Currency.entries.firstOrNull {
                    it.code.equals(settings.currencyCode, ignoreCase = true)
                }
                _uiState.update {
                    it.copy(
                        currencySymbol = currency?.symbol ?: "?",
                        currencyCode = currency?.code ?: settings.currencyCode,
                        defaultChargePrice = settings.defaultChargePrice,
                        teslamateBaseUrl = settings.teslamateBaseUrl
                    )
                }
                if (allCharges.isNotEmpty() && !_uiState.value.isLoading && !_uiState.value.isRefreshing) {
                    applyFiltersAndUpdateState()
                }
            }
        }
    }

    fun setCarId(id: Int) {
        if (carId != id) {
            carId = id
            historyCarId = null
            activeHistoryProof = null
            latestLoad.invalidate()
            allCharges = emptyList()
            allCostRecords = emptyList()
            _uiState.update { it.copy(charges = emptyList(), freeSupercharging = false, priceOverrides = emptyMap(), effectiveCosts = emptyMap(), pendingLegacyAmounts = emptyMap(), pendingBoundAmounts = emptyMap(), otherCurrencyAmounts = emptyMap(), chartData = emptyList(), summary = ChargesSummary(), dcChargeIds = emptySet(), processedChargeIds = emptySet(), availableLocations = emptyList(), error = null, historySyncWarning = null, localArchiveLinkPending = false) }
            loadCarSettings(id)
            // Apply restored (or default) filter on first load. CUSTOM needs the
            // explicit date pair — setDateFilter is a no-op for CUSTOM.
            val state = _uiState.value
            val customStart = state.customStartDate
            val customEnd = state.customEndDate
            if (state.selectedFilter == DateFilter.CUSTOM && customStart != null && customEnd != null) {
                setCustomDateRange(customStart, customEnd)
            } else {
                setDateFilter(state.selectedFilter)
            }
        } else refresh()
    }

    private fun loadCarSettings(id: Int) {
        viewModelScope.launch {
            val resolved = historyRepository.resolveContext(id)
            val proof = (resolved as? ApiResult.Success)?.data ?: return@launch
            if (!historyRepository.isContextCurrent(proof) || carId != id) return@launch
            when (val result = repository.getCar(id)) {
                is ApiResult.Success -> {
                    if (carId != id || !historyRepository.isContextCurrent(proof) ||
                        !proof.matchesCar(result.data)) return@launch
                    val free = result.data.carSettings?.freeSupercharging == true
                    _uiState.update { it.copy(freeSupercharging = free) }
                    if (allCharges.isNotEmpty()) applyFiltersAndUpdateState()
                }
                is ApiResult.Error -> Unit
            }
        }
    }

    fun setDateFilter(filter: DateFilter) {
        if (filter == DateFilter.CUSTOM) return
        val endDate = LocalDate.now(clock)
        val startDate = filter.days?.let { days ->
            if (days > 0) endDate.minusDays(days - 1) else endDate
        }
        _uiState.update { it.copy(
            selectedFilter = filter,
            startDate = startDate,
            endDate = if (filter.days != null) endDate else null,
            customStartDate = null,
            customEndDate = null,
            canLoadMore = filter != DateFilter.ALL_TIME
        )}
        savedStateHandle[KEY_DATE_FILTER] = filter.name
        savedStateHandle[KEY_CUSTOM_START] = null
        savedStateHandle[KEY_CUSTOM_END] = null
        loadCharges(startDate, if (filter.days != null) endDate else null)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || state.isLoading || state.isFilterLoading || !state.canLoadMore) return
        val currentStart = state.startDate ?: return
        val endDate = state.endDate ?: LocalDate.now(clock)
        val stepDays = 30L
        val newStart = currentStart.minusDays(stepDays)
        _uiState.update {
            it.copy(
                selectedFilter = DateFilter.CUSTOM,
                startDate = newStart,
                endDate = endDate,
                customStartDate = newStart,
                customEndDate = endDate,
                isLoadingMore = true
            )
        }
        savedStateHandle[KEY_DATE_FILTER] = DateFilter.CUSTOM.name
        savedStateHandle[KEY_CUSTOM_START] = newStart.toString()
        savedStateHandle[KEY_CUSTOM_END] = endDate.toString()
        loadCharges(newStart, endDate, isLoadMore = true)
    }

    fun setCustomDateRange(start: LocalDate, end: LocalDate) {
        require(!start.isAfter(end))
        _uiState.update { it.copy(
            selectedFilter = DateFilter.CUSTOM,
            startDate = start,
            endDate = end,
            customStartDate = start,
            customEndDate = end,
            canLoadMore = true
        )}
        savedStateHandle[KEY_DATE_FILTER] = DateFilter.CUSTOM.name
        savedStateHandle[KEY_CUSTOM_START] = start.toString()
        savedStateHandle[KEY_CUSTOM_END] = end.toString()
        loadCharges(start, end)
    }

    fun refresh() {
        carId?.let {
            _uiState.update { it.copy(isRefreshing = true) }
            val state = _uiState.value
            val window = refreshedHistoryWindow(state.selectedFilter.days, state.selectedFilter == DateFilter.CUSTOM,
                state.startDate, state.endDate, clock)
            _uiState.update { it.copy(startDate = window.start, endDate = window.end) }
            loadCharges(window.start, window.end)
        }
    }

    fun setCostFilter(filter: CostFilter) {
        _uiState.update { it.copy(costFilter = filter) }
        savedStateHandle[KEY_COST_FILTER] = filter.name
        applyFiltersAndUpdateState()
    }

    fun setChargeTypeFilter(filter: ChargeTypeFilter) {
        val currentFilter = _uiState.value.chargeTypeFilter
        // Toggle: if same filter is selected, reset to ALL
        val newFilter = if (filter == currentFilter && filter != ChargeTypeFilter.ALL) {
            ChargeTypeFilter.ALL
        } else {
            filter
        }
        _uiState.update { it.copy(chargeTypeFilter = newFilter) }
        savedStateHandle[KEY_CHARGE_TYPE_FILTER] = newFilter.name
        applyFiltersAndUpdateState()
    }

    fun setLocationFilter(location: String) {
        val current = _uiState.value.selectedLocations
        val updated = if (location in current) current - location else current + location
        _uiState.update { it.copy(selectedLocations = updated) }
        savedStateHandle[KEY_LOCATIONS] = ArrayList(updated)
        applyFiltersAndUpdateState()
    }

    fun clearLocationFilter() {
        _uiState.update { it.copy(selectedLocations = emptySet()) }
        savedStateHandle[KEY_LOCATIONS] = ArrayList<String>()
        applyFiltersAndUpdateState()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun saveScrollPosition(firstVisibleIndex: Int, offset: Int) {
        _uiState.update { it.copy(scrollPosition = firstVisibleIndex, scrollOffset = offset) }
    }

    private fun loadCharges(
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        isLoadMore: Boolean = false
    ) {
        val id = carId ?: return
        val requestZone = clock.zone
        latestLoad.launch(viewModelScope) {
            val state = _uiState.value
            // Re-read verified Room rows for every selected window. A previous
            // recent in-memory result cannot hide an older day while offline.
            var verifiedLocalId: Int? = null
            val localRows = try {
                val scope = vehicleContextRepository.captureReadScope()
                val context = vehicleContextRepository.cachedContextForRemote(id, scope)
                val rows = context?.let { chargeSummaryDao.getAllForCar(it.localHistoryCarId) }
                    ?: emptyList()
                if (context != null && !vehicleContextRepository.isHistoryContextCurrentNow(scope, context))
                    throw CancellationException("Charge history identity changed")
                verifiedLocalId = context?.localHistoryCarId
                rows
            } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            ensureCurrent()
            historyCarId = verifiedLocalId
            if (verifiedLocalId == null) activeHistoryProof = null
            allCharges = localRows.map { it.toAnalysisChargeData() }
            _uiState.update { it.copy(chartGranularity = determineGranularity(startDate, endDate)) }
            applyFiltersAndUpdateState()
            val hasData = _uiState.value.charges.isNotEmpty()
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
                    hasCache = _uiState.value.charges.isNotEmpty(),
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
                    val proof = result.data.verifiedReadContext ?: return@launch
                    suspend fun checkProof() {
                        ensureCurrent()
                        if (carId != id || !historyRepository.isContextCurrent(proof))
                            throw CancellationException("Charges history identity changed")
                    }
                    checkProof()
                    val dcChargeIds = try {
                        aggregateDao.getDcChargeIds(result.data.context.localHistoryCarId).toSet()
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        emptySet()
                    }
                    val processedChargeIds = try {
                        aggregateDao.getAllProcessedChargeIds(result.data.context.localHistoryCarId).toSet()
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        emptySet()
                    }
                    checkProof()
                    activeHistoryProof = proof
                    historyCarId = result.data.context.localHistoryCarId
                    allCharges = result.data.charges
                    val granularity = determineGranularity(startDate, endDate)

                    _uiState.update {
                        it.copy(
                            dcChargeIds = dcChargeIds,
                            processedChargeIds = processedChargeIds,
                            chartGranularity = granularity,
                            error = null,
                            historySyncWarning = result.data.chargesSyncError,
                            localArchiveLinkPending = result.data.localArchiveLinkPending,
                            canLoadMore = state.selectedFilter != DateFilter.ALL_TIME,
                            isLoading = false,
                            isFilterLoading = false,
                            isLoadingMore = false
                        )
                    }

                    checkProof()
                    applyFiltersAndUpdateState()
                }
                is ApiResult.Error -> {
                    if (result.message == com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE) {
                        allCharges = emptyList()
                        historyCarId = null
                        activeHistoryProof = null
                        allCostRecords = emptyList()
                        _uiState.update { it.copy(charges = emptyList(), freeSupercharging = false, priceOverrides = emptyMap(), effectiveCosts = emptyMap(), pendingLegacyAmounts = emptyMap(), pendingBoundAmounts = emptyMap(), otherCurrencyAmounts = emptyMap(), chartData = emptyList(), summary = ChargesSummary(), dcChargeIds = emptySet(), processedChargeIds = emptySet(), availableLocations = emptyList(), localArchiveLinkPending = false, historySyncWarning = null) }
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isFilterLoading = false,
                            isLoadingMore = false,
                            error = if (_uiState.value.charges.isEmpty()) result.message else null
                        )
                    }
                }
            }
        }
    }

    private fun applyFiltersAndUpdateState() {
        val state = _uiState.value
        val chargeTypeFilter = state.chargeTypeFilter
        val costFilter = state.costFilter
        val dcChargeIds = state.dcChargeIds
        val processedChargeIds = state.processedChargeIds
        val typeOf: (ChargeData) -> ChargeType = { historyChargeType(it, dcChargeIds, processedChargeIds) }
        val granularity = state.chartGranularity

        val visibleCharges = allCharges.filter { charge ->
            charge.qualityState != "quarantined" &&
                chargeInHistoryWindow(charge.startDate, state.startDate, state.endDate, clock.zone)
        }
        // Incomplete imports remain visible, but cannot produce stats or charts.
        // Eligibility is about observed source quality, not a non-zero SOC delta.
        // A valid zero-energy session remains a session; unknown energy is not zero.
        val validCharges = visibleCharges.filter { charge ->
            isAnalysisEligible(charge.qualityState, charge.qualityReason)
        }

        var filteredCharges = if (showShortDrivesCharges) {
            visibleCharges
        } else {
            visibleCharges.filter { charge ->
                !isAnalysisEligible(charge.qualityState, charge.qualityReason) ||
                    (charge.batteryInputKwh?.let { it > MIN_ENERGY_KWH } ?: true)
            }
        }

        // Apply charge type filter (AC/DC) for list display
        val displayCharges = when (chargeTypeFilter) {
            ChargeTypeFilter.ALL -> filteredCharges
            ChargeTypeFilter.DC -> filteredCharges.filter { typeOf(it) == ChargeType.DC }
            ChargeTypeFilter.AC -> filteredCharges.filter { typeOf(it) == ChargeType.AC }
        }

        // Apply charge type filter to all charges for summary/charts (include short charges)
        val chargesForStats = when (chargeTypeFilter) {
            ChargeTypeFilter.ALL -> validCharges
            ChargeTypeFilter.DC -> validCharges.filter { typeOf(it) == ChargeType.DC }
            ChargeTypeFilter.AC -> validCharges.filter { typeOf(it) == ChargeType.AC }
        }

        // Extract unique locations from the complete set
        val locations = visibleCharges.mapNotNull { it.address }.distinct().sorted()
        // Apply location filter to displayCharges
        val locationFilter = state.selectedLocations
        val displayChargesLocFiltered = if (locationFilter.isNotEmpty())
            displayCharges.filter {  it.address in locationFilter }
        else displayCharges
        // Apply location filter to Stats
        val chargesForStatsLocFiltered = if (locationFilter.isNotEmpty())
            chargesForStats.filter { it.address in locationFilter }
        else chargesForStats

        // Apply cost filter. TeslaMate returns cost=0 (not null) for unset values,
        // so "No cost" means "0 or null" and "Has cost" means strictly > 0. This
        // mixes free-SuC sessions in with not-yet-filled-in ones — which is fine
        // for the primary use case (find DC sessions to edit) since free-SuC cars
        // surface a contextual hint and other cars rarely have legitimate 0-cost
        // DC charges.
        val displayChargesFiltered = when (costFilter) {
            CostFilter.ALL -> displayChargesLocFiltered
            CostFilter.HAS_COST -> displayChargesLocFiltered.filter {
                projectedCostMatchesFilter(projectedCost(it, state), costFilter)
            }
            CostFilter.NO_COST -> displayChargesLocFiltered.filter {
                projectedCostMatchesFilter(projectedCost(it, state), costFilter)
            }
        }
        val chargesForStatsFiltered = when (costFilter) {
            CostFilter.ALL -> chargesForStatsLocFiltered
            CostFilter.HAS_COST -> chargesForStatsLocFiltered.filter {
                projectedCostMatchesFilter(projectedCost(it, state), costFilter)
            }
            CostFilter.NO_COST -> chargesForStatsLocFiltered.filter {
                projectedCostMatchesFilter(projectedCost(it, state), costFilter)
            }
        }

        // Calculate summary and chart data from filtered charges
        val summary = calculateSummary(chargesForStatsFiltered)
        val chartData = calculateChartData(chargesForStatsFiltered, granularity, state.startDate, state.endDate)
        val projections = allCharges.associate { charge -> charge.chargeId to projectedCost(charge, state) }
        val prices = allCharges.mapNotNull { charge ->
            projections[charge.chargeId]?.takeIf { it.source == ChargeCostSource.MANUAL }
                ?.cost?.let { charge.chargeId to it }
        }.toMap()
        val pendingLegacy = historyCarId?.let { id ->
            allCostRecords.filter { it.carId == id && it.sessionKey == "legacy" && !it.deleted }
                .associate { it.chargeId to it.manualTotalAmount }
        } ?: emptyMap()
        val pendingBound = historyCarId?.let { id ->
            allCharges.mapNotNull { charge ->
                val record = findChargeCostOverride(allCostRecords, id, charge, state.currencyCode)
                if (record == null || record.deleted ||
                    projections[charge.chargeId]?.source == ChargeCostSource.MANUAL) null
                else charge.chargeId to (record.currencyCode to record.manualTotalAmount)
            }.toMap()
        } ?: emptyMap()
        val otherCurrency = historyCarId?.let { id ->
            allCharges.mapNotNull { charge ->
                findOtherCurrencyChargeCostOverride(allCostRecords, id, charge, state.currencyCode)
                    ?.let { record -> charge.chargeId to (record.currencyCode!! to record.manualTotalAmount) }
            }.toMap()
        } ?: emptyMap()

        _uiState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                isFilterLoading = false,
                charges = displayChargesFiltered.sortedByDescending { it.startDate },
                availableLocations = locations,
                priceOverrides = prices,
                effectiveCosts = projections,
                pendingLegacyAmounts = pendingLegacy,
                pendingBoundAmounts = pendingBound,
                otherCurrencyAmounts = otherCurrency,
                summary = summary,
                chartData = chartData
            )
        }
    }

    private fun determineGranularity(startDate: LocalDate?, endDate: LocalDate?): ChartGranularity {
        if (startDate == null || endDate == null) return ChartGranularity.MONTHLY
        val days = ChronoUnit.DAYS.between(startDate, endDate)
        return when {
            days <= 30 -> ChartGranularity.DAILY
            days <= 90 -> ChartGranularity.WEEKLY
            else -> ChartGranularity.MONTHLY
        }
    }

    private fun calculateChartData(charges: List<ChargeData>, granularity: ChartGranularity,
        startDate: LocalDate?, endDate: LocalDate?): List<ChargeChartData> {
        if (charges.isEmpty()) return emptyList()

        val weekFields = WeekFields.of(Locale.getDefault())

        // Group the charges by day
        val chargesByDay = charges.mapNotNull { charge ->
            charge.startDate?.let { parseIsoInstant(it)?.atZone(clock.zone)?.toLocalDate()?.toEpochDay()?.let { day -> day to charge } }
        }.groupBy({ it.first }, { it.second })

        return when (granularity) {
            ChartGranularity.DAILY -> {
                // DAILY ranges (today, last 7 and last 30 days)
                // If not startDate (All Time), get the first trip, or today
                val start = startDate ?: (chargesByDay.keys.minOrNull()?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now(clock))
                val end = endDate ?: LocalDate.now(clock)
                val result = mutableListOf<ChargeChartData>()
                var current = start
                while (!current.isAfter(end)) {
                    val key = current.toEpochDay()
                    val itemsInDay = chargesByDay[key] ?: emptyList()
                    result.add(
                        createChargeChartPoint(
                            label = current.formatShortNoYear(Locale.getDefault()),
                            sortKey = key,
                            charges = itemsInDay,
                            dcChargeIds = _uiState.value.dcChargeIds,
                            processedChargeIds = _uiState.value.processedChargeIds
                        )
                    )
                    current = current.plusDays(1)
                }
                result
            }
            ChartGranularity.WEEKLY -> {
                // WEEKLY range (last 90 days = ~13 weeks)
                val start = startDate ?: (chargesByDay.keys.minOrNull()?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now(clock))
                val end = endDate ?: LocalDate.now(clock)

                // Get first day of the week for start date
                val weekStart = start.with(weekFields.dayOfWeek(), 1)

                // Group charges by week
                val chargesByWeek = charges.mapNotNull { charge ->
                    charge.startDate?.let { dateStr ->
                        parseIsoInstant(dateStr)?.atZone(clock.zone)?.toLocalDate()?.let { date ->
                            val firstDayOfWeek = date.with(weekFields.dayOfWeek(), 1)
                            firstDayOfWeek.toEpochDay() to charge
                        }
                    }
                }.groupBy({ it.first }, { it.second })

                // Generate all weeks in range
                val result = mutableListOf<ChargeChartData>()
                var currentWeek = weekStart
                while (!currentWeek.isAfter(end)) {
                    val key = currentWeek.toEpochDay()
                    val chargesInWeek = chargesByWeek[key] ?: emptyList()
                    val weekOfYear = currentWeek.get(weekFields.weekOfYear())
                    result.add(
                        createChargeChartPoint(
                            label = formatWeekLabel(appContext.resources, weekOfYear),
                            sortKey = key,
                            charges = chargesInWeek,
                            dcChargeIds = _uiState.value.dcChargeIds,
                            processedChargeIds = _uiState.value.processedChargeIds
                        )
                    )
                    currentWeek = currentWeek.plusWeeks(1)
                }
                result
            }

            ChartGranularity.MONTHLY -> {
                // MONTHLY range (last year = 12 months)
                val start = startDate ?: (chargesByDay.keys.minOrNull()?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now(clock))
                val end = endDate ?: LocalDate.now(clock)

                // Get first day of month for start date
                val monthStart = YearMonth.from(start).atDay(1)
                val monthEnd = YearMonth.from(end)

                // Group charges by month
                val chargesByMonth = charges.mapNotNull { charge ->
                    charge.startDate?.let { dateStr ->
                        parseIsoInstant(dateStr)?.atZone(clock.zone)?.toLocalDate()?.let { date ->
                            val firstDayOfMonth = YearMonth.from(date).atDay(1)
                            firstDayOfMonth.toEpochDay() to charge
                        }
                    }
                }.groupBy({ it.first }, { it.second })

                // Generate all months in range
                val result = mutableListOf<ChargeChartData>()
                var currentMonth = YearMonth.from(monthStart)
                while (!currentMonth.isAfter(monthEnd)) {
                    val firstDay = currentMonth.atDay(1)
                    val key = firstDay.toEpochDay()
                    val chargesInMonth = chargesByMonth[key] ?: emptyList()
                    result.add(
                        createChargeChartPoint(
                            label = firstDay.formatMonthYear(Locale.getDefault(), includeYear = start.year != end.year),
                            sortKey = key,
                            charges = chargesInMonth,
                            dcChargeIds = _uiState.value.dcChargeIds,
                            processedChargeIds = _uiState.value.processedChargeIds
                        )
                    )
                    currentMonth = currentMonth.plusMonths(1)
                }
                result
            }
        }
    }

    // Helper function to centralize chart data creation
    private fun createChargeChartPoint(
        label: String,
        sortKey: Long,
        charges: List<ChargeData>,
        dcChargeIds: Set<Int>,
        processedChargeIds: Set<Int>
    ): ChargeChartData {
        val dcCharges = charges.filter { historyChargeType(it, dcChargeIds, processedChargeIds) == ChargeType.DC }
        val acCharges = charges.filter { historyChargeType(it, dcChargeIds, processedChargeIds) == ChargeType.AC }
        val unknownCharges = charges.filter { historyChargeType(it, dcChargeIds, processedChargeIds) == ChargeType.UNKNOWN }
        val energyDc = dcCharges.mapNotNull { observedChargeEnergy(it.batteryInputKwh) }.sum()
        val energyAc = acCharges.mapNotNull { observedChargeEnergy(it.batteryInputKwh) }.sum()
        val energyUnknown = unknownCharges.mapNotNull { observedChargeEnergy(it.batteryInputKwh) }.sum()
        val energyValues = charges.mapNotNull { observedChargeEnergy(it.batteryInputKwh) }
        val energyTotal = energyValues.sum()
        val state = _uiState.value
        val dcCosts = summarizeProjectedChargeCosts(dcCharges.map { projectedCost(it, state) })
        val acCosts = summarizeProjectedChargeCosts(acCharges.map { projectedCost(it, state) })
        val unknownCosts = summarizeProjectedChargeCosts(unknownCharges.map { projectedCost(it, state) })
        val costs = summarizeProjectedChargeCosts(charges.map { projectedCost(it, state) })
        val countDc = dcCharges.size
        val countTotal = charges.size
        return ChargeChartData(
            label = label,
            totalEnergy = energyTotal,
            totalCost = costs.total ?: 0.0,
            count = countTotal,
            sortKey = sortKey,
            energyDc = energyDc,
            energyAc = energyAc,
            energyUnknown = energyUnknown,
            costDc = dcCosts.total ?: 0.0,
            costAc = acCosts.total ?: 0.0,
            costUnknown = unknownCosts.total ?: 0.0,
            countDc = countDc,
            countAc = acCharges.size,
            countUnknown = unknownCharges.size,
            costCoverage = costs.coveredCount,
            estimatedCostCount = costs.estimatedCount,
            energyCoverage = energyValues.size
        )
    }

    fun saveManualTotalAmount(chargeId: Int, totalAmount: Double?) {
        val currentCarId = historyCarId ?: return
        val remoteCarId = carId ?: return
        val charge = allCharges.firstOrNull { it.chargeId == chargeId } ?: return
        val currencyCode = _uiState.value.currencyCode
        if (Currency.entries.none { it.code.equals(currencyCode, ignoreCase = true) }) {
            _uiState.update { it.copy(error = appContext.getString(R.string.charge_cost_currency_unverified)) }
            return
        }
        val expectedVersion = findChargeCostOverride(allCostRecords, currentCarId, charge, currencyCode)?.version ?: 0L
        val validTotal = validManualChargeTotal(totalAmount)
        if (totalAmount != null && validTotal == null) return

        viewModelScope.launch {
            val proof = when (val result = historyRepository.resolveContext(remoteCarId)) {
                is ApiResult.Error -> {
                    if (carId == remoteCarId && historyCarId == currentCarId) _uiState.update { it.copy(error = result.message) }
                    return@launch
                }
                is ApiResult.Success -> result.data
            }
            if (carId != remoteCarId || historyCarId != currentCarId || _uiState.value.currencyCode != currencyCode ||
                proof.context.localHistoryCarId != currentCarId || !historyRepository.isContextCurrent(proof)) return@launch
            val saved = chargeCostOverrideStore.saveBound(currentCarId, charge, validTotal,
                currencyCode, expectedVersion)
            if (!historyRepository.isContextCurrent(proof)) return@launch
            if (!saved) _uiState.update { it.copy(error = appContext.getString(R.string.charge_cost_edit_conflict)) }
        }
    }

    private fun calculateSummary(charges: List<ChargeData>): ChargesSummary {
        if (charges.isEmpty()) return ChargesSummary()

        val energyValues = charges.mapNotNull { observedChargeEnergy(it.batteryInputKwh) }
        val totalEnergy = energyValues.takeIf { it.isNotEmpty() }?.sum()
        val state = _uiState.value
        val costs = summarizeProjectedChargeCosts(charges.map { projectedCost(it, state) })
        val count = charges.size

        return ChargesSummary(
            totalCharges = count,
            totalEnergyAdded = totalEnergy,
            totalCost = costs.total,
            avgEnergyPerCharge = totalEnergy?.let { it / energyValues.size },
            avgCostPerCharge = costs.average,
            costCoverage = costs.coveredCount,
            estimatedCostCount = costs.estimatedCount
        )
    }

    private fun projectedCost(charge: ChargeData, state: ChargesUiState): EffectiveChargeCost {
        val isDcCharge = historyChargeType(charge, state.dcChargeIds, state.processedChargeIds) == ChargeType.DC
        val localCar = historyCarId ?: return EffectiveChargeCost(null, ChargeCostSource.UNAVAILABLE)
        return projectEffectiveChargeCost(
            charge = charge,
            localHistoryCarId = localCar,
            manual = findChargeCostOverride(allCostRecords, localCar, charge, state.currencyCode),
            selectedCurrencyCode = state.currencyCode,
            freeSupercharging = state.freeSupercharging,
            isDcCharge = isDcCharge,
            defaultPricePerKwh = state.defaultChargePrice
        )
    }

}

/** A contract without verified mode is unknown, even if a stale local AC/DC index exists. */
internal fun historyChargeType(
    charge: ChargeData,
    dcIds: Set<Int>,
    processedIds: Set<Int>
): ChargeType {
    val contract = charge.energyContract
    if (contract != null) {
        if (contract.version != 1 ||
            contract.chargeModeEvidence != "observed_boundary_modes_no_conflict" ||
            (charge.chargeType != null && charge.chargeType != contract.chargeMode)) return ChargeType.UNKNOWN
        return when (contract.chargeMode) {
            "ac" -> ChargeType.AC
            "dc" -> ChargeType.DC
            else -> ChargeType.UNKNOWN
        }
    }
    // Older TeslaMate records require actual aggregate evidence, not "not DC".
    return when {
        charge.chargeId in dcIds -> ChargeType.DC
        charge.chargeId in processedIds -> ChargeType.AC
        else -> ChargeType.UNKNOWN
    }
}

internal fun observedChargeEnergy(value: Double?): Double? =
    value?.takeIf { it.isFinite() && it >= 0.0 }

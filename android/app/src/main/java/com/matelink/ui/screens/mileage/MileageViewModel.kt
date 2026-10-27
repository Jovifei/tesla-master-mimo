package com.matelink.ui.screens.mileage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.Units
import com.matelink.data.repository.CarStatusWithUnits
import com.matelink.data.repository.UnifiedHistory
import com.matelink.domain.analytics.AnalysisPageIdentity
import com.matelink.domain.analytics.captureAnalysisPageIdentity
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.data.local.VehicleContextRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.VerifiedHistoryReadContext
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.model.Currency
import com.matelink.domain.analytics.observedCostSumOrNull
import com.matelink.domain.analytics.findChargeCostOverride
import com.matelink.domain.analytics.projectEffectiveChargeCost
import com.matelink.data.local.entity.ChargeCostOverride
import com.matelink.domain.history.LatestHistoryLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import com.matelink.domain.analytics.buildMileageEvidence
import com.matelink.domain.analytics.observedBatteryUsagePercent
import com.matelink.domain.analytics.observedDistanceKm
import com.matelink.domain.analytics.observedEnergyKwh
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.matelink.util.parseIsoDateTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import javax.inject.Inject

data class YearlyMileage(
    val year: Int,
    val totalDistance: Double,
    val driveCount: Int,
    val totalEnergy: Double,
    val totalBatteryUsage: Double,
    val totalEnergyCost: Double? = null,
    val drives: List<DriveData>
)

data class MonthlyMileage(
    val yearMonth: YearMonth,
    val totalDistance: Double,
    val driveCount: Int,
    val totalEnergy: Double,
    val totalBatteryUsage: Double,
    val totalEnergyCost: Double? = null,
    val drives: List<DriveData>
)

data class DailyMileage(
    val date: LocalDate,
    val totalDistance: Double,
    val driveCount: Int,
    val totalEnergy: Double,
    val totalBatteryUsage: Double,
    val totalEnergyCost: Double? = null,
    val drives: List<DriveData>
)

data class MileageUiState(
    // Settings
    val currencySymbol: String = com.matelink.data.model.Currency.CNY.symbol,
    val units: Units? = null,

    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val historyFreshness: HistoryFreshness = HistoryFreshness.FRESH,
    val error: String? = null,
    val allDrives: List<DriveData> = emptyList(),
    val allCharges: List<ChargeData> = emptyList(),

    // Lifetime totals (year overview)
    val yearlyData: List<YearlyMileage> = emptyList(),
    val totalLifetimeDistance: Double = 0.0,
    val avgYearlyDistance: Double = 0.0,
    val firstDriveDate: LocalDate? = null,
    val totalLifetimeDriveCount: Int = 0,
    val totalLifetimeEnergy: Double = 0.0,
    val avgLifetimeEnergyDistance: Double = 0.0,
    val totalLifetimeEnergyCost: Double? = null,

    // Year detail view state
    val selectedYear: Int? = null,
    val monthlyData: List<MonthlyMileage> = emptyList(),
    val yearTotalDistance: Double = 0.0,
    val avgMonthlyDistance: Double = 0.0,
    val yearDriveCount: Int = 0,
    val yearTotalEnergy: Double = 0.0,
    val avgYearEnergyDistance: Double = 0.0,
    val yearTotalEnergyCost: Double? = null,

    // Month detail view state
    val selectedMonth: YearMonth? = null,
    val dailyData: List<DailyMileage> = emptyList(),

    // Day detail view state
    val selectedDay: LocalDate? = null,
    val selectedDayData: DailyMileage? = null
)

/**
 * Drive (or charge) paired with its parsed [LocalDateTime]. Held in private
 * VM-scoped lists so repeated year / month / day aggregations don't have to
 * re-parse the same `startDate` string. With ~500 drives + ~200 charges that's
 * upwards of 2,000 string-parse calls saved per re-aggregation.
 */
private data class TimedDrive(val drive: DriveData, val dateTime: LocalDateTime)
private data class TimedCharge(val charge: ChargeData, val dateTime: LocalDateTime, val effectiveCost: Double?)

/**
 * Bundle holding everything `aggregateByYear` writes back to UiState. Computed
 * off the main thread inside a `withContext(Dispatchers.Default)` block so the
 * UI thread is free during the (non-trivial) lifetime aggregation.
 */
private data class YearAggregation(
    val yearlyData: List<YearlyMileage>,
    val totalLifetimeDistance: Double,
    val totalLifetimeDriveCount: Int,
    val totalLifetimeEnergy: Double,
    val avgLifetimeEnergyDistance: Double,
    val totalLifetimeEnergyCost: Double?,
    val firstDriveDate: LocalDate?,
    val avgYearlyDistance: Double,
)

private data class MonthAggregation(
    val monthlyData: List<MonthlyMileage>,
    val yearTotalDistance: Double,
    val avgMonthlyDistance: Double,
    val yearDriveCount: Int,
    val yearTotalEnergy: Double,
    val avgYearEnergyDistance: Double,
    val yearTotalEnergyCost: Double?,
)

internal interface MileagePageSource {
    val scopeChanges: Flow<Unit>
    suspend fun identity(carId: Int): AnalysisPageIdentity
    suspend fun status(carId: Int): ApiResult<CarStatusWithUnits>
    suspend fun history(carId: Int): ApiResult<UnifiedHistory>
    suspend fun loadCached(carId: Int): ApiResult<UnifiedHistory> = ApiResult.Error(
        "mileage_cache_unavailable")
    suspend fun isContextCurrent(proof: VerifiedHistoryReadContext): Boolean
    fun isContextCurrentNow(proof: VerifiedHistoryReadContext): Boolean
    suspend fun currencyCode(): String
    suspend fun manualRecords(): List<ChargeCostOverride>
}

@HiltViewModel
class MileageViewModel internal constructor(
    private val source: MileagePageSource,
    private val aggregationDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {
    @Inject constructor(
        repository: TeslamateRepository,
        settingsDataStore: SettingsDataStore,
        unifiedHistoryRepository: UnifiedHistoryRepository,
        chargeCostOverrideStore: ChargeCostOverrideStore,
        contexts: VehicleContextRepository
    ) : this(object : MileagePageSource {
        override val scopeChanges = contexts.historyScopeChanges.map { Unit }
        override suspend fun identity(carId: Int) = contexts.captureAnalysisPageIdentity(carId)
        override suspend fun status(carId: Int) = repository.getCarStatus(carId)
        override suspend fun history(carId: Int) = unifiedHistoryRepository.load(carId)
        override suspend fun loadCached(carId: Int) = unifiedHistoryRepository.loadCached(carId)
        override suspend fun isContextCurrent(proof: VerifiedHistoryReadContext) = unifiedHistoryRepository.isContextCurrent(proof)
        override fun isContextCurrentNow(proof: VerifiedHistoryReadContext) = contexts.isHistoryContextCurrentNow(proof.scope, proof.context)
        override suspend fun currencyCode() = settingsDataStore.settings.first().currencyCode
        override suspend fun manualRecords() = chargeCostOverrideStore.getAllRecords()
    })

    private val _uiState = MutableStateFlow(MileageUiState())
    val uiState: StateFlow<MileageUiState> = _uiState.asStateFlow()

    private var carId: Int? = null
    private var loadGeneration = 0L
    private var projectionVersion = 0L
    private var selectionGeneration = 0L
    private var historyProof: VerifiedHistoryReadContext? = null
    private var identityGeneration = 0L
    private var activeIdentity: AnalysisPageIdentity? = null
    private var activationJob: Job? = null
    private var unitsJob: Job? = null
    private var historyJob: Job? = null
    private val latestHistory = LatestHistoryLoad()

    // Pre-parsed drive / charge lists. Populated once per loadAllDrives,
    // reused across all subsequent year / month / day aggregations.
    private var timedDrives: List<TimedDrive> = emptyList()
    private var timedCharges: List<TimedCharge> = emptyList()

    init {
        loadSettings()
        viewModelScope.launch {
            source.scopeChanges.collect {
                if (activeIdentity?.stillCurrent() == false || historyProof?.let { !source.isContextCurrentNow(it) } == true)
                    carId?.let(::activate)
            }
        }
    }

    private fun loadSettings() {
        val initialGeneration = loadGeneration
        viewModelScope.launch {
            val currency = try { Currency.findByCode(source.currencyCode()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@launch }
            if (initialGeneration == loadGeneration) _uiState.update { it.copy(currencySymbol = currency.symbol) }
        }
    }

    private fun clearProjection() {
        historyProof = null
        projectionVersion++
        selectionGeneration++
        timedDrives = emptyList()
        timedCharges = emptyList()
        _uiState.update { MileageUiState(currencySymbol = it.currencySymbol) }
    }

    fun setCarId(id: Int) {
        if (carId != id || activeIdentity?.stillCurrent() == false ||
            historyProof?.let { !source.isContextCurrentNow(it) } == true ||
            (activeIdentity == null && activationJob?.isActive != true)) activate(id)
    }

    private fun activate(id: Int) {
        carId = id
        val generation = ++identityGeneration
        loadGeneration++
        activationJob?.cancel()
        unitsJob?.cancel()
        historyJob?.cancel()
        activeIdentity = null
        clearProjection()
        activationJob = viewModelScope.launch {
            val execution = currentCoroutineContext()
            try {
                val identity = source.identity(id)
                execution.ensureActive()
                if (generation != identityGeneration || carId != id || !identity.stillCurrent())
                    throw CancellationException("Mileage identity superseded")
                activeIdentity = identity
                loadUnits(id, generation, identity)
                loadAllDrives()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == identityGeneration && carId == id && execution.isActive)
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = HISTORY_IDENTITY_UNAVAILABLE) }
            }
        }
    }

    private fun loadUnits(id: Int, generation: Long, identity: AnalysisPageIdentity) {
        // Exactly one status request per identity activation. A history refresh does not replace it.
        unitsJob = viewModelScope.launch {
            val result = source.status(id)
            currentCoroutineContext().ensureActive()
            if (generation != identityGeneration || carId != id || !identity.stillCurrent())
                throw CancellationException("Mileage units identity changed")
            when (result) {
                is ApiResult.Success -> _uiState.update { it.copy(units = result.data.units) }
                is ApiResult.Error -> Unit
            }
        }
    }

    fun refresh() {
        carId?.let { id ->
            if (activeIdentity?.stillCurrent() == false || historyProof?.let { !source.isContextCurrentNow(it) } == true) {
                activate(id)
            } else {
                _uiState.update { it.copy(isRefreshing = true) }
                if (activeIdentity != null) loadAllDrives()
            }
        }
    }

    fun clearError() {
        if (ensureSelectionContext()) _uiState.update { it.copy(error = null) }
    }

    private fun ensureSelectionContext(): Boolean {
        if (activeIdentity?.stillCurrent() == true && historyProof?.let { source.isContextCurrentNow(it) } != false)
            return true
        clearProjection()
        _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = HISTORY_IDENTITY_UNAVAILABLE) }
        return false
    }

    fun selectYear(year: Int) {
        if (!ensureSelectionContext()) return
        selectionGeneration++
        _uiState.update { it.copy(selectedYear = year) }
        viewModelScope.launch { aggregateByMonth(year) }
    }

    fun clearSelectedYear() {
        if (!ensureSelectionContext()) return
        selectionGeneration++
        _uiState.update {
            it.copy(
                selectedYear = null,
                monthlyData = emptyList(),
                yearTotalDistance = 0.0,
                avgMonthlyDistance = 0.0,
                yearDriveCount = 0
            )
        }
    }

    fun selectMonth(yearMonth: YearMonth) {
        if (!ensureSelectionContext()) return
        selectionGeneration++
        _uiState.update { it.copy(selectedMonth = yearMonth) }
        viewModelScope.launch { aggregateByDay(yearMonth) }
    }

    fun clearSelectedMonth() {
        if (!ensureSelectionContext()) return
        selectionGeneration++
        _uiState.update { it.copy(selectedMonth = null, dailyData = emptyList()) }
    }

    fun selectDay(date: LocalDate) {
        if (!ensureSelectionContext()) return
        selectionGeneration++
        val dayData = _uiState.value.dailyData.find { it.date == date }
        _uiState.update { it.copy(selectedDay = date, selectedDayData = dayData) }
    }

    fun clearSelectedDay() {
        if (!ensureSelectionContext()) return
        selectionGeneration++
        _uiState.update { it.copy(selectedDay = null, selectedDayData = null) }
    }

    /**
     * Navigates directly to a specific day's detail view. Aggregations now run
     * on Dispatchers.Default — chained inside a single coroutine here so the
     * day's data is actually populated by the time `selectDay` looks for it.
     */
    fun navigateToDay(date: LocalDate) {
        if (!ensureSelectionContext()) return
        val selection = ++selectionGeneration
        val generation = loadGeneration
        val ym = YearMonth.of(date.year, date.month)
        viewModelScope.launch {
            if (selection != selectionGeneration || generation != loadGeneration || !ensureSelectionContext()) return@launch
            _uiState.update { it.copy(selectedYear = date.year) }
            aggregateByMonth(date.year)
            if (selection != selectionGeneration || generation != loadGeneration || !ensureSelectionContext()) return@launch
            _uiState.update { it.copy(selectedMonth = ym) }
            aggregateByDay(ym)
            if (selection != selectionGeneration || generation != loadGeneration || !ensureSelectionContext()) return@launch
            val dayData = _uiState.value.dailyData.find { it.date == date }
            _uiState.update { it.copy(selectedDay = date, selectedDayData = dayData) }
        }
    }

    private fun loadAllDrives() {
        val id = carId ?: return
        val identity = activeIdentity ?: return
        val generation = ++loadGeneration
        latestHistory.launch(viewModelScope) {
            val execution = currentCoroutineContext()
            historyJob = execution[Job]
            fun requireIdentity() {
                execution.ensureActive()
                if (generation != loadGeneration || carId != id) throw CancellationException("Mileage history superseded")
                if (!identity.stillCurrent() || historyProof?.let { !source.isContextCurrentNow(it) } == true) {
                    clearProjection()
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = HISTORY_IDENTITY_UNAVAILABLE) }
                    throw CancellationException("Mileage history identity changed")
                }
            }
            // Publish one prepared projection. Both the cached (STALE) and the remote (real
            // freshness) paths share the same verified proof, currency and manual cost rules,
            // so offline and online always show one consistent projection of the same vehicle.
            suspend fun publish(unified: UnifiedHistory, freshness: HistoryFreshness) {
                val proof = unified.verifiedReadContext
                requireIdentity()
                if (proof == null || proof.context != unified.context || proof.context != identity.context ||
                    !source.isContextCurrentNow(proof)) {
                    clearProjection()
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = HISTORY_IDENTITY_UNAVAILABLE) }
                    throw CancellationException("Mileage history proof changed")
                }
                val selectedCurrencyCode = try { source.currencyCode() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                ensureCurrent()
                requireIdentity()
                val manualRecords = try { source.manualRecords() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                ensureCurrent()
                requireIdentity()
                val drives = unified.drives
                val charges = unified.charges
                data class PreparedAndYear(
                    val timedDrives: List<TimedDrive>, val timedCharges: List<TimedCharge>, val agg: YearAggregation
                )
                val prepared = withContext(aggregationDispatcher) {
                    val td = drives.mapNotNull { drive -> parseDateTime(drive.startDate)?.let { TimedDrive(drive, it) } }
                    val tc = charges.mapNotNull { charge -> parseDateTime(charge.startDate)?.let { TimedCharge(charge, it,
                        if (manualRecords != null && selectedCurrencyCode != null)
                            projectMileageChargeCost(charge, proof.context.localHistoryCarId, manualRecords, selectedCurrencyCode)
                        else null) } }
                    PreparedAndYear(td, tc, computeYearAggregation(td, tc))
                }
                ensureCurrent()
                requireIdentity()
                // Publish every open level together. If selection changes during calculation,
                // prepare its new details before exposing any part of the new history snapshot.
                while (true) {
                    val selected = _uiState.value
                    val details = if (selected.selectedYear != null || selected.selectedMonth != null) {
                        withContext(aggregationDispatcher) {
                            selected.selectedYear?.let { computeMonthAggregation(prepared.timedDrives, prepared.timedCharges, it) } to
                                selected.selectedMonth?.let { computeDailyAggregation(prepared.timedDrives, prepared.timedCharges, it) }.orEmpty()
                        }
                    } else null to emptyList<DailyMileage>()
                    ensureCurrent()
                    requireIdentity()
                    if (!source.isContextCurrentNow(proof)) {
                        clearProjection()
                        _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = HISTORY_IDENTITY_UNAVAILABLE) }
                        throw CancellationException("Mileage history proof changed")
                    }
                    val current = _uiState.value
                    if (current.selectedYear != selected.selectedYear || current.selectedMonth != selected.selectedMonth) continue
                    historyProof = proof
                    projectionVersion++
                    timedDrives = prepared.timedDrives
                    timedCharges = prepared.timedCharges
                    val month = details.first
                    _uiState.update { it.copy(
                        currencySymbol = selectedCurrencyCode?.let { code -> Currency.findByCode(code).symbol } ?: "?",
                        isLoading = false, isRefreshing = false, allDrives = drives, allCharges = charges, error = null,
                        historyFreshness = freshness,
                        yearlyData = prepared.agg.yearlyData, totalLifetimeDistance = prepared.agg.totalLifetimeDistance,
                        avgYearlyDistance = prepared.agg.avgYearlyDistance, firstDriveDate = prepared.agg.firstDriveDate,
                        totalLifetimeEnergy = prepared.agg.totalLifetimeEnergy,
                        avgLifetimeEnergyDistance = prepared.agg.avgLifetimeEnergyDistance,
                        totalLifetimeEnergyCost = prepared.agg.totalLifetimeEnergyCost,
                        totalLifetimeDriveCount = prepared.agg.totalLifetimeDriveCount,
                        monthlyData = month?.monthlyData.orEmpty(), yearTotalDistance = month?.yearTotalDistance ?: 0.0,
                        avgMonthlyDistance = month?.avgMonthlyDistance ?: 0.0, yearDriveCount = month?.yearDriveCount ?: 0,
                        yearTotalEnergy = month?.yearTotalEnergy ?: 0.0,
                        avgYearEnergyDistance = month?.avgYearEnergyDistance ?: 0.0,
                        yearTotalEnergyCost = month?.yearTotalEnergyCost, dailyData = details.second,
                        selectedDayData = it.selectedDay?.let { day -> details.second.find { row -> row.date == day } }) }
                    break
                }
            }
            try {
                ensureCurrent()
                requireIdentity()
                if (!_uiState.value.isRefreshing) _uiState.update { it.copy(isLoading = true) }
                var cachedPublished = false
                // Prefer already persisted Room history while the network is slow or offline. A
                // corrupt/unreadable Room read must never suppress a valid remote load.
                val saved = try { source.loadCached(id) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                ensureCurrent()
                requireIdentity()
                if (saved is ApiResult.Success) {
                    publish(saved.data, HistoryFreshness.STALE)
                    cachedPublished = true
                }
                val historyResult = source.history(id)
                ensureCurrent()
                requireIdentity()
                when (historyResult) {
                    is ApiResult.Success -> {
                        // A remote result is only FRESH when both drives and charges were confirmed from
                        // the remote; a merged/exhausted remote with local-only halves stays STALE.
                        val remoteFresh = historyResult.data.drivesFromRemote && historyResult.data.chargesFromRemote
                        publish(historyResult.data, if (remoteFresh) HistoryFreshness.FRESH else HistoryFreshness.STALE)
                    }
                    is ApiResult.Error -> if (historyResult.message == HISTORY_IDENTITY_UNAVAILABLE) {
                        clearProjection()
                        _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = HISTORY_IDENTITY_UNAVAILABLE) }
                    } else if (!cachedPublished) {
                        _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = historyResult.message) }
                    } else {
                        // Keep the cached projection visible but clear any lingering spinner.
                        _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == loadGeneration && carId == id && execution.isActive) {
                    val valid = failure !is com.matelink.data.local.HistoryIdentityUnavailableException &&
                        identity.stillCurrent() && historyProof?.let(source::isContextCurrentNow) == true
                    if (!valid) clearProjection()
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false,
                        error = if (valid) null else HISTORY_IDENTITY_UNAVAILABLE) }
                }
            }
        }
    }

    private fun historyStillCurrent(generation: Long, proof: VerifiedHistoryReadContext): Boolean =
        generation == loadGeneration && carId == proof.context.remoteApiCarId &&
            activeIdentity?.stillCurrent() == true && source.isContextCurrentNow(proof)

    private suspend fun aggregateByMonth(year: Int) {
        val generation = loadGeneration
        val version = projectionVersion
        val proof = historyProof ?: return
        val drivesSnapshot = timedDrives
        val chargesSnapshot = timedCharges
        val agg = withContext(aggregationDispatcher) {
            computeMonthAggregation(drivesSnapshot, chargesSnapshot, year)
        }
        val proofCurrent = source.isContextCurrent(proof)
        if (generation != loadGeneration || version != projectionVersion || carId != proof.context.remoteApiCarId) return
        if (!proofCurrent || !historyStillCurrent(generation, proof)) {
            clearProjection()
            _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = HISTORY_IDENTITY_UNAVAILABLE) }
            return
        }
        if (_uiState.value.selectedYear != year) return
        _uiState.update {
            it.copy(
                monthlyData = agg.monthlyData,
                yearTotalDistance = agg.yearTotalDistance,
                avgMonthlyDistance = agg.avgMonthlyDistance,
                yearTotalEnergy = agg.yearTotalEnergy,
                avgYearEnergyDistance = agg.avgYearEnergyDistance,
                yearTotalEnergyCost = agg.yearTotalEnergyCost,
                yearDriveCount = agg.yearDriveCount,
            )
        }
    }

    private suspend fun aggregateByDay(yearMonth: YearMonth) {
        val generation = loadGeneration
        val version = projectionVersion
        val proof = historyProof ?: return
        val drivesSnapshot = timedDrives
        val chargesSnapshot = timedCharges
        val dailyData = withContext(aggregationDispatcher) {
            computeDailyAggregation(drivesSnapshot, chargesSnapshot, yearMonth)
        }
        val proofCurrent = source.isContextCurrent(proof)
        if (generation != loadGeneration || version != projectionVersion || carId != proof.context.remoteApiCarId) return
        if (!proofCurrent || !historyStillCurrent(generation, proof)) {
            clearProjection()
            _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = HISTORY_IDENTITY_UNAVAILABLE) }
            return
        }
        if (_uiState.value.selectedMonth != yearMonth) return
        _uiState.update { it.copy(dailyData = dailyData,
            selectedDayData = it.selectedDay?.let { day -> dailyData.find { row -> row.date == day } }) }
    }

    // Get yearly data for chart
    fun getYearlyChartData(): List<Pair<Int, Double>> {
        val state = _uiState.value
        return state.yearlyData
            .filter { buildMileageEvidence(it.drives).hasDistance }
            .map { it.year to it.totalDistance }
            .sortedBy { it.first }
    }

    // Get monthly data for chart. Months without an observed distance are not
    // emitted, so a missing metric cannot become a zero-height data point.
    fun getMonthlyChartData(): List<Pair<Int, Double>> {
        val state = _uiState.value
        return state.monthlyData
            .filter { buildMileageEvidence(it.drives).hasDistance }
            .map { it.yearMonth.monthValue to it.totalDistance }
            .sortedBy { it.first }
    }

    // Get daily data for chart within selected month
    fun getDailyChartData(): List<Pair<Int, Double>> {
        val state = _uiState.value
        val selectedMonth = state.selectedMonth ?: return emptyList()
        val dailyMap = state.dailyData.associate { it.date.dayOfMonth to it.totalDistance }

        val daysInMonth = selectedMonth.lengthOfMonth()
        return (1..daysInMonth).mapNotNull { day ->
            val dayData = state.dailyData.find { it.date.dayOfMonth == day }
            if (dayData != null && buildMileageEvidence(dayData.drives).hasDistance) {
                day to dayData.totalDistance
            } else null
        }
    }
}

// ---------------------------------------------------------------------------
// Pure aggregation helpers. Kept as top-level private functions so they have
// no dependency on VM state and can run safely on Dispatchers.Default — the
// caller passes in the snapshot of pre-parsed data they want aggregated.
// ---------------------------------------------------------------------------

private fun parseDateTime(dateStr: String?): LocalDateTime? =
    parseIsoDateTime(dateStr)

internal fun projectMileageChargeCost(
    charge: ChargeData, localHistoryCarId: Int, manualRecords: List<ChargeCostOverride>, selectedCurrencyCode: String
): Double? = projectEffectiveChargeCost(charge, localHistoryCarId,
    findChargeCostOverride(manualRecords, localHistoryCarId, charge, selectedCurrencyCode), selectedCurrencyCode).cost

private fun computeYearAggregation(
    drives: List<TimedDrive>,
    charges: List<TimedCharge>,
): YearAggregation {
    val grouped = drives.groupBy { it.dateTime.year }
    val chargesByYear = charges.groupBy { it.dateTime.year }

    val yearlyData = grouped.map { (year, yearDrives) ->
        var totalDistance = 0.0
        var totalEnergy = 0.0
        var totalBatteryUsage = 0.0
        for (td in yearDrives) {
            totalDistance += td.drive.observedDistanceKm() ?: 0.0
            totalEnergy += td.drive.observedEnergyKwh() ?: 0.0
            totalBatteryUsage += td.drive.observedBatteryUsagePercent() ?: 0.0
        }
        val totalCost = observedCostSumOrNull(chargesByYear[year].orEmpty().map { it.effectiveCost })

        YearlyMileage(
            year = year,
            totalDistance = totalDistance,
            driveCount = yearDrives.size,
            totalEnergy = totalEnergy,
            totalBatteryUsage = totalBatteryUsage,
            totalEnergyCost = totalCost,
            drives = yearDrives.map { it.drive },
        )
    }.sortedByDescending { it.year }

    val totalLifetimeDistance = yearlyData.sumOf { it.totalDistance }
    val totalLifetimeDriveCount = yearlyData.sumOf { it.driveCount }
    val totalLifetimeEnergy = yearlyData.sumOf { it.totalEnergy }
    val avgLifetimeEnergyDistance = buildMileageEvidence(drives.map { it.drive }).efficiencyWhKm ?: 0.0
    val totalLifetimeEnergyCost = observedCostSumOrNull(charges.map { it.effectiveCost })

    val firstDriveDate = drives.minByOrNull { it.dateTime }?.dateTime?.toLocalDate()

    val avgYearlyDistance = if (firstDriveDate != null && totalLifetimeDistance > 0) {
        val daysSinceFirstDrive = java.time.temporal.ChronoUnit.DAYS.between(firstDriveDate, LocalDate.now())
        if (daysSinceFirstDrive > 0) {
            (totalLifetimeDistance / daysSinceFirstDrive) * 365
        } else {
            totalLifetimeDistance
        }
    } else {
        0.0
    }

    return YearAggregation(
        yearlyData = yearlyData,
        totalLifetimeDistance = totalLifetimeDistance,
        totalLifetimeDriveCount = totalLifetimeDriveCount,
        totalLifetimeEnergy = totalLifetimeEnergy,
        avgLifetimeEnergyDistance = avgLifetimeEnergyDistance,
        totalLifetimeEnergyCost = totalLifetimeEnergyCost,
        firstDriveDate = firstDriveDate,
        avgYearlyDistance = avgYearlyDistance,
    )
}

private fun computeMonthAggregation(
    drives: List<TimedDrive>,
    charges: List<TimedCharge>,
    year: Int,
): MonthAggregation {
    val yearDrives = drives.filter { it.dateTime.year == year }
    val grouped = yearDrives.groupBy { YearMonth.of(it.dateTime.year, it.dateTime.month) }
    val chargesByYearMonth = charges
        .filter { it.dateTime.year == year }
        .groupBy { YearMonth.of(it.dateTime.year, it.dateTime.month) }

    val monthlyData = grouped.map { (yearMonth, monthDrives) ->
        var totalDistance = 0.0
        var totalEnergy = 0.0
        var totalBatteryUsage = 0.0
        for (td in monthDrives) {
            totalDistance += td.drive.observedDistanceKm() ?: 0.0
            totalEnergy += td.drive.observedEnergyKwh() ?: 0.0
            totalBatteryUsage += td.drive.observedBatteryUsagePercent() ?: 0.0
        }
        val totalCost = observedCostSumOrNull(chargesByYearMonth[yearMonth].orEmpty().map { it.effectiveCost })

        MonthlyMileage(
            yearMonth = yearMonth,
            totalDistance = totalDistance,
            driveCount = monthDrives.size,
            totalEnergy = totalEnergy,
            totalBatteryUsage = totalBatteryUsage,
            totalEnergyCost = totalCost,
            drives = monthDrives.map { it.drive },
        )
    }.sortedByDescending { it.yearMonth }

    val yearTotalDistance = monthlyData.sumOf { it.totalDistance }
    val yearDriveCount = monthlyData.sumOf { it.driveCount }
    val avgMonthlyDistance = if (monthlyData.isNotEmpty()) yearTotalDistance / monthlyData.size else 0.0
    val yearTotalEnergy = monthlyData.sumOf { it.totalEnergy }
    val avgYearEnergyDistance = buildMileageEvidence(yearDrives.map { it.drive }).efficiencyWhKm ?: 0.0
    val yearTotalEnergyCost = observedCostSumOrNull(charges.filter { it.dateTime.year == year }.map { it.effectiveCost })

    return MonthAggregation(
        monthlyData = monthlyData,
        yearTotalDistance = yearTotalDistance,
        avgMonthlyDistance = avgMonthlyDistance,
        yearDriveCount = yearDriveCount,
        yearTotalEnergy = yearTotalEnergy,
        avgYearEnergyDistance = avgYearEnergyDistance,
        yearTotalEnergyCost = yearTotalEnergyCost,
    )
}

private fun computeDailyAggregation(
    drives: List<TimedDrive>,
    charges: List<TimedCharge>,
    yearMonth: YearMonth,
): List<DailyMileage> {
    val monthDrives = drives.filter {
        YearMonth.of(it.dateTime.year, it.dateTime.month) == yearMonth
    }
    val grouped = monthDrives.groupBy { it.dateTime.toLocalDate() }
    val chargesByDay = charges
        .filter { YearMonth.of(it.dateTime.year, it.dateTime.month) == yearMonth }
        .groupBy { it.dateTime.toLocalDate() }

    return grouped.map { (date, dayDrives) ->
        var totalDistance = 0.0
        var totalEnergy = 0.0
        var totalBatteryUsage = 0.0
        for (td in dayDrives) {
            totalDistance += td.drive.observedDistanceKm() ?: 0.0
            totalEnergy += td.drive.observedEnergyKwh() ?: 0.0
            totalBatteryUsage += td.drive.observedBatteryUsagePercent() ?: 0.0
        }
        val totalCost = observedCostSumOrNull(chargesByDay[date].orEmpty().map { it.effectiveCost })

        DailyMileage(
            date = date,
            totalDistance = totalDistance,
            driveCount = dayDrives.size,
            totalEnergy = totalEnergy,
            totalBatteryUsage = totalBatteryUsage,
            totalEnergyCost = totalCost,
            drives = dayDrives
                .sortedByDescending { it.dateTime }
                .map { it.drive },
        )
    }.sortedByDescending { it.date }
}

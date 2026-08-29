package com.matelink.ui.screens.reports

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.R
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.dao.MonthlyChargeAggregation
import com.matelink.data.local.dao.MonthlyDriveAggregation
import com.matelink.data.model.Currency
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.StatsRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.model.CarStats
import com.matelink.domain.model.YearFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class AnnualReportUiState(
    val isLoading: Boolean = true,
    val historyFreshness: HistoryFreshness = HistoryFreshness.FRESH,
    val year: Int = java.time.Year.now().value,
    val carStats: CarStats? = null,
    val monthlyDrives: List<MonthlyDriveAggregation> = emptyList(),
    val monthlyCharges: List<MonthlyChargeAggregation> = emptyList(),
    val effectiveCost: Double? = null,
    val standbyKwh: Double? = null,
    val currencySymbol: String = Currency.CNY.symbol,
    val availableYears: List<Int> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class AnnualReportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val historyRepository: AnalysisHistoryRepository,
    private val chargeCostOverrideStore: ChargeCostOverrideStore,
    private val settingsDataStore: SettingsDataStore,
    private val teslamateRepository: TeslamateRepository,
    private val aggregateDao: AggregateDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnnualReportUiState())
    val uiState: StateFlow<AnnualReportUiState> = _uiState.asStateFlow()

    private var carId: Int = 1
    private var loadJob: kotlinx.coroutines.Job? = null

    fun init(carId: Int, year: Int) {
        this.carId = carId
        _uiState.value = _uiState.value.copy(year = year)
        loadYears()
        loadReport()
    }

    private fun loadYears() {
        viewModelScope.launch {
            try {
                val years = availableReportYears(
                    java.time.Year.now().value,
                    statsRepository.getAvailableYears(carId)
                )
                _uiState.value = _uiState.value.copy(availableYears = years)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.value = _uiState.value.copy(
                    error = context.getString(
                        R.string.error_load_years,
                        e.message ?: ""
                    )
                )
            }
        }
    }

    fun selectYear(year: Int) {
        _uiState.value = _uiState.value.copy(year = year)
        loadReport()
    }

    private fun loadReport() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null
            )
            try {
                val year = _uiState.value.year
                val yearFilter = YearFilter.Year(year)
                val currencySymbol = Currency.findByCode(
                    settingsDataStore.settings.first().currencyCode
                ).symbol
                val stats = statsRepository.getStats(carId, yearFilter)
                val monthlyDrives =
                    statsRepository.getMonthlyDriveAggregation(carId, year)
                val monthlyCharges =
                    statsRepository.getMonthlyChargeAggregation(carId, year)

                var historyFreshness = HistoryFreshness.FRESH
                val history = when (
                    val historyResult = historyRepository.load(carId)
                ) {
                    is ApiResult.Success -> {
                        historyFreshness = historyResult.data.freshness
                        historyResult.data
                    }
                    is ApiResult.Error -> null
                }

                val manualTotals = chargeCostOverrideStore.getAll()
                val freeSupercharging = when (
                    val carResult = teslamateRepository.getCar(carId)
                ) {
                    is ApiResult.Success ->
                        carResult.data.carSettings?.freeSupercharging == true
                    is ApiResult.Error -> false
                }
                val dcChargeIds = runCatching {
                    aggregateDao.getDcChargeIds(carId).toSet()
                }.getOrDefault(emptySet())

                val effectiveCost = history?.let {
                    annualEffectiveCost(
                        carId = carId,
                        year = year,
                        charges = it.charges,
                        manualTotals = manualTotals,
                        freeSupercharging = freeSupercharging,
                        dcChargeIds = dcChargeIds
                    )
                }
                val standbyKwh = when (
                    val standbyResult =
                        teslamateRepository.getStandbyWindows(carId)
                ) {
                    is ApiResult.Success ->
                        annualStandbyKwh(year, standbyResult.data)
                    is ApiResult.Error -> null
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    carStats = stats,
                    monthlyDrives = monthlyDrives,
                    monthlyCharges = monthlyCharges,
                    effectiveCost = effectiveCost,
                    standbyKwh = standbyKwh,
                    currencySymbol = currencySymbol,
                    historyFreshness = historyFreshness
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                        ?: context.getString(R.string.error_load_report)
                )
            }
        }
    }
}

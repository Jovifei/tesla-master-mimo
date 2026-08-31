package com.matelink.ui.screens.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.matelink.R
import dagger.hilt.android.qualifiers.ApplicationContext
import com.matelink.data.local.dao.MonthlyChargeAggregation
import com.matelink.data.local.dao.MonthlyDriveAggregation
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.model.Currency
import com.matelink.data.repository.StatsRepository
import com.matelink.data.repository.ApiResult
import com.matelink.domain.model.CarStats
import com.matelink.domain.model.YearFilter
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.HistoryFreshness
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val settingsDataStore: SettingsDataStore
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
                val years = availableReportYears(java.time.Year.now().value, statsRepository.getAvailableYears(carId))
                _uiState.value = _uiState.value.copy(availableYears = years)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.value = _uiState.value.copy(error = context.getString(R.string.error_load_years, e.message ?: ""))
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
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val year = _uiState.value.year
                val yearFilter = YearFilter.Year(year)
                val currencySymbol = Currency.findByCode(
                    settingsDataStore.settings.first().currencyCode
                ).symbol
                val stats = statsRepository.getStats(carId, yearFilter)
                val monthlyDrives = statsRepository.getMonthlyDriveAggregation(carId, year)
                val monthlyCharges = statsRepository.getMonthlyChargeAggregation(carId, year)
                var historyFreshness = HistoryFreshness.FRESH
                val remoteMetrics = runCatching {
                    when (val history = historyRepository.load(carId)) {
                        is ApiResult.Success -> {
                            historyFreshness = history.data.freshness
                            val manualTotals = chargeCostOverrideStore.getAll()
                            val effectiveCost = annualEffectiveCost(
                                history.data.context?.localHistoryCarId ?: carId,
                                year,
                                history.data.charges,
                                manualTotals
                            )
                            val standbyKwh = annualStandbyKwh(year, history.data.charges, history.data.drives)
                            effectiveCost to standbyKwh
                        }
                        is ApiResult.Error -> null
                    }
                }.getOrNull()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    carStats = stats,
                    monthlyDrives = monthlyDrives,
                    monthlyCharges = monthlyCharges,
                    effectiveCost = remoteMetrics?.first,
                    standbyKwh = remoteMetrics?.second,
                    currencySymbol = currencySymbol,
                    historyFreshness = historyFreshness
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: context.getString(R.string.error_load_report)
                )
            }
        }
    }
}

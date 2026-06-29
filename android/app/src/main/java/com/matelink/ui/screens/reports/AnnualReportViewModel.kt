package com.matelink.ui.screens.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.dao.MonthlyChargeAggregation
import com.matelink.data.local.dao.MonthlyDriveAggregation
import com.matelink.data.repository.StatsRepository
import com.matelink.domain.model.CarStats
import com.matelink.domain.model.YearFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnnualReportUiState(
    val isLoading: Boolean = true,
    val year: Int = 2025,
    val carStats: CarStats? = null,
    val monthlyDrives: List<MonthlyDriveAggregation> = emptyList(),
    val monthlyCharges: List<MonthlyChargeAggregation> = emptyList(),
    val availableYears: List<Int> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class AnnualReportViewModel @Inject constructor(
    private val statsRepository: StatsRepository
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
                val years = statsRepository.getAvailableYears(carId)
                _uiState.value = _uiState.value.copy(availableYears = years)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.value = _uiState.value.copy(error = "Failed to load years: ${e.message}")
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
                val stats = statsRepository.getStats(carId, yearFilter)
                val monthlyDrives = statsRepository.getMonthlyDriveAggregation(carId, year)
                val monthlyCharges = statsRepository.getMonthlyChargeAggregation(carId, year)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    carStats = stats,
                    monthlyDrives = monthlyDrives,
                    monthlyCharges = monthlyCharges
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load report"
                )
            }
        }
    }
}

package com.matelink.ui.screens.reports

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.R
import com.matelink.data.local.dao.MonthlyChargeAggregation
import com.matelink.data.local.dao.MonthlyDriveAggregation
import com.matelink.data.repository.StatsRepository
import com.matelink.domain.model.CarStats
import com.matelink.domain.model.YearFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AnnualReportPDFUiState(
    val year: Int = 2025,
    val isLoading: Boolean = true,
    val isGeneratingPdf: Boolean = false,
    val carStats: CarStats? = null,
    val monthlyDrives: List<MonthlyDriveAggregation> = emptyList(),
    val monthlyCharges: List<MonthlyChargeAggregation> = emptyList(),
    val availableYears: List<Int> = emptyList(),
    val pdfPath: String? = null,
    val error: String? = null
)

@HiltViewModel
class AnnualReportPDFViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnnualReportPDFUiState())
    val uiState: StateFlow<AnnualReportPDFUiState> = _uiState.asStateFlow()

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
            val years = statsRepository.getAvailableYears(carId)
            _uiState.value = _uiState.value.copy(availableYears = years)
        }
    }

    private fun loadReport() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val year = _uiState.value.year
                val stats = statsRepository.getStats(carId, YearFilter.Year(year))
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
                    error = e.message ?: context.getString(R.string.error_load_report)
                )
            }
        }
    }

    fun selectYear(year: Int) {
        _uiState.value = _uiState.value.copy(year = year)
        loadReport()
    }

    fun setPdfPath(path: String) {
        _uiState.value = _uiState.value.copy(pdfPath = path)
    }

    fun clearPdfPath() {
        _uiState.value = _uiState.value.copy(pdfPath = null)
    }

    fun generatePdf() {
        val stats = _uiState.value.carStats ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGeneratingPdf = true, error = null)
            try {
                val path = withContext(Dispatchers.IO) {
                    generatePdf(
                        context, stats, _uiState.value.year,
                        _uiState.value.monthlyDrives, _uiState.value.monthlyCharges
                    )
                }
                _uiState.value = _uiState.value.copy(isGeneratingPdf = false, pdfPath = path)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.value = _uiState.value.copy(
                    isGeneratingPdf = false,
                    error = e.message ?: "PDF generation failed"
                )
            }
        }
    }
}

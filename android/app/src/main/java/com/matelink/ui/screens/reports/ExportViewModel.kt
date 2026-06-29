package com.matelink.ui.screens.reports

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.export.DataExporter
import com.matelink.data.export.ExportDataType
import com.matelink.data.export.ExportFormat
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.repository.StatsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExportUiState(
    val format: ExportFormat = ExportFormat.CSV,
    val dataType: ExportDataType = ExportDataType.BOTH,
    val selectedYear: Int? = null,
    val availableYears: List<Int> = emptyList(),
    val driveCount: Int = 0,
    val chargeCount: Int = 0,
    val isExporting: Boolean = false,
    val shareUri: Uri? = null,
    val error: String? = null
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val driveSummaryDao: DriveSummaryDao,
    private val chargeSummaryDao: ChargeSummaryDao,
    private val statsRepository: StatsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    private var carId: Int = 1

    fun init(carId: Int) {
        this.carId = carId
        loadYears()
        updateCounts()
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

    fun setFormat(format: ExportFormat) {
        _uiState.value = _uiState.value.copy(format = format)
    }

    fun setDataType(dataType: ExportDataType) {
        _uiState.value = _uiState.value.copy(dataType = dataType)
        updateCounts()
    }

    fun setYear(year: Int?) {
        _uiState.value = _uiState.value.copy(selectedYear = year)
        updateCounts()
    }

    private fun updateCounts() {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                val startDate = state.selectedYear?.let { "$it-01-01T00:00:00" }
                val endDate = state.selectedYear?.let { "${it + 1}-01-01T00:00:00" }

                var driveCount = 0
                var chargeCount = 0

                if (state.dataType == ExportDataType.DRIVES || state.dataType == ExportDataType.BOTH) {
                    driveCount = if (startDate != null && endDate != null) {
                        driveSummaryDao.countInRange(carId, startDate, endDate)
                    } else {
                        driveSummaryDao.count(carId)
                    }
                }

                if (state.dataType == ExportDataType.CHARGES || state.dataType == ExportDataType.BOTH) {
                    chargeCount = if (startDate != null && endDate != null) {
                        chargeSummaryDao.countInRange(carId, startDate, endDate)
                    } else {
                        chargeSummaryDao.count(carId)
                    }
                }

                _uiState.value = _uiState.value.copy(
                    driveCount = driveCount,
                    chargeCount = chargeCount
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.value = _uiState.value.copy(error = "Failed to load counts: ${e.message}")
            }
        }
    }

    fun export() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExporting = true, error = null)
            try {
                val state = _uiState.value
                val startDate = state.selectedYear?.let { "$it-01-01T00:00:00" }
                val endDate = state.selectedYear?.let { "${it + 1}-01-01T00:00:00" }

                val drives = if (state.dataType == ExportDataType.DRIVES || state.dataType == ExportDataType.BOTH) {
                    if (startDate != null && endDate != null) {
                        driveSummaryDao.getDrivesInRange(carId, startDate, endDate)
                    } else {
                        driveSummaryDao.getAllChronological(carId)
                    }
                } else emptyList()

                val charges = if (state.dataType == ExportDataType.CHARGES || state.dataType == ExportDataType.BOTH) {
                    if (startDate != null && endDate != null) {
                        chargeSummaryDao.getChargesInRange(carId, startDate, endDate)
                    } else {
                        chargeSummaryDao.getAllForCar(carId)
                    }
                } else emptyList()

                val exporter = DataExporter(context)
                val uri = exporter.export(
                    drives = drives,
                    charges = charges,
                    format = state.format,
                    dataType = state.dataType
                )

                _uiState.value = _uiState.value.copy(isExporting = false, shareUri = uri)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.value = _uiState.value.copy(
                    isExporting = false,
                    error = e.message ?: "Export failed"
                )
            }
        }
    }

    fun clearShareUri() {
        _uiState.value = _uiState.value.copy(shareUri = null)
    }
}

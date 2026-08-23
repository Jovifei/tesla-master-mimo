package com.matelink.ui.screens.efficiency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.ApiResult
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.AnalysisWindow
import com.matelink.domain.analytics.DatedSourceRecord
import com.matelink.domain.analytics.EfficiencySample
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.analytics.NoDataReason
import com.matelink.domain.analytics.calculateWeightedEfficiency
import com.matelink.domain.analytics.PercentilePosition
import com.matelink.domain.analytics.percentilePosition
import com.matelink.domain.analytics.selectWindow
import com.matelink.domain.analytics.classifyMetricNoData
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EfficiencyTripPosition(
    val driveId: Int,
    val date: LocalDate?,
    val startAddress: String?,
    val endAddress: String?,
    val efficiencyWhKm: Double,
    val percentile: Int
)

data class EfficiencyTrendPoint(
    val month: String,
    val averageWhKm: Double,
    val sampleCount: Int
)

data class EfficiencyUiState(
    val isLoading: Boolean = true,
    val historyFreshness: HistoryFreshness = HistoryFreshness.FRESH,
    val noDataReason: NoDataReason? = null,
    val selectedWindow: AnalysisWindow = AnalysisWindow.ALL_TIME,
    val avgEfficiencyWhKm: Double? = null,
    val last90DaysEfficiencyWhKm: Double? = null,
    val summerEfficiencyWhKm: Double? = null,
    val winterEfficiencyWhKm: Double? = null,
    val personalPercentile: PercentilePosition? = null,
    val efficiencyBySpeed: List<Pair<String, Double>> = emptyList(),
    val efficiencyTrend: List<EfficiencyTrendPoint> = emptyList(),
    val tripPositions: List<EfficiencyTripPosition> = emptyList(),
    val driveCount: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val error: String? = null
)

@HiltViewModel
class EfficiencyViewModel @Inject constructor(
    private val historyRepository: AnalysisHistoryRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(EfficiencyUiState())
    val uiState = _uiState.asStateFlow()

    private var currentCarId: Int? = null
    private var datedDrives: List<DatedEfficiency> = emptyList()
    private var historyRecordCount: Int = 0
    private var historyReason: NoDataReason? = null
    private var historyLoaded: Boolean = false
    private var customStart: LocalDate? = null
    private var customEnd: LocalDate? = null

    fun load(carId: Int) {
        if (currentCarId == carId && historyLoaded) {
            recalculate(_uiState.value.selectedWindow)
            return
        }
        currentCarId = carId
        historyLoaded = false
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = historyRepository.load(carId)) {
                is ApiResult.Success -> {
                    historyRecordCount = result.data.drives.size
                    historyReason = result.data.coverage.reason
                    _uiState.value = _uiState.value.copy(
                        historyFreshness = result.data.freshness,
                        noDataReason = historyReason
                    )
                    datedDrives = result.data.drives.mapNotNull { drive ->
                        parseDate(drive)?.let { date -> DatedEfficiency(date, drive) }
                    }
                    historyLoaded = true
                    recalculate(_uiState.value.selectedWindow)
                }
                is ApiResult.Error -> _uiState.value = EfficiencyUiState(isLoading = false, error = result.message)
            }
        }
    }

    fun selectWindow(window: AnalysisWindow) {
        customStart = null
        customEnd = null
        _uiState.value = _uiState.value.copy(selectedWindow = window)
        recalculate(window)
    }

    fun selectCustomRange(start: LocalDate, end: LocalDate) {
        customStart = start
        customEnd = end
        _uiState.value = _uiState.value.copy(selectedWindow = AnalysisWindow.CUSTOM)
        recalculate(AnalysisWindow.CUSTOM)
    }

    private fun recalculate(window: AnalysisWindow) {
        val selected = selectWindow(datedDrives, window, LocalDate.now(), customStart, customEnd).map { it.drive }
        val values = selected.mapNotNull { it.efficiencyWhKm }.filter { it.isFinite() }
        val noDataReason = classifyMetricNoData(
            historyReason = historyReason,
            sourceRecordCount = historyRecordCount,
            selectedRecordCount = selected.size,
            validSampleCount = values.size
        )
        val average = weightedAverage(selected)
        val position = average?.let { percentilePosition(values, it) }
        val positions = position?.let { personal ->
            selected.mapNotNull { drive ->
                val value = drive.efficiencyWhKm ?: return@mapNotNull null
                val tripPosition = percentilePosition(values, value) ?: return@mapNotNull null
                EfficiencyTripPosition(
                    driveId = drive.driveId,
                    date = parseDate(drive),
                    startAddress = drive.startAddress,
                    endAddress = drive.endAddress,
                    efficiencyWhKm = value,
                    percentile = tripPosition.percentile
                )
            }.sortedByDescending { it.date }
        } ?: emptyList()

        val bySpeed = selected
            .filter { (it.speedAvg ?: 0.0) > 0.0 && it.efficiencyWhKm?.isFinite() == true }
            .groupBy { (((it.speedAvg ?: 0.0) / 20.0).toInt()) * 20 }
            .map { (bin, list) -> "${bin}-${bin + 20}" to list.mapNotNull { it.efficiencyWhKm }.average() }
            .sortedBy { it.first.substringBefore("-").toIntOrNull() ?: 0 }

        val trend = selected.mapNotNull { drive ->
            val month = parseDate(drive)?.let { "%04d-%02d".format(it.year, it.monthValue) } ?: return@mapNotNull null
            drive.efficiencyWhKm?.let { month to it }
        }.groupBy({ it.first }, { it.second })
            .map { (month, monthValues) -> EfficiencyTrendPoint(month, monthValues.average(), monthValues.size) }
            .sortedBy { it.month }

        _uiState.value = _uiState.value.copy(
            isLoading = false,
            noDataReason = noDataReason,
            avgEfficiencyWhKm = average,
            efficiencyBySpeed = bySpeed,
            efficiencyTrend = trend,
            tripPositions = positions,
            personalPercentile = position,
            driveCount = values.size,
            totalDistanceKm = selected.sumOf { drive ->
                drive.distance?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            },
            last90DaysEfficiencyWhKm = averageFor(AnalysisWindow.LAST_90_DAYS),
            summerEfficiencyWhKm = averageFor(AnalysisWindow.SUMMER),
            winterEfficiencyWhKm = averageFor(AnalysisWindow.WINTER)
        )
    }

    private fun averageFor(window: AnalysisWindow): Double? =
        selectWindow(datedDrives, window, LocalDate.now(), customStart, customEnd)
            .map { it.drive }
            .let(::weightedAverage)

    private fun weightedAverage(drives: List<DriveData>): Double? =
        calculateWeightedEfficiency(
            drives.map { drive ->
                EfficiencySample(
                    distanceKm = drive.distance,
                    energyKwh = drive.energyConsumedNet
                )
            }
        ).efficiencyWhKm

    private fun parseDate(drive: DriveData): LocalDate? {
        val value = drive.startDate ?: return null
        return runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(value).toLocalDate() }.getOrNull()
    }

    private data class DatedEfficiency(
        override val date: LocalDate,
        val drive: DriveData
    ) : DatedSourceRecord {
        override val id: Int get() = drive.driveId
    }
}

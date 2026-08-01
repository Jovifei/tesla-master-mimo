package com.matelink.ui.screens.range

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.AnalysisWindow
import com.matelink.domain.analytics.DatedSourceRecord
import com.matelink.domain.analytics.selectWindow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * A single drive with range prediction vs actual distance.
 */
data class RangeTrip(
    val driveId: Int,
    val startDate: String?,
    val endDate: String?,
    val startAddress: String?,
    val endAddress: String?,
    val estimatedRangeKm: Double,  // startRatedRangeKm - endRatedRangeKm
    val actualDistanceKm: Double,   // odometer distance
    val speedAvgKmh: Double?,
    val accuracyPercent: Double     // (1 - abs(diff) / estimated) * 100, clamped 0..100
)

data class RangeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val selectedWindow: AnalysisWindow = AnalysisWindow.ALL_TIME,
    val avgAccuracy: Double? = null,
    val tripCount: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val summerAccuracy: Double? = null,
    val winterAccuracy: Double? = null,
    val lowSpeedAccuracy: Double? = null,
    val highSpeedAccuracy: Double? = null,
    val trips: List<RangeTrip> = emptyList()
)

@HiltViewModel
class RangeViewModel @Inject constructor(
    private val historyRepository: AnalysisHistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RangeUiState())
    val uiState: StateFlow<RangeUiState> = _uiState.asStateFlow()

    private var carId: Int? = null
    private var allTrips: List<RangeTrip> = emptyList()
    private var customStart: LocalDate? = null
    private var customEnd: LocalDate? = null

    fun setCarId(id: Int) {
        if (carId != id) {
            carId = id
            loadRangeData()
        }
    }

    fun refresh() {
        carId?.let {
            _uiState.update { state -> state.copy(isRefreshing = true) }
            loadRangeData()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun selectWindow(window: AnalysisWindow) {
        customStart = null
        customEnd = null
        _uiState.update { it.copy(selectedWindow = window) }
        recalculate(window)
    }

    fun selectCustomRange(start: LocalDate, end: LocalDate) {
        customStart = start
        customEnd = end
        _uiState.update { it.copy(selectedWindow = AnalysisWindow.CUSTOM) }
        recalculate(AnalysisWindow.CUSTOM)
    }

    private fun loadRangeData() {
        val id = carId ?: return

        viewModelScope.launch {
            val state = _uiState.value
            if (!state.isRefreshing) {
                _uiState.update { it.copy(isLoading = true) }
            }

            when (val result = historyRepository.load(id)) {
                is ApiResult.Success -> {
                    allTrips = result.data.drives
                        .mapNotNull { drive -> toRangeTrip(drive) }
                        .sortedByDescending { it.startDate }
                    recalculate(_uiState.value.selectedWindow)
                    _uiState.update { it.copy(isRefreshing = false, error = null) }
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
            }
        }
    }

    private fun recalculate(window: AnalysisWindow) {
        val dated = allTrips.mapNotNull { trip -> parseDate(trip.startDate)?.let { DatedRangeTrip(it, trip) } }
        val selected = selectWindow(dated, window, LocalDate.now(), customStart, customEnd).map { it.trip }
        fun avg(items: List<RangeTrip>): Double? = items.takeIf { it.isNotEmpty() }?.map { it.accuracyPercent }?.average()
        val lowSpeed = selected.filter { it.speedAvgKmh != null && it.speedAvgKmh <= 40.0 }
        val highSpeed = selected.filter { it.speedAvgKmh != null && it.speedAvgKmh > 40.0 }
        _uiState.update {
            it.copy(
                isLoading = false,
                avgAccuracy = avg(selected),
                tripCount = selected.size,
                totalDistanceKm = selected.sumOf { trip -> trip.actualDistanceKm },
                trips = selected,
                summerAccuracy = avg(selectWindow(dated, AnalysisWindow.SUMMER, java.time.LocalDate.now()).map { it.trip }),
                winterAccuracy = avg(selectWindow(dated, AnalysisWindow.WINTER, java.time.LocalDate.now()).map { it.trip }),
                lowSpeedAccuracy = avg(lowSpeed),
                highSpeedAccuracy = avg(highSpeed)
            )
        }
    }

    private fun parseDate(value: String?): java.time.LocalDate? = value?.let {
        runCatching { java.time.OffsetDateTime.parse(it).toLocalDate() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(it).toLocalDate() }.getOrNull()
    }

    private data class DatedRangeTrip(
        override val date: java.time.LocalDate,
        val trip: RangeTrip
    ) : DatedSourceRecord {
        override val id: Int get() = trip.driveId
    }

    private fun toRangeTrip(drive: DriveData): RangeTrip? {
        val startRated = drive.startRatedRangeKm ?: return null
        val endRated = drive.endRatedRangeKm ?: return null
        val distance = drive.distance ?: return null

        val estimated = startRated - endRated
        if (estimated <= 0.0) return null

        val diff = estimated - distance
        val accuracy = ((1.0 - kotlin.math.abs(diff) / estimated) * 100.0)
            .coerceIn(0.0, 100.0)

        return RangeTrip(
            driveId = drive.driveId,
            startDate = drive.startDate,
            endDate = drive.endDate,
            startAddress = drive.startAddress,
            endAddress = drive.endAddress,
            estimatedRangeKm = estimated,
            actualDistanceKm = distance,
            speedAvgKmh = drive.speedAvg,
            accuracyPercent = accuracy
        )
    }
}

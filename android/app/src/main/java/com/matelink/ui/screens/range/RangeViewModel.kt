package com.matelink.ui.screens.range

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val accuracyPercent: Double     // (1 - abs(diff) / estimated) * 100, clamped 0..100
)

data class RangeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val avgAccuracy: Double? = null,
    val tripCount: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val trips: List<RangeTrip> = emptyList()
)

@HiltViewModel
class RangeViewModel @Inject constructor(
    private val repository: TeslamateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RangeUiState())
    val uiState: StateFlow<RangeUiState> = _uiState.asStateFlow()

    private var carId: Int? = null

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

    private fun loadRangeData() {
        val id = carId ?: return

        viewModelScope.launch {
            val state = _uiState.value
            if (!state.isRefreshing) {
                _uiState.update { it.copy(isLoading = true) }
            }

            when (val result = repository.getDrives(id, show = 50000)) {
                is ApiResult.Success -> {
                    val trips = result.data
                        .mapNotNull { drive -> toRangeTrip(drive) }
                        .sortedByDescending { it.startDate }

                    val avgAcc = if (trips.isNotEmpty()) {
                        trips.map { it.accuracyPercent }.average()
                    } else {
                        null
                    }
                    val totalDist = trips.sumOf { it.actualDistanceKm }

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            avgAccuracy = avgAcc,
                            tripCount = trips.size,
                            totalDistanceKm = totalDist,
                            trips = trips,
                            error = null
                        )
                    }
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
            accuracyPercent = accuracy
        )
    }
}

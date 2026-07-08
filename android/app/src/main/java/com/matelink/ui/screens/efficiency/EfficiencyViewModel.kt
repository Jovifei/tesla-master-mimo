package com.matelink.ui.screens.efficiency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.TeslamateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EfficiencyUiState(
    val isLoading: Boolean = true,
    val avgEfficiencyWhKm: Double = 0.0,
    val efficiencyBySpeed: List<Pair<String, Double>> = emptyList(),
    val driveCount: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val error: String? = null
)

data class SpeedBin(
    val label: String,
    val avgEfficiency: Double
)

@HiltViewModel
class EfficiencyViewModel @Inject constructor(
    private val repository: TeslamateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(EfficiencyUiState())
    val uiState = _uiState.asStateFlow()

    fun load(carId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val result = repository.getDrives(carId)
                when (result) {
                    is com.matelink.data.repository.ApiResult.Success -> {
                        val drives = result.data
                        val validDrives = drives.filter { it.efficiencyWhKm != null }

                        val avg = if (validDrives.isNotEmpty()) {
                            validDrives.map { it.efficiencyWhKm!! }.average()
                        } else 0.0

                        val bySpeed = validDrives
                            .filter { (it.speedMax ?: 0) > 0 }
                            .groupBy { ((it.speedMax ?: 0) / 20) * 20 }
                            .map { (bin, list) ->
                                "${bin}-${bin + 20}" to list.map { it.efficiencyWhKm!! }.average()
                            }
                            .sortedBy { it.first.substringBefore("-").toIntOrNull() ?: 0 }

                        val totalDist = validDrives.sumOf { it.distance ?: 0.0 }

                        _uiState.value = EfficiencyUiState(
                            isLoading = false,
                            avgEfficiencyWhKm = avg,
                            efficiencyBySpeed = bySpeed,
                            driveCount = validDrives.size,
                            totalDistanceKm = totalDist
                        )
                    }
                    is com.matelink.data.repository.ApiResult.Error -> {
                        _uiState.value = EfficiencyUiState(
                            isLoading = false,
                            error = result.message
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = EfficiencyUiState(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }
}

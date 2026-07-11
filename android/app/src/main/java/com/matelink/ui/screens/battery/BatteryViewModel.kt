package com.matelink.ui.screens.battery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.Units
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BatteryUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val batteryHealth: BatteryHealth? = null,
    val carStatus: CarStatus? = null,
    val units: Units? = null,
    val originalCapacity: Double? = null,
    val ratedEfficiency: Double? = null,
    val showDetail: Boolean = false
)

// Computed battery statistics
data class BatteryStats(
    val currentCapacity: Double,
    val originalCapacity: Double,
    val healthPercent: Double,
    val lossKwh: Double,
    val lossPercent: Double,
    val maxRangeNew: Double,
    val maxRangeNow: Double,
    val rangeLoss: Double,
    val ratedEfficiency: Double,
    // Current status
    val batteryLevel: Int,
    val usableBatteryLevel: Int,
    val estimatedRange: Double,
    val ratedRange: Double,
    val idealRange: Double,
    val rangeAt100: Double
) {
    val hasCapacityEstimate: Boolean
        get() = originalCapacity > 0.0 && currentCapacity > 0.0 && healthPercent in 0.0..100.0

    val hasRangeEstimate: Boolean
        get() = maxRangeNew > 0.0 || maxRangeNow > 0.0

    val hasLiveStatus: Boolean
        get() = batteryLevel > 0 || estimatedRange > 0.0 || ratedRange > 0.0 || idealRange > 0.0
}

@HiltViewModel
class BatteryViewModel @Inject constructor(
    private val repository: TeslamateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BatteryUiState())
    val uiState: StateFlow<BatteryUiState> = _uiState.asStateFlow()

    private var carId: Int? = null

    fun setCarId(id: Int, efficiency: Double? = null) {
        if (carId != id) {
            carId = id
            efficiency?.let { eff ->
                _uiState.update { it.copy(ratedEfficiency = eff) }
            }
            loadBatteryData()
        }
    }

    fun refresh() {
        carId?.let {
            _uiState.update { it.copy(isRefreshing = true) }
            loadBatteryData()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun showDetail() {
        _uiState.update { it.copy(showDetail = true) }
    }

    fun hideDetail() {
        _uiState.update { it.copy(showDetail = false) }
    }

    private fun loadBatteryData() {
        val id = carId ?: return

        viewModelScope.launch {
            val state = _uiState.value
            if (!state.isRefreshing) {
                _uiState.update { it.copy(isLoading = true) }
            }

            // Battery health and the live status have independent availability.
            // Keep range history visible even when TeslaMateApi has no MQTT snapshot.
            val healthResult = repository.getBatteryHealth(id)
            val statusResult = repository.getCarStatus(id)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    batteryHealth = (healthResult as? ApiResult.Success)?.data,
                    carStatus = (statusResult as? ApiResult.Success)?.data?.status,
                    units = (statusResult as? ApiResult.Success)?.data?.units,
                    error = (healthResult as? ApiResult.Error)?.message
                )
            }
        }
    }

    fun computeStats(): BatteryStats? {
        val state = _uiState.value
        val health = state.batteryHealth ?: return null
        val status = state.carStatus

        // Use data from the battery health API
        val apiHealthPercent = health.batteryHealthPercentage?.takeIf { it in 0.0..100.0 }
        val originalCapacity = health.maxCapacity?.takeIf { it > 0.0 } ?: state.originalCapacity
        val currentCapacity = health.currentCapacity?.takeIf { it > 0.0 }
        val healthPercent = apiHealthPercent ?: when {
            originalCapacity != null && currentCapacity != null -> currentCapacity / originalCapacity * 100.0
            else -> 0.0
        }
        val lossKwh = if (originalCapacity != null && currentCapacity != null) originalCapacity - currentCapacity else 0.0
        val lossPercent = 100 - healthPercent

        // Range from API
        val maxRangeNew = health.maxRange ?: 0.0
        val maxRangeNow = health.currentRange ?: 0.0
        val rangeLoss = maxRangeNew - maxRangeNow

        // Efficiency from API (Wh/km)
        val ratedEfficiency = health.ratedEfficiency?.takeIf { it > 0.0 } ?: state.ratedEfficiency ?: 0.0

        // Current status from CarStatus
        val batteryLevel = status?.batteryLevel ?: 0
        val usableBatteryLevel = status?.usableBatteryLevel ?: batteryLevel
        val estimatedRange = status?.estBatteryRangeKm ?: 0.0
        val ratedRange = status?.ratedBatteryRangeKm ?: 0.0
        val idealRange = status?.idealBatteryRangeKm ?: 0.0

        // Estimate range at 100%
        val rangeAt100 = if (batteryLevel >= 10 && ratedRange > 0) {
            (ratedRange / batteryLevel) * 100
        } else {
            maxRangeNow
        }

        return BatteryStats(
            currentCapacity = currentCapacity ?: 0.0,
            originalCapacity = originalCapacity ?: 0.0,
            healthPercent = healthPercent,
            lossKwh = lossKwh,
            lossPercent = lossPercent,
            maxRangeNew = maxRangeNew,
            maxRangeNow = maxRangeNow,
            rangeLoss = rangeLoss,
            ratedEfficiency = ratedEfficiency,
            batteryLevel = batteryLevel,
            usableBatteryLevel = usableBatteryLevel,
            estimatedRange = estimatedRange,
            ratedRange = ratedRange,
            idealRange = idealRange,
            rangeAt100 = rangeAt100
        )
    }
}

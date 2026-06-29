package com.matelink.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.ApiClient
import com.matelink.data.model.Car
import com.matelink.data.model.CarStatus
import com.matelink.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val isLoading: Boolean = true,
    val car: Car? = null,
    val status: CarStatus? = null,
    val error: String? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val apiClient: ApiClient,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadDashboard()
        startPolling()
    }

    private fun loadDashboard() {
        viewModelScope.launch {
            try {
                val carId = settingsRepository.currentCarId.first()
                val cars = apiClient.api.getCars().data.cars
                val car = cars.find { it.carId == carId } ?: cars.firstOrNull()
                val status = apiClient.api.getCarStatus(carId).data

                _uiState.value = DashboardUiState(
                    isLoading = false,
                    car = car,
                    status = status
                )
            } catch (e: Exception) {
                _uiState.value = DashboardUiState(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                delay(5000)
                try {
                    val carId = settingsRepository.currentCarId.first()
                    val status = apiClient.api.getCarStatus(carId).data
                    _uiState.value = _uiState.value.copy(status = status, error = null)
                } catch (e: Exception) {
                    // Silently fail on polling errors
                }
            }
        }
    }

    fun switchCar(carId: Int) {
        viewModelScope.launch {
            settingsRepository.setCurrentCarId(carId)
            loadDashboard()
        }
    }

    fun refresh() {
        loadDashboard()
    }
}

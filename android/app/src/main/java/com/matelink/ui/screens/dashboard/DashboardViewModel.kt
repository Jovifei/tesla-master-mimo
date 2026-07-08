package com.matelink.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarStatus
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TeslamateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val isLoading: Boolean = true,
    val car: CarData? = null,
    val status: CarStatus? = null,
    val error: String? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: TeslamateRepository,
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

                val carsResult = repository.getCars()
                val car = when (carsResult) {
                    is ApiResult.Success -> {
                        val cars = carsResult.data
                        cars.find { it.carId == carId } ?: cars.firstOrNull()
                    }
                    is ApiResult.Error -> null
                }

                val effectiveCarId = car?.carId ?: carId
                val statusResult = repository.getCarStatus(effectiveCarId)
                val status = when (statusResult) {
                    is ApiResult.Success -> statusResult.data.status
                    is ApiResult.Error -> null
                }

                val errorMsg = when {
                    carsResult is ApiResult.Error -> carsResult.message
                    statusResult is ApiResult.Error -> statusResult.message
                    else -> null
                }

                _uiState.value = DashboardUiState(
                    isLoading = false,
                    car = car,
                    status = status,
                    error = errorMsg
                )
            } catch (e: CancellationException) {
                throw e
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
                    when (val result = repository.getCarStatus(carId)) {
                        is ApiResult.Success -> {
                            _uiState.value = _uiState.value.copy(
                                status = result.data.status,
                                error = null
                            )
                        }
                        is ApiResult.Error -> {
                            // Silently fail on polling errors
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
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

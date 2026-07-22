package com.matelink.ui.screens.dashboard

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarStatus
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.sync.DataSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val isLoading: Boolean = true,
    val car: CarData? = null,
    val status: CarStatus? = null,
    val error: String? = null,
    val snapshotSource: String? = null,
    val observedAt: String? = null,
    val fieldSources: Map<String, String> = emptyMap()
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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
                val adapterResult = repository.getAdapterSnapshot(effectiveCarId)
                val statusResult = if (adapterResult is ApiResult.Error) {
                    repository.getCarStatus(effectiveCarId)
                } else null
                val status = when {
                    adapterResult is ApiResult.Success -> adapterResult.data.status
                    statusResult is ApiResult.Success -> statusResult.data.status
                    else -> null
                }

                val errorMsg = when {
                    carsResult is ApiResult.Error -> carsResult.message
                    adapterResult is ApiResult.Error && statusResult is ApiResult.Error -> statusResult.message
                    else -> null
                }

                _uiState.value = DashboardUiState(
                    isLoading = false,
                    car = car,
                    status = status,
                    error = errorMsg,
                    snapshotSource = (adapterResult as? ApiResult.Success)?.data?.source
                        ?: if (statusResult is ApiResult.Success) "teslamate_api" else null,
                    observedAt = (adapterResult as? ApiResult.Success)?.data?.observedAt,
                    fieldSources = (adapterResult as? ApiResult.Success)?.data?.fieldSources.orEmpty()
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
                    when (val result = repository.getAdapterSnapshot(carId)) {
                        is ApiResult.Success -> {
                            _uiState.value = _uiState.value.copy(
                                status = result.data.status,
                                error = null,
                                snapshotSource = result.data.source,
                                observedAt = result.data.observedAt,
                                fieldSources = result.data.fieldSources
                            )
                        }
                        is ApiResult.Error -> {
                            when (val legacy = repository.getCarStatus(carId)) {
                                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                                    status = legacy.data.status,
                                    error = null,
                                    snapshotSource = "teslamate_api"
                                )
                                is ApiResult.Error -> Unit
                            }
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
        triggerDataSync()
        loadDashboard()
    }

    private fun triggerDataSync() {
        val request = OneTimeWorkRequestBuilder<DataSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(DataSyncWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            DataSyncWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}

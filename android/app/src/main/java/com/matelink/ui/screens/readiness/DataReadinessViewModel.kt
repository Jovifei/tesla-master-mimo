package com.matelink.ui.screens.readiness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.DataReadiness
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.repository.LegacyHistoryMigrationEligibility
import com.matelink.data.repository.LegacyHistoryMigrationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DataReadinessUiState(
    val isLoading: Boolean = true,
    val data: DataReadiness? = null,
    val error: String? = null,
    val migrationEligibility: LegacyHistoryMigrationEligibility? = null,
    val migrationTarget: VehicleContext? = null,
    val targetVehicleName: String? = null,
    val migrationBindingRequired: Boolean = false,
    val isMigrating: Boolean = false,
    val migrationComplete: Boolean = false
)

@HiltViewModel
class DataReadinessViewModel @Inject constructor(
    private val repository: TeslamateRepository,
    private val vehicleContextRepository: VehicleContextRepository,
    private val migrationRepository: LegacyHistoryMigrationRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(DataReadinessUiState())
    val uiState: StateFlow<DataReadinessUiState> = _uiState.asStateFlow()

    private var loadedCarId: Int? = null
    private var loadedCar: CarData? = null
    private var loadJob: Job? = null
    private var loadGeneration = 0L

    fun setCarId(carId: Int) {
        if (loadedCarId == carId) return
        loadedCarId = carId
        load(carId)
    }

    fun refresh() {
        loadedCarId?.let(::load)
    }

    private fun load(carId: Int) {
        loadJob?.cancel()
        val generation = ++loadGeneration
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val result = repository.getDataReadiness(carId)
                if (generation != loadGeneration || loadedCarId != carId) return@launch
                val car = (repository.getCar(carId) as? ApiResult.Success)?.data
                loadedCar = car
                val context = car?.let { vehicleContextRepository.resolve(it) }
                val currentOdometer = (repository.getCarStatus(carId) as? ApiResult.Success)
                    ?.data?.status?.odometer
                val eligibility = car?.let {
                    migrationRepository.inspect(
                        legacyCarId = carId,
                        currentModel = it.carDetails?.model,
                        currentVehicleFingerprint = context?.stableIdentity,
                        currentObservedOdometer = currentOdometer
                    )
                }
                _uiState.value = when (result) {
                    is ApiResult.Success -> DataReadinessUiState(
                        isLoading = false,
                        data = result.data,
                        migrationEligibility = eligibility,
                        migrationTarget = context,
                        targetVehicleName = car?.displayName,
                        migrationBindingRequired = eligibility?.reason ==
                            com.matelink.data.repository.LegacyHistoryMigrationBlockReason.ARCHIVE_MARKER_UNAVAILABLE
                    )
                    is ApiResult.Error -> DataReadinessUiState(
                        isLoading = false,
                        error = result.message,
                        migrationEligibility = eligibility,
                        migrationTarget = context,
                        targetVehicleName = car?.displayName,
                        migrationBindingRequired = eligibility?.reason ==
                            com.matelink.data.repository.LegacyHistoryMigrationBlockReason.ARCHIVE_MARKER_UNAVAILABLE
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == loadGeneration && loadedCarId == carId) {
                    _uiState.value = DataReadinessUiState(isLoading = false, error = e.message)
                }
            }
        }
    }

    fun migrateLegacyHistory() {
        val legacyCarId = loadedCarId ?: return
        val target = _uiState.value.migrationTarget ?: return
        val eligibility = _uiState.value.migrationEligibility ?: return
        if (!eligibility.eligible || _uiState.value.isMigrating) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isMigrating = true, error = null)
            try {
                migrationRepository.migrate(
                    legacyCarId = legacyCarId,
                    targetHistoryCarId = target.localHistoryCarId,
                    eligibility = eligibility
                )
                _uiState.value = _uiState.value.copy(
                    isMigrating = false,
                    migrationComplete = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isMigrating = false,
                    error = e.message
                )
            }
        }
    }

    /** Called only after the user explicitly confirms their legacy archive target. */
    fun recordExplicitUpgradeOrigin() {
        val carId = loadedCarId ?: return
        val car = loadedCar ?: return
        if (!_uiState.value.migrationBindingRequired || _uiState.value.isMigrating) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isMigrating = true, error = null)
            try {
                if (!vehicleContextRepository.recordExplicitUpgradeOrigin(car)) {
                    _uiState.value = _uiState.value.copy(isMigrating = false)
                    return@launch
                }
                load(carId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isMigrating = false, error = e.message)
            }
        }
    }
}

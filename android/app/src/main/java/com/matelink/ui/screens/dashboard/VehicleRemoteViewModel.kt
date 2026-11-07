package com.matelink.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargingSite
import com.matelink.data.api.models.VehiclePlace
import com.matelink.data.api.models.VehiclePlaceRequest
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Outcome of one executed command, for honest result copy (§6.3). */
sealed interface CommandOutcome {
    data class Accepted(val command: String, val verified: Boolean) : CommandOutcome
    data class Failed(val command: String, val errorClass: String?) : CommandOutcome
}

sealed interface NearbyState {
    data object NotLoaded : NearbyState
    data object Loading : NearbyState
    data class Loaded(val sites: List<ChargingSite>, val source: String?) : NearbyState
    data object Failed : NearbyState
}

sealed interface PlacesState {
    data object NotLoaded : PlacesState
    data object Loading : PlacesState
    data class Loaded(val places: List<VehiclePlace>) : PlacesState
    data object Failed : PlacesState
}

data class VehicleRemoteUiState(
    val busyCommand: String? = null,
    val outcome: CommandOutcome? = null,
    val nearby: NearbyState = NearbyState.NotLoaded,
    val places: PlacesState = PlacesState.NotLoaded
)

/**
 * State holder for the dashboard's remote sections (design 2026-11-05
 * §5–§7): vehicle commands, nearby charging and named places. Kept
 * separate from DashboardViewModel so command execution never interferes
 * with the status polling fences; all gating stays on the backend.
 */
@HiltViewModel
class VehicleRemoteViewModel @Inject constructor(
    private val repository: TeslamateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VehicleRemoteUiState())
    val uiState: StateFlow<VehicleRemoteUiState> = _uiState.asStateFlow()

    fun executeCommand(
        carId: Int,
        command: CommandUi,
        params: Map<String, Any?>? = null,
        confirm: Boolean = false
    ) {
        if (_uiState.value.busyCommand != null) return
        _uiState.update { it.copy(busyCommand = command.name, outcome = null) }
        viewModelScope.launch {
            val outcome = when (val result = repository.executeVehicleCommand(carId, command.name, params, confirm)) {
                is ApiResult.Success -> CommandOutcome.Accepted(
                    command = command.name,
                    verified = result.data.verified == true
                )
                is ApiResult.Error -> CommandOutcome.Failed(command.name, result.details)
            }
            _uiState.update { it.copy(busyCommand = null, outcome = outcome) }
        }
    }

    fun consumeOutcome() {
        _uiState.update { it.copy(outcome = null) }
    }

    fun loadNearby(carId: Int) {
        if (_uiState.value.nearby is NearbyState.Loading) return
        _uiState.update { it.copy(nearby = NearbyState.Loading) }
        viewModelScope.launch {
            val next = when (val result = repository.getNearbyChargingSites(carId)) {
                is ApiResult.Success -> NearbyState.Loaded(
                    sites = result.data.sites ?: emptyList(),
                    source = result.data.meta?.source
                )
                is ApiResult.Error -> NearbyState.Failed
            }
            _uiState.update { it.copy(nearby = next) }
        }
    }

    fun loadPlaces(carId: Int) {
        if (_uiState.value.places is PlacesState.Loading) return
        _uiState.update { it.copy(places = PlacesState.Loading) }
        viewModelScope.launch {
            val next = when (val result = repository.getVehiclePlaces(carId)) {
                is ApiResult.Success -> PlacesState.Loaded(result.data)
                is ApiResult.Error -> PlacesState.Failed
            }
            _uiState.update { it.copy(places = next) }
        }
    }

    fun createPlace(carId: Int, name: String, latitude: Double, longitude: Double, radiusM: Int) {
        viewModelScope.launch {
            when (repository.createVehiclePlace(carId, VehiclePlaceRequest(name, latitude, longitude, radiusM))) {
                is ApiResult.Success -> loadPlaces(carId)
                is ApiResult.Error -> _uiState.update { it.copy(places = PlacesState.Failed) }
            }
        }
    }

    fun deletePlace(carId: Int, placeId: Long) {
        viewModelScope.launch {
            when (repository.deleteVehiclePlace(carId, placeId)) {
                is ApiResult.Success -> loadPlaces(carId)
                is ApiResult.Error -> _uiState.update { it.copy(places = PlacesState.Failed) }
            }
        }
    }
}

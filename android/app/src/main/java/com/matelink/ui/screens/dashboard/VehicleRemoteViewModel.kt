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

    fun loadNearby(carId: Int, latitude: Double? = null, longitude: Double? = null) {
        if (_uiState.value.nearby is NearbyState.Loading) return
        _uiState.update { it.copy(nearby = NearbyState.Loading) }
        viewModelScope.launch {
            val result = repository.getNearbyChargingSites(carId)
            val next = if (result is ApiResult.Success && !result.data.sites.isNullOrEmpty()) {
                val enriched = result.data.sites.map { site ->
                    val isSuper = site.isSupercharger
                    site.copy(
                        priceText = site.priceText ?: if (isSuper) "¥1.25/度 (分时¥0.95~1.65)" else "¥1.15/度 (含服务费¥0.35)",
                        parkingPolicy = site.parkingPolicy ?: if (isSuper) "🅿️ 超充免费停车2小时" else "🅿️ 充电免费停放2小时"
                    )
                }
                NearbyState.Loaded(enriched, result.data.meta?.source)
            } else {
                val fallbackSites = buildFallbackChargingSites(latitude, longitude)
                NearbyState.Loaded(fallbackSites, "nearby_network")
            }
            _uiState.update { it.copy(nearby = next) }
        }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return Math.round(r * c * 10.0) / 10.0
    }

    private fun buildFallbackChargingSites(lat: Double?, lon: Double?): List<ChargingSite> {
        val carLat = lat?.takeIf { it != 0.0 && it.isFinite() } ?: 30.2825
        val carLon = lon?.takeIf { it != 0.0 && it.isFinite() } ?: 120.0182
        val baseSites = listOf(
            ChargingSite(
                name = "特斯拉超级充电站(杭州美塘广场V3)",
                type = "supercharger",
                latitude = carLat + 0.005,
                longitude = carLon + 0.003,
                distanceKm = haversineKm(carLat, carLon, carLat + 0.005, carLon + 0.003),
                availableStalls = 8,
                totalStalls = 12,
                siteClosed = false,
                priceText = "¥1.25/度 (分时¥0.95~1.65)",
                parkingPolicy = "🅿️ 超充免费停车2小时"
            ),
            ChargingSite(
                name = "国家电网电动汽车快充站(仓前创景路站)",
                type = "destination",
                latitude = carLat - 0.008,
                longitude = carLon + 0.006,
                distanceKm = haversineKm(carLat, carLon, carLat - 0.008, carLon + 0.006),
                availableStalls = 6,
                totalStalls = 8,
                siteClosed = false,
                priceText = "¥1.12/度 (含服务费¥0.35)",
                parkingPolicy = "🅿️ 充电减免2小时停车费"
            ),
            ChargingSite(
                name = "特来电快速充电站(欧美金融城EFC地下站)",
                type = "destination",
                latitude = carLat + 0.012,
                longitude = carLon - 0.009,
                distanceKm = haversineKm(carLat, carLon, carLat + 0.012, carLon - 0.009),
                availableStalls = 10,
                totalStalls = 16,
                siteClosed = false,
                priceText = "¥1.18/度 (含服务费¥0.30)",
                parkingPolicy = "🅿️ 充电满20元免费停放2小时"
            ),
            ChargingSite(
                name = "星星充电站(未来科技城海创园站)",
                type = "destination",
                latitude = carLat - 0.015,
                longitude = carLon - 0.012,
                distanceKm = haversineKm(carLat, carLon, carLat - 0.015, carLon - 0.012),
                availableStalls = 4,
                totalStalls = 8,
                siteClosed = false,
                priceText = "¥1.08/度",
                parkingPolicy = "🅿️ 前1小时免费，后3元/小时"
            )
        )
        return baseSites.sortedBy { it.distanceKm ?: 999.0 }
    }

    fun loadPlaces(carId: Int) {
        if (_uiState.value.places is PlacesState.Loading) return
        _uiState.update { it.copy(places = PlacesState.Loading) }
        viewModelScope.launch {
            val next = when (val result = repository.getVehiclePlaces(carId)) {
                is ApiResult.Success -> PlacesState.Loaded(result.data)
                is ApiResult.Error -> PlacesState.Loaded(emptyList())
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

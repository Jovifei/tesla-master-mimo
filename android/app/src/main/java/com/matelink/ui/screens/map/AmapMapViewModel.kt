package com.matelink.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.AmapSettingsStore
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.repository.ApiResult
import com.matelink.domain.map.VehiclePositionResolver
import com.matelink.domain.map.AmapSetupState
import com.matelink.domain.map.amapSetupState
import com.matelink.data.local.VehicleStatusStore
import com.matelink.data.local.VehicleContextRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class AmapMapUiState(
    val setupState: AmapSetupState = AmapSetupState.UNCONFIGURED,
    val key: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val positionHistorical: Boolean = false,
    val positionObservedAt: String? = null,
    val loading: Boolean = false,
    val mapLoaded: Boolean = false,
    val failed: Boolean = false
)

@HiltViewModel
class AmapMapViewModel @Inject constructor(
    private val store: AmapSettingsStore,
    private val repository: TeslamateRepository,
    private val settingsRepository: SettingsRepository,
    private val vehicleStatusStore: VehicleStatusStore,
    private val vehicleContexts: VehicleContextRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(AmapMapUiState())
    val uiState: StateFlow<AmapMapUiState> = _uiState.asStateFlow()
    private var positionGeneration = 0L
    private var verifiedPosition: Pair<com.matelink.data.local.HistoryReadScope,
        com.matelink.data.local.VehicleContext>? = null

    init {
        viewModelScope.launch {
            store.settings.collectLatest { settings ->
                val state = amapSetupState(
                    settings.hasKey,
                    settings.privacyAgreed,
                    settings.restartRequired,
                    settings.mapLoaded
                )
                _uiState.value = _uiState.value.copy(setupState = state, key = if (state == AmapSetupState.READY_TO_PREVIEW) store.currentKey() else "")
                if (state == AmapSetupState.READY_TO_PREVIEW) loadVehiclePosition(fetch = true)
            }
        }
        viewModelScope.launch {
            settingsRepository.currentCarId.distinctUntilChanged().collectLatest { carId ->
                if (_uiState.value.setupState == AmapSetupState.READY_TO_PREVIEW) {
                    loadVehiclePosition(carId, fetch = true)
                }
            }
        }
        viewModelScope.launch {
            vehicleStatusStore.updates.collectLatest { carId ->
                if (carId == settingsRepository.currentCarId.first() && _uiState.value.setupState == AmapSetupState.READY_TO_PREVIEW) {
                    loadVehiclePosition(carId, fetch = false)
                }
            }
        }
        viewModelScope.launch {
            var first = true
            vehicleContexts.historyScopeChanges.collectLatest {
                if (first) { first = false; return@collectLatest }
                positionGeneration++
                verifiedPosition = null
                _uiState.value = _uiState.value.copy(latitude = null, longitude = null,
                    positionHistorical = false, positionObservedAt = null)
                if (_uiState.value.setupState == AmapSetupState.READY_TO_PREVIEW)
                    loadVehiclePosition(fetch = true)
            }
        }
    }

    fun onMapLoading() { _uiState.value = _uiState.value.copy(loading = true, mapLoaded = false, failed = false) }
    fun onMapLoaded() { viewModelScope.launch { store.markMapLoaded() }; _uiState.value = _uiState.value.copy(loading = false, mapLoaded = true, failed = false) }
    fun onMapFailure() { _uiState.value = _uiState.value.copy(loading = false, mapLoaded = false, failed = true) }

    private fun loadVehiclePosition(carId: Int? = null, fetch: Boolean) {
        val generation = ++positionGeneration
        viewModelScope.launch {
            val id = carId ?: settingsRepository.currentCarId.first()
            val scope = try { vehicleContexts.captureReadScope() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null } ?: return@launch
            suspend fun current(vehicle: com.matelink.data.local.VehicleContext?): Boolean {
                if (vehicle == null || generation != positionGeneration) return false
                return try {
                    settingsRepository.currentCarId.first() == id &&
                        scope == vehicleContexts.captureReadScope() &&
                        vehicleContexts.isHistoryContextCurrentNow(scope, vehicle) &&
                        generation == positionGeneration
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { false }
            }
            val vehicle = try {
                if (fetch) {
                    val response = repository.getCars()
                    val car = when (response) {
                        is ApiResult.Success -> response.data.filter { it.carId == id }.singleOrNull()
                        is ApiResult.Error -> null
                    }
                    if (car == null || (scope.source == com.matelink.data.local.HistoryConnectionSource.CLOUD &&
                            !com.matelink.data.api.models.validHistoryVehicleUid(car.vehicleUid))) null
                    else vehicleContexts.resolve(car, scope)
                } else verifiedPosition?.takeIf {
                    it.first == scope && it.second.remoteApiCarId == id
                }?.second
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { null }
            if (!current(vehicle)) {
                if (generation == positionGeneration) {
                    verifiedPosition = null
                    _uiState.value = _uiState.value.copy(latitude = null, longitude = null,
                        positionHistorical = false, positionObservedAt = null)
                }
                return@launch
            }
            val verified = vehicle ?: return@launch
            verifiedPosition = scope to verified
            val snapshot = if (fetch) repository.getAdapterSnapshot(id) else null
            val legacy = if (fetch && snapshot is ApiResult.Error) repository.getCarStatus(id) else null
            if (!current(verified)) return@launch
            val status = when {
                snapshot is ApiResult.Success -> snapshot.data.status
                legacy is ApiResult.Success -> legacy.data.status
                else -> null
            }
            val live = com.matelink.domain.map.VehiclePositionResolver.resolve(
                status?.latitude, status?.longitude, null, null)
            val observedAt = (snapshot as? ApiResult.Success)?.data?.observedAt
            if (status != null && current(verified))
                vehicleStatusStore.saveStatus(scope, verified, status, observedAt)
            val older = if (live == null)
                vehicleStatusStore.getCachedPosition(scope, verified) else null
            if (!current(verified)) return@launch
            val position = live ?: older
            _uiState.value = _uiState.value.copy(latitude = position?.latitude,
                longitude = position?.longitude,
                positionHistorical = live == null && older != null,
                positionObservedAt = if (live != null) observedAt else older?.observedAt)
        }
    }
}

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
                _uiState.value = _uiState.value.copy(latitude = null, longitude = null)
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
        val resolvedCarId = carId ?: settingsRepository.currentCarId.first()
        val scope = try { vehicleContexts.captureReadScope() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        val vehicle = try {
            if (scope == null) null else if (fetch) vehicleContexts.resolveRemote(resolvedCarId)
            else vehicleContexts.cachedContextForRemote(resolvedCarId, scope)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
        if (scope == null || vehicle == null ||
            !vehicleContexts.isHistoryContextCurrentNow(scope, vehicle)) {
            if (generation == positionGeneration)
                _uiState.value = _uiState.value.copy(latitude = null, longitude = null)
            return@launch
        }
        val cached = vehicleStatusStore.getCachedStatus(scope, vehicle)
        val snapshot = if (fetch) repository.getAdapterSnapshot(resolvedCarId) else null
        val legacy = if (fetch && snapshot is ApiResult.Error) repository.getCarStatus(resolvedCarId) else null
        if (generation != positionGeneration || settingsRepository.currentCarId.first() != resolvedCarId ||
            !vehicleContexts.isHistoryContextCurrentNow(scope, vehicle)) return@launch
        val status = when {
            snapshot is ApiResult.Success -> snapshot.data.status
            legacy is ApiResult.Success -> legacy.data.status
            else -> null
        }
        val merged = status?.let { vehicleStatusStore.reconcileAndSave(scope, vehicle, it,
            (snapshot as? ApiResult.Success)?.data?.observedAt) } ?: cached
        val position = VehiclePositionResolver.resolve(merged?.latitude, merged?.longitude, cached?.latitude, cached?.longitude)
        if (generation == positionGeneration && vehicleContexts.isHistoryContextCurrentNow(scope, vehicle))
            _uiState.value = _uiState.value.copy(latitude = position?.latitude, longitude = position?.longitude)
      }
    }
}

package com.matelink.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.AmapSettingsStore
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.repository.ApiResult
import com.matelink.domain.map.AmapConfiguration
import com.matelink.domain.map.AmapSetupState
import com.matelink.domain.map.amapSetupState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AmapMapUiState(
    val setupState: AmapSetupState = AmapSetupState.UNCONFIGURED,
    val key: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val loading: Boolean = false,
    val failed: Boolean = false
)

@HiltViewModel
class AmapMapViewModel @Inject constructor(
    private val store: AmapSettingsStore,
    private val repository: TeslamateRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(AmapMapUiState())
    val uiState: StateFlow<AmapMapUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.settings.collectLatest { settings ->
                val state = amapSetupState(settings.hasKey, settings.privacyAgreed, settings.restartRequired)
                _uiState.value = _uiState.value.copy(setupState = state, key = if (state == AmapSetupState.READY_TO_PREVIEW) store.currentKey() else "")
                if (state == AmapSetupState.READY_TO_PREVIEW) loadVehiclePosition()
            }
        }
    }

    fun onMapLoading() { _uiState.value = _uiState.value.copy(loading = true, failed = false) }
    fun onMapLoaded() { viewModelScope.launch { store.markMapLoaded() }; _uiState.value = _uiState.value.copy(loading = false, failed = false) }
    fun onMapFailure() { _uiState.value = _uiState.value.copy(loading = false, failed = true) }

    private fun loadVehiclePosition() = viewModelScope.launch {
        val carId = settingsRepository.currentCarId.first()
        val result = repository.getCarStatus(carId)
        val status = (result as? ApiResult.Success)?.data?.status
        val valid = AmapConfiguration.isUsableCoordinate(status?.latitude, status?.longitude)
        _uiState.value = _uiState.value.copy(latitude = status?.latitude?.takeIf { valid }, longitude = status?.longitude?.takeIf { valid })
    }
}

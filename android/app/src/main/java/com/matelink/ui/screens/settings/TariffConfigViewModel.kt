package com.matelink.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TariffConfigUiState(
    val isEnabled: Boolean = true,
    val peakPrice: Double = 1.0,
    val flatPrice: Double = 0.7,
    val valleyPrice: Double = 0.3,
    val peakRanges: List<TimeRange> = listOf(TimeRange(10, 14), TimeRange(18, 20)),
    val flatRanges: List<TimeRange> = listOf(TimeRange(7, 9), TimeRange(15, 17), TimeRange(21, 22)),
    val valleyRanges: List<TimeRange> = listOf(TimeRange(23, 23), TimeRange(0, 6)),
    val isSaving: Boolean = false
)

@HiltViewModel
class TariffConfigViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(TariffConfigUiState())
    val uiState: StateFlow<TariffConfigUiState> = _uiState.asStateFlow()

    fun loadConfig() {
        viewModelScope.launch {
            val settings = settingsDataStore.settings.first()
            _uiState.value = TariffConfigUiState(
                isEnabled = settings.tariffEnabled,
                peakPrice = settings.tariffPeakPrice,
                flatPrice = settings.tariffFlatPrice,
                valleyPrice = settings.tariffValleyPrice,
                peakRanges = parseTimeRanges(settings.tariffPeakRanges),
                flatRanges = parseTimeRanges(settings.tariffFlatRanges),
                valleyRanges = parseTimeRanges(settings.tariffValleyRanges)
            )
        }
    }

    fun updateEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isEnabled = enabled)
        saveConfig()
    }

    fun updatePeakPrice(price: Double) {
        _uiState.value = _uiState.value.copy(peakPrice = price)
        saveConfig()
    }

    fun updateFlatPrice(price: Double) {
        _uiState.value = _uiState.value.copy(flatPrice = price)
        saveConfig()
    }

    fun updateValleyPrice(price: Double) {
        _uiState.value = _uiState.value.copy(valleyPrice = price)
        saveConfig()
    }

    fun updatePeakRanges(ranges: List<TimeRange>) {
        _uiState.value = _uiState.value.copy(peakRanges = ranges)
        saveConfig()
    }

    fun updateFlatRanges(ranges: List<TimeRange>) {
        _uiState.value = _uiState.value.copy(flatRanges = ranges)
        saveConfig()
    }

    fun updateValleyRanges(ranges: List<TimeRange>) {
        _uiState.value = _uiState.value.copy(valleyRanges = ranges)
        saveConfig()
    }

    fun resetToDefaults() {
        _uiState.value = TariffConfigUiState()
        saveConfig()
    }

    private fun saveConfig() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsDataStore.saveTariffConfig(
                enabled = state.isEnabled,
                peakPrice = state.peakPrice,
                flatPrice = state.flatPrice,
                valleyPrice = state.valleyPrice,
                peakRanges = serializeTimeRanges(state.peakRanges),
                flatRanges = serializeTimeRanges(state.flatRanges),
                valleyRanges = serializeTimeRanges(state.valleyRanges)
            )
        }
    }
}

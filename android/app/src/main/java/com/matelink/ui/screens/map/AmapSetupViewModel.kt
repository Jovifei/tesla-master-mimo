package com.matelink.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.AmapSettingsStore
import com.matelink.domain.map.AmapSetupState
import com.matelink.domain.map.amapSetupState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AmapSetupUiState(
    val keyInput: String = "",
    val hasKey: Boolean = false,
    val privacyAgreed: Boolean = false,
    val restartRequired: Boolean = false,
    val mapLoaded: Boolean = false,
    val keyError: Boolean = false
) {
    val state: AmapSetupState get() = amapSetupState(hasKey, privacyAgreed, restartRequired)
}

@HiltViewModel
class AmapSetupViewModel @Inject constructor(
    private val store: AmapSettingsStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(AmapSetupUiState())
    val uiState: StateFlow<AmapSetupUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.settings.collectLatest { settings ->
                _uiState.value = _uiState.value.copy(
                    hasKey = settings.hasKey,
                    privacyAgreed = settings.privacyAgreed,
                    restartRequired = settings.restartRequired,
                    mapLoaded = settings.mapLoaded
                )
            }
        }
    }

    fun updateKey(value: String) { _uiState.value = _uiState.value.copy(keyInput = value, keyError = false) }

    fun saveKey() {
        viewModelScope.launch {
            val saved = store.saveKey(_uiState.value.keyInput, AmapSdkGate.wasInitialized)
            _uiState.value = _uiState.value.copy(keyInput = if (saved) "" else _uiState.value.keyInput, keyError = !saved)
        }
    }

    fun clearKey() { viewModelScope.launch { store.clearKey(); _uiState.value = _uiState.value.copy(keyInput = "") } }
    fun setPrivacyAgreed(agreed: Boolean) { viewModelScope.launch { store.setPrivacyAgreed(agreed) } }
}

/** Process-only guard: a changed key cannot safely replace an already-created MapView. */
object AmapSdkGate {
    @Volatile var wasInitialized: Boolean = false
}

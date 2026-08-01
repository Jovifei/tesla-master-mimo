package com.matelink.ui.screens.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.AmapSettingsStore
import com.matelink.domain.map.AmapSetupState
import com.matelink.domain.map.amapSetupState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class AmapEmbeddedMapUiState(
    val setupState: AmapSetupState = AmapSetupState.UNCONFIGURED,
    val apiKey: String = "",
    val loading: Boolean = false,
    val failed: Boolean = false
)

@HiltViewModel
class AmapEmbeddedMapViewModel @Inject constructor(
    private val store: AmapSettingsStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(AmapEmbeddedMapUiState())
    val uiState: StateFlow<AmapEmbeddedMapUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.settings.collectLatest { settings ->
                val setupState = amapSetupState(
                    settings.hasKey,
                    settings.privacyAgreed,
                    settings.restartRequired,
                    settings.mapLoaded
                )
                _uiState.value = AmapEmbeddedMapUiState(
                    setupState = setupState,
                    apiKey = if (setupState == AmapSetupState.READY_TO_PREVIEW) {
                        store.currentKey()
                    } else {
                        ""
                    }
                )
            }
        }
    }

    fun onMapLoading() {
        _uiState.value = _uiState.value.copy(loading = true, failed = false)
    }

    fun onMapLoaded() {
        viewModelScope.launch { store.markMapLoaded() }
        _uiState.value = _uiState.value.copy(loading = false, failed = false)
    }

    fun onMapFailure() {
        _uiState.value = _uiState.value.copy(loading = false, failed = true)
    }
}

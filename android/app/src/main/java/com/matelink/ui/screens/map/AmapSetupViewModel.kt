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
    val keyError: Boolean = false,
    val isEditingKey: Boolean = false,
    val verificationFailed: Boolean = false
) {
    val state: AmapSetupState get() = amapSetupState(hasKey, privacyAgreed, restartRequired, mapLoaded)
    val hasVerifiedKey: Boolean get() = hasKey && mapLoaded
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

    fun updateKey(value: String) {
        _uiState.value = _uiState.value.copy(keyInput = value, keyError = false, verificationFailed = false)
    }

    fun startEditingKey() {
        _uiState.value = _uiState.value.copy(isEditingKey = true, keyInput = "", keyError = false, verificationFailed = false)
    }

    fun stageDraftKey(onReady: () -> Unit) {
        viewModelScope.launch {
            val staged = store.stageKeyForVerification(_uiState.value.keyInput)
            _uiState.value = _uiState.value.copy(keyError = !staged, verificationFailed = false)
            if (staged) onReady()
        }
    }

    fun stageSavedKey(onReady: () -> Unit) {
        viewModelScope.launch {
            val staged = store.stageSavedKeyForVerification()
            _uiState.value = _uiState.value.copy(keyError = !staged, verificationFailed = false)
            if (staged) onReady()
        }
    }

    fun acceptVerifiedKey() {
        viewModelScope.launch {
            val promoted = store.promoteVerifiedPendingKey()
            _uiState.value = _uiState.value.copy(
                keyInput = "",
                isEditingKey = !promoted,
                verificationFailed = !promoted,
                keyError = !promoted
            )
        }
    }

    fun rejectPendingKey() {
        store.discardPendingKey()
        _uiState.value = _uiState.value.copy(verificationFailed = true, keyInput = "", isEditingKey = !_uiState.value.hasKey)
    }

    fun cancelEditingKey() {
        _uiState.value = _uiState.value.copy(
            keyInput = "",
            keyError = false,
            verificationFailed = false,
            isEditingKey = false
        )
    }

    fun setPrivacyAgreed(agreed: Boolean) { viewModelScope.launch { store.setPrivacyAgreed(agreed) } }
}

/** Process-only guard: a changed key cannot safely replace an already-created MapView. */
object AmapSdkGate {
    @Volatile var wasInitialized: Boolean = false
}

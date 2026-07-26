package com.matelink.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StartDestinationViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _startDestination = MutableStateFlow<Screen?>(null)
    val startDestination: StateFlow<Screen?> = _startDestination.asStateFlow()

    private val _notificationPermissionAsked = MutableStateFlow(true) // default true to avoid flash
    val notificationPermissionAsked: StateFlow<Boolean> = _notificationPermissionAsked.asStateFlow()

    /**
     * The currently selected car id. The bottom navigation shell needs this to
     * resolve the Drives / Charges / More tabs (their routes require a carId),
     * mirroring how [com.matelink.ui.screens.dashboard.DashboardViewModel] picks
     * the active car. Defaults to 1 to match [SettingsRepository.currentCarId].
     */
    val currentCarId: StateFlow<Int> = settingsRepository.currentCarId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)

    init {
        viewModelScope.launch {
            // Configuration is available from More. Starting there hid all
            // primary screens whenever a connection was incomplete.
            _startDestination.value = Screen.Dashboard
        }
        viewModelScope.launch {
            settingsDataStore.notificationPermissionAsked.collect {
                _notificationPermissionAsked.value = it
            }
        }
    }

    suspend fun markNotificationPermissionAsked() {
        settingsDataStore.saveNotificationPermissionAsked()
    }
}

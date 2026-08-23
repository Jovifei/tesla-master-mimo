package com.matelink.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.ConnectionModeStore
import com.matelink.data.local.ConnectionMode
import com.matelink.data.local.InstanceDataStore
import com.matelink.data.local.JourVoltSessionStore
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StartDestinationViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val settingsRepository: SettingsRepository,
    private val jourVoltSessionStore: JourVoltSessionStore,
    private val instanceDataStore: InstanceDataStore,
    private val connectionModeStore: ConnectionModeStore
) : ViewModel() {

    private val _startDestination = MutableStateFlow<Screen?>(null)
    val startDestination: StateFlow<Screen?> = _startDestination.asStateFlow()

    private val _connectionMode = MutableStateFlow<ConnectionMode?>(null)
    val connectionMode: StateFlow<ConnectionMode?> = _connectionMode.asStateFlow()

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
            val settings = settingsDataStore.settings.first()
            val instances = instanceDataStore.instances.first()
            val mode = connectionModeStore.resolveInitial(
                settings = settings,
                instances = instances,
                hasJourVoltSession = jourVoltSessionStore.current() != null
            )
            _connectionMode.value = mode
            _startDestination.value = when {
                mode == ConnectionMode.SELF_HOSTED -> Screen.Dashboard
                jourVoltSessionStore.current() != null -> Screen.Dashboard
                else -> Screen.TeslaLogin
            }
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

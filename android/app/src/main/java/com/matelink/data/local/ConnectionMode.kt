package com.matelink.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.matelink.data.model.Instance
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class ConnectionMode {
    TESLA_CLOUD,
    SELF_HOSTED
}

private val Context.connectionModeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "matelink_connection_mode"
)

/**
 * Persists the selected connection route without changing any existing
 * server, token, Room, or instance data.
 */
@Singleton
class ConnectionModeStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val modeKey = stringPreferencesKey("mode")
    private val _current = MutableStateFlow<ConnectionMode?>(null)

    val mode: Flow<ConnectionMode?> = context.connectionModeDataStore.data
        .map { preferences -> preferences[modeKey]?.let(::parse) }

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
            .launch {
                mode.collect { _current.value = it }
            }
    }

    fun current(): ConnectionMode? = _current.value

    suspend fun set(mode: ConnectionMode) {
        context.connectionModeDataStore.edit { preferences ->
            preferences[modeKey] = mode.name
        }
        _current.value = mode
    }

    /**
     * Performs the one-time upgrade migration. Existing self-hosted settings
     * take precedence over the new cloud-login default; a JourVolt session
     * means the user already completed cloud authorization.
     */
    suspend fun resolveInitial(
        settings: AppSettings,
        instances: List<Instance>,
        hasJourVoltSession: Boolean
    ): ConnectionMode {
        val persistedMode = mode.first()
        val resolved = resolveInitialConnectionMode(
            persistedMode = persistedMode,
            settings = settings,
            instances = instances,
            hasJourVoltSession = hasJourVoltSession
        )
        if (persistedMode != resolved) {
            set(resolved)
        }
        return resolved
    }

    private fun parse(value: String): ConnectionMode? =
        runCatching { ConnectionMode.valueOf(value) }.getOrNull()
}

internal fun resolveInitialConnectionMode(
    persistedMode: ConnectionMode?,
    settings: AppSettings,
    instances: List<Instance>,
    hasJourVoltSession: Boolean
): ConnectionMode = when {
    hasJourVoltSession -> ConnectionMode.TESLA_CLOUD
    settings.serverUrl.isNotBlank() ||
        settings.apiToken.isNotBlank() ||
        instances.any { it.serverUrl.isNotBlank() } -> ConnectionMode.SELF_HOSTED
    persistedMode != null -> persistedMode
    else -> ConnectionMode.TESLA_CLOUD
}

internal fun migratedConnectionMode(
    settings: AppSettings,
    instances: List<Instance>,
    hasJourVoltSession: Boolean
): ConnectionMode = resolveInitialConnectionMode(
    persistedMode = null,
    settings = settings,
    instances = instances,
    hasJourVoltSession = hasJourVoltSession
)

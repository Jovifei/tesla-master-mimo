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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Persists the user's selected connection route without changing any existing
 * server, token, Room, or instance data.
 *
 * Once a mode has been persisted it is authoritative. Legacy server/session
 * signals are consulted only when no mode has ever been stored. This prevents
 * a stale self-hosted URL or a still-valid cloud session from silently undoing
 * an explicit mode choice on the next process start.
 */
@Singleton
class ConnectionModeStore internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.connectionModeDataStore)
    private val modeKey = stringPreferencesKey("mode")
    private val _current = MutableStateFlow<ConnectionMode?>(null)
    private val identityMutex = Mutex()
    @Volatile private var identityGeneration = 0L
    internal fun currentIdentityGeneration(): Long = identityGeneration

    val mode: Flow<ConnectionMode?> = dataStore.data
        .map { preferences -> preferences[modeKey]?.let(::parse) }

    init {
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        ).launch {
            mode.collect { _current.value = it }
        }
    }

    fun current(): ConnectionMode? = _current.value

    internal suspend fun identitySnapshot(): Pair<ConnectionMode?, Long> = identityMutex.withLock {
        mode.first() to identityGeneration
    }

    suspend fun set(mode: ConnectionMode) = identityMutex.withLock {
        val changed = this.mode.first() != mode
        if (changed) identityGeneration++
        dataStore.edit { preferences ->
            preferences[modeKey] = mode.name
        }
        _current.value = mode
    }

    /**
     * Resolves startup mode. Legacy inference is a one-time migration path only:
     * a persisted mode always wins on subsequent launches.
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
        if (persistedMode == null) {
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
    persistedMode != null -> persistedMode
    hasJourVoltSession -> ConnectionMode.TESLA_CLOUD
    settings.serverUrl.isNotBlank() ||
        settings.apiToken.isNotBlank() ||
        instances.any { it.serverUrl.isNotBlank() } -> ConnectionMode.SELF_HOSTED
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

package com.matelink.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.matelink.domain.map.AmapConfiguration
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

private val Context.amapDataStore by preferencesDataStore(name = "amap_settings")

data class AmapSettings(
    val hasKey: Boolean = false,
    val privacyAgreed: Boolean = false,
    val restartRequired: Boolean = false,
    val mapLoaded: Boolean = false
)

@Singleton
class AmapSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStore: SecureSettingsDataStore
) {
    private val privacyAgreedKey = booleanPreferencesKey("privacy_agreed")
    private val mapLoadedKey = booleanPreferencesKey("map_loaded")
    private val restartRequired = MutableStateFlow(false)

    val settings: Flow<AmapSettings> = combine(context.amapDataStore.data, secureStore.amapKeyFlow, restartRequired) { prefs, key, restart ->
        AmapSettings(
            hasKey = key.isNotBlank(),
            privacyAgreed = prefs[privacyAgreedKey] ?: false,
            restartRequired = restart,
            mapLoaded = prefs[mapLoadedKey] ?: false
        )
    }

    fun currentKey(): String = secureStore.getAmapKey()

    suspend fun saveKey(rawKey: String, sdkWasInitialized: Boolean): Boolean {
        val mutation = AmapConfiguration.prepareKeySave(rawKey, secureStore.getAmapKey(), sdkWasInitialized) ?: return false
        secureStore.setAmapKey(mutation.key)
        restartRequired.value = restartRequired.value || mutation.restartRequired
        context.amapDataStore.edit { prefs ->
            prefs[mapLoadedKey] = false
        }
        return true
    }

    suspend fun clearKey() {
        secureStore.setAmapKey(AmapConfiguration.clearKeyMutation().key)
        restartRequired.value = false
        context.amapDataStore.edit { prefs ->
            prefs[mapLoadedKey] = false
        }
    }

    suspend fun setPrivacyAgreed(agreed: Boolean) {
        context.amapDataStore.edit { prefs ->
            prefs[privacyAgreedKey] = agreed
            if (!agreed) prefs[mapLoadedKey] = false
        }
    }

    suspend fun markMapLoaded() {
        context.amapDataStore.edit { it[mapLoadedKey] = true }
    }
}

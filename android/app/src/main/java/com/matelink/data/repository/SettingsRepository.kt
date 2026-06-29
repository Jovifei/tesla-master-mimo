package com.matelink.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_TOKEN = stringPreferencesKey("api_token")
        val CURRENT_CAR_ID = intPreferencesKey("current_car_id")
        val THEME = stringPreferencesKey("theme")
        val UNITS = stringPreferencesKey("units")
        val MOCK_MODE = booleanPreferencesKey("mock_mode")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { it[Keys.SERVER_URL] ?: "" }
    val apiToken: Flow<String> = context.dataStore.data.map { it[Keys.API_TOKEN] ?: "" }
    val currentCarId: Flow<Int> = context.dataStore.data.map { it[Keys.CURRENT_CAR_ID] ?: 1 }
    val theme: Flow<String> = context.dataStore.data.map { it[Keys.THEME] ?: "system" }
    val units: Flow<String> = context.dataStore.data.map { it[Keys.UNITS] ?: "km" }
    val mockMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.MOCK_MODE] ?: true }

    suspend fun setServer(url: String, token: String) {
        context.dataStore.edit {
            it[Keys.SERVER_URL] = url
            it[Keys.API_TOKEN] = token
        }
    }

    suspend fun setCurrentCarId(id: Int) {
        context.dataStore.edit { it[Keys.CURRENT_CAR_ID] = id }
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { it[Keys.THEME] = theme }
    }

    suspend fun setUnits(units: String) {
        context.dataStore.edit { it[Keys.UNITS] = units }
    }

    suspend fun setMockMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MOCK_MODE] = enabled }
    }
}

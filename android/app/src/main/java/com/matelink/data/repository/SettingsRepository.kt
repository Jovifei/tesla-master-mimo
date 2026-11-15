package com.matelink.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.matelink.BuildConfig
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.validHistoryVehicleUid
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryReadScope
import java.security.MessageDigest
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
        val CURRENT_CAR_OWNER = stringPreferencesKey("current_car_owner")
        val THEME = stringPreferencesKey("theme")
        val UNITS = stringPreferencesKey("units")
        val MOCK_MODE = booleanPreferencesKey("mock_mode")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { it[Keys.SERVER_URL] ?: "" }
    val apiToken: Flow<String> = context.dataStore.data.map { it[Keys.API_TOKEN] ?: "" }
    val currentCarId: Flow<Int> = context.dataStore.data.map { it[Keys.CURRENT_CAR_ID] ?: 1 }
    val theme: Flow<String> = context.dataStore.data.map { it[Keys.THEME] ?: "system" }
    val units: Flow<String> = context.dataStore.data.map { it[Keys.UNITS] ?: "km" }
    val mockMode: Flow<Boolean> = context.dataStore.data.map {
        BuildConfig.JOURVOLT_MOCK_LOGIN && (it[Keys.MOCK_MODE] ?: false)
    }

    suspend fun setServer(url: String, token: String) {
        context.dataStore.edit {
            it[Keys.SERVER_URL] = url
            it[Keys.API_TOKEN] = token
        }
    }

    suspend fun setCurrentCarId(id: Int) {
        context.dataStore.edit { it[Keys.CURRENT_CAR_ID] = id }
    }

    /** Only a current authenticated /cars result can repair or select a vehicle. */
    internal suspend fun selectVerifiedCurrentCar(
        scope: HistoryReadScope, cars: List<CarData>, preferredCarId: Int? = null,
        isCurrent: suspend () -> Boolean
    ): CarData? {
        if (scope.effectiveApiOrigin.isBlank() ||
            (scope.source == HistoryConnectionSource.CLOUD && scope.accountNamespace.isNullOrBlank()) ||
            cars.any { it.carId <= 0 } || cars.map { it.carId }.distinct().size != cars.size) return null
        val eligible = cars.filter { scope.source != HistoryConnectionSource.CLOUD || validHistoryVehicleUid(it.vehicleUid) }
        if (eligible.isEmpty() || (scope.source == HistoryConnectionSource.CLOUD &&
                eligible.map { it.vehicleUid }.distinct().size != eligible.size)) return null
        val owner = selectionDigest(listOf(scope.source.name, scope.effectiveApiOrigin,
            scope.accountNamespace.orEmpty()).joinToString("\u0000"))
        val idKey = intPreferencesKey("current_car_id_$owner")
        val uidKey = stringPreferencesKey("current_car_uid_$owner")
        var selected: CarData? = null
        context.dataStore.edit { preferences ->
            if (!isCurrent()) return@edit
            val remembered = eligible.singleOrNull { it.carId == preferences[idKey] &&
                (scope.source != HistoryConnectionSource.CLOUD ||
                    selectionDigest(it.vehicleUid.orEmpty()) == preferences[uidKey]) }
            val currentOwner = preferences[Keys.CURRENT_CAR_OWNER]
            val current = if (currentOwner == null || currentOwner == owner)
                eligible.singleOrNull { it.carId == preferences[Keys.CURRENT_CAR_ID] } else null
            val car = preferredCarId?.let { id -> eligible.singleOrNull { it.carId == id } }
                ?: current ?: remembered ?: eligible.first()
            if (!isCurrent()) return@edit
            preferences[Keys.CURRENT_CAR_ID] = car.carId
            preferences[Keys.CURRENT_CAR_OWNER] = owner
            preferences[idKey] = car.carId
            if (scope.source == HistoryConnectionSource.CLOUD)
                preferences[uidKey] = selectionDigest(car.vehicleUid.orEmpty())
            selected = car
        }
        return selected.takeIf { isCurrent() }
    }

    private fun selectionDigest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { it[Keys.THEME] = theme }
    }

    suspend fun setUnits(units: String) {
        context.dataStore.edit { it[Keys.UNITS] = units }
    }

    suspend fun setMockMode(enabled: Boolean) {
        if (!BuildConfig.JOURVOLT_MOCK_LOGIN) return
        context.dataStore.edit { it[Keys.MOCK_MODE] = enabled }
    }
}

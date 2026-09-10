package com.matelink.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class TeslaOnboardingPhase {
    IDLE,
    CHECKING,
    PAIRING_REQUIRED,
    PERMISSION_REQUIRED,
    BLOCKED,
    PENDING,
    READY
}

data class TeslaOnboardingSnapshot(
    val phase: TeslaOnboardingPhase = TeslaOnboardingPhase.IDLE,
    val accountId: String? = null,
    val vehicleId: Int? = null,
    val virtualKeyUrl: String? = null,
    val launchPending: Boolean = false,
    val retryUsed: Boolean = false,
    val reason: String? = null
)

private val Context.teslaOnboardingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tesla_onboarding_state"
)

@Singleton
class TeslaOnboardingStateStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val phaseKey = stringPreferencesKey("phase")
    private val accountIdKey = stringPreferencesKey("account_id")
    private val vehicleIdKey = intPreferencesKey("vehicle_id")
    private val virtualKeyUrlKey = stringPreferencesKey("virtual_key_url")
    private val launchPendingKey = booleanPreferencesKey("launch_pending")
    private val reasonKey = stringPreferencesKey("reason")
    private val retryUsedKey = booleanPreferencesKey("retry_used")

    val state: Flow<TeslaOnboardingSnapshot> = context.teslaOnboardingDataStore.data.map { preferences ->
        TeslaOnboardingSnapshot(
            phase = runCatching {
                TeslaOnboardingPhase.valueOf(preferences[phaseKey].orEmpty())
            }.getOrDefault(TeslaOnboardingPhase.IDLE),
            accountId = preferences[accountIdKey],
            vehicleId = preferences[vehicleIdKey],
            virtualKeyUrl = preferences[virtualKeyUrlKey],
            launchPending = preferences[launchPendingKey] ?: false,
            retryUsed = preferences[retryUsedKey] ?: false,
            reason = preferences[reasonKey]
        )
    }

    suspend fun save(snapshot: TeslaOnboardingSnapshot) {
        context.teslaOnboardingDataStore.edit { preferences ->
            if (snapshot.phase == TeslaOnboardingPhase.IDLE) {
                preferences.clear()
            } else {
                preferences[phaseKey] = snapshot.phase.name
                snapshot.accountId?.let { preferences[accountIdKey] = it }
                    ?: preferences.remove(accountIdKey)
                snapshot.vehicleId?.let { preferences[vehicleIdKey] = it }
                    ?: preferences.remove(vehicleIdKey)
                snapshot.virtualKeyUrl?.let { preferences[virtualKeyUrlKey] = it }
                    ?: preferences.remove(virtualKeyUrlKey)
                preferences[launchPendingKey] = snapshot.launchPending
                preferences[retryUsedKey] = snapshot.retryUsed
                snapshot.reason?.let { preferences[reasonKey] = it } ?: preferences.remove(reasonKey)
            }
        }
    }
}

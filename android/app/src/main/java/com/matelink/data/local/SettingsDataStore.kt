package com.matelink.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manual override for car image selection.
 *
 * @param variant The model variant (e.g., "my", "myj", "myjs", "myjp")
 * @param wheelCode The wheel code (e.g., "WY18P", "WY19P")
 */
data class CarImageOverride(
    val variant: String,
    val wheelCode: String
) {
    fun toJson(): String = """{"variant":"$variant","wheelCode":"$wheelCode"}"""

    companion object {
        fun fromJson(json: String): CarImageOverride? {
            return try {
                val obj = JSONObject(json)
                CarImageOverride(
                    variant = obj.getString("variant"),
                    wheelCode = obj.getString("wheelCode")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "matelink_settings")

data class AppSettings(
    val defaultChargePrice: Double = 1.14,
    val serverUrl: String = "",
    val secondaryServerUrl: String = "",
    val apiToken: String = "",
    val httpBasicAuthUsername: String = "",
    val httpBasicAuthPassword: String = "",
    val acceptInvalidCerts: Boolean = false,
    val currencyCode: String = "CNY",
    val showShortDrivesCharges: Boolean = false,
    val teslamateBaseUrl: String = "",
    val lastSelectedCarId: Int? = null,
    val languageCode: String = "",
    val tariffEnabled: Boolean = true,
    val tariffPeakPrice: Double = 1.0,
    val tariffFlatPrice: Double = 0.7,
    val tariffValleyPrice: Double = 0.3,
    val tariffPeakRanges: String = "[[10,14],[18,20]]",
    val tariffFlatRanges: String = "[[7,9],[15,17],[21,22]]",
    val tariffValleyRanges: String = "[[23,23],[0,6]]"
) {
    val isConfigured: Boolean
        get() = serverUrl.isNotBlank()

    val hasSecondaryServer: Boolean
        get() = secondaryServerUrl.isNotBlank()
}

@Singleton
class SettingsDataStore internal constructor(
    private val backingDataStore: DataStore<Preferences>,
    private val secureStore: SecureSettingsDataStore,
    private val languagePrefs: SharedPreferences
) {
    @Inject constructor(@ApplicationContext context: Context, secureStore: SecureSettingsDataStore) : this(
        context.dataStore, secureStore, context.getSharedPreferences("matelink_language", Context.MODE_PRIVATE)
    )
    private val identityMutex = Mutex()
    @Volatile private var identityGeneration = 0L
    internal fun currentIdentityGeneration(): Long = identityGeneration
    private val persistentIdentityEpochKey = longPreferencesKey("history_identity_epoch")
    internal suspend fun persistentIdentityEpoch(): Long = identityMutex.withLock {
        backingDataStore.data.first()[persistentIdentityEpochKey] ?: 0L
    }
    private suspend fun advancePersistentIdentityEpoch() {
        backingDataStore.edit { it[persistentIdentityEpochKey] = Math.addExact(it[persistentIdentityEpochKey] ?: 0L, 1L) }
    }


    /** URL and its process generation are captured under the same lock as connection mutations. */
    internal suspend fun historyIdentitySnapshot(readServerUrl: suspend () -> String): Pair<String, Long> =
        identityMutex.withLock { readServerUrl() to identityGeneration }
    private val defaultChargePriceKey = stringPreferencesKey("default_charge_price")

    suspend fun saveDefaultChargePrice(price: Double) {
        require(price.isFinite() && price >= 0.0)
        backingDataStore.edit { it[defaultChargePriceKey] = price.toString() }
    }
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val secondaryServerUrlKey = stringPreferencesKey("secondary_server_url")
    private val acceptInvalidCertsKey = booleanPreferencesKey("accept_invalid_certs")
    private val currencyCodeKey = stringPreferencesKey("currency_code")
    private val showShortDrivesChargesKey = booleanPreferencesKey("show_short_drives_charges")
    private val teslamateBaseUrlKey = stringPreferencesKey("teslamate_base_url")
    private val lastSelectedCarIdKey = intPreferencesKey("last_selected_car_id")
    private val carImageOverridesKey = stringPreferencesKey("car_image_overrides")
    private val chargePriceOverridesKey = stringPreferencesKey("charge_price_overrides")
    private val chargeTotalOverridesKey = stringPreferencesKey("charge_total_overrides")
    private val chargeTotalOverridesMigratedKey = booleanPreferencesKey("charge_total_overrides_migrated")
    private val languageCodeKey = stringPreferencesKey("language_code")
    private val notificationPermissionAskedKey = booleanPreferencesKey("notification_permission_asked")
    private val tariffEnabledKey = booleanPreferencesKey("tariff_enabled")
    private val tariffPeakPriceKey = stringPreferencesKey("tariff_peak_price")
    private val tariffFlatPriceKey = stringPreferencesKey("tariff_flat_price")
    private val tariffValleyPriceKey = stringPreferencesKey("tariff_valley_price")
    private val tariffPeakRangesKey = stringPreferencesKey("tariff_peak_ranges")
    private val tariffFlatRangesKey = stringPreferencesKey("tariff_flat_ranges")
    private val tariffValleyRangesKey = stringPreferencesKey("tariff_valley_ranges")
    private val tpmsAlertProfilesKey = stringPreferencesKey("tpms_alert_profiles")

    val notificationPermissionAsked: Flow<Boolean> = backingDataStore.data.map { preferences ->
        preferences[notificationPermissionAskedKey] ?: false
    }

    val settings: Flow<AppSettings> = backingDataStore.data.map { preferences ->
        AppSettings(
            defaultChargePrice = preferences[defaultChargePriceKey]?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 } ?: 1.14,
            serverUrl = preferences[serverUrlKey] ?: "",
            secondaryServerUrl = preferences[secondaryServerUrlKey] ?: "",
            apiToken = secureStore.getApiToken(),
            httpBasicAuthUsername = secureStore.getHttpBasicUsername(),
            httpBasicAuthPassword = secureStore.getHttpBasicPassword(),
            acceptInvalidCerts = preferences[acceptInvalidCertsKey] ?: false,
            currencyCode = preferences[currencyCodeKey] ?: legacyDefaultCurrency(preferences),
            showShortDrivesCharges = preferences[showShortDrivesChargesKey] ?: false,
            teslamateBaseUrl = preferences[teslamateBaseUrlKey] ?: "",
            lastSelectedCarId = preferences[lastSelectedCarIdKey],
            languageCode = languagePrefs.getString("language_code", "") ?: "",
            tariffEnabled = preferences[tariffEnabledKey] ?: true,
            tariffPeakPrice = preferences[tariffPeakPriceKey]?.toDoubleOrNull() ?: 1.0,
            tariffFlatPrice = preferences[tariffFlatPriceKey]?.toDoubleOrNull() ?: 0.7,
            tariffValleyPrice = preferences[tariffValleyPriceKey]?.toDoubleOrNull() ?: 0.3,
            tariffPeakRanges = preferences[tariffPeakRangesKey] ?: "[[10,14],[18,20]]",
            tariffFlatRanges = preferences[tariffFlatRangesKey] ?: "[[7,9],[15,17],[21,22]]",
            tariffValleyRanges = preferences[tariffValleyRangesKey] ?: "[[23,23],[0,6]]"
        )
    }

    val languageCode: Flow<String> = kotlinx.coroutines.flow.flow {
        emit(languagePrefs.getString("language_code", "") ?: "")
    }

    val showShortDrivesCharges: Flow<Boolean> = backingDataStore.data.map { preferences ->
        preferences[showShortDrivesChargesKey] ?: false
    }

    /**
     * Flow of car image overrides, keyed by car ID.
     */
    val carImageOverrides: Flow<Map<Int, CarImageOverride>> = backingDataStore.data.map { preferences ->
        val jsonString = preferences[carImageOverridesKey] ?: "{}"
        parseOverridesJson(jsonString)
    }

    val chargePriceOverrides: Flow<Map<String, Double>> = backingDataStore.data.map { preferences ->
        parseChargePriceOverrides(preferences[chargePriceOverridesKey] ?: "{}")
    }

    /** Manual total amount (¥) overrides, keyed by car ID and charge ID. */
    val chargeTotalOverrides: Flow<Map<String, Double>> = backingDataStore.data.map { preferences ->
        parseChargePriceOverrides(preferences[chargeTotalOverridesKey] ?: "{}")
    }

    /** Whether the legacy JSON total overrides have been copied into Room. */
    val chargeTotalOverridesMigrated: Flow<Boolean> = backingDataStore.data.map { preferences ->
        preferences[chargeTotalOverridesMigratedKey] ?: false
    }

    val tpmsAlertProfiles: Flow<Map<Int, TpmsAlertProfile>> = backingDataStore.data.map { preferences ->
        parseTpmsAlertProfiles(preferences[tpmsAlertProfilesKey] ?: "{}")
    }

    private fun parseOverridesJson(jsonString: String): Map<Int, CarImageOverride> {
        return try {
            val result = mutableMapOf<Int, CarImageOverride>()
            val obj = JSONObject(jsonString)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val carId = key.toIntOrNull() ?: continue
                val overrideJson = obj.getJSONObject(key)
                val override = CarImageOverride(
                    variant = overrideJson.getString("variant"),
                    wheelCode = overrideJson.getString("wheelCode")
                )
                result[carId] = override
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun overridesToJson(overrides: Map<Int, CarImageOverride>): String {
        val obj = JSONObject()
        for ((carId, override) in overrides) {
            val overrideObj = JSONObject()
            overrideObj.put("variant", override.variant)
            overrideObj.put("wheelCode", override.wheelCode)
            obj.put(carId.toString(), overrideObj)
        }
        return obj.toString()
    }

    private fun parseChargePriceOverrides(jsonString: String): Map<String, Double> {
        return runCatching {
            val obj = JSONObject(jsonString)
            buildMap {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    obj.optDouble(key, Double.NaN)
                        .takeIf { it.isFinite() && it >= 0.0 }
                        ?.let { put(key, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun parseTpmsAlertProfiles(jsonString: String): Map<Int, TpmsAlertProfile> = runCatching {
        val obj = JSONObject(jsonString)
        buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val carId = key.toIntOrNull() ?: continue
                val profile = runCatching {
                    obj.optJSONObject(key)?.let { TpmsAlertProfile.fromJson(it.toString()) }
                }.getOrNull()
                if (profile != null) put(carId, profile)
            }
        }
    }.getOrDefault(emptyMap())

    private fun tpmsAlertProfilesToJson(profiles: Map<Int, TpmsAlertProfile>): String = JSONObject().apply {
        profiles.forEach { (carId, profile) -> put(carId.toString(), JSONObject(profile.toJson())) }
    }.toString()

    private fun legacyDefaultCurrency(preferences: Preferences): String {
        return defaultCurrencyCode(
            serverUrl = preferences[serverUrlKey],
            apiToken = secureStore.getApiToken()
        )
    }

    private fun chargePriceOverridesToJson(overrides: Map<String, Double>): String =
        JSONObject().apply {
            overrides.forEach { (key, price) -> put(key, price) }
        }.toString()

    suspend fun saveSettings(
        serverUrl: String,
        secondaryServerUrl: String,
        apiToken: String,
        httpBasicAuthUsername: String,
        httpBasicAuthPassword: String,
        acceptInvalidCerts: Boolean,
        currencyCode: String
    ): Unit = identityMutex.withLock {
        identityGeneration++
        advancePersistentIdentityEpoch()
        backingDataStore.edit { preferences ->
            preferences[serverUrlKey] = serverUrl
            preferences[secondaryServerUrlKey] = secondaryServerUrl
            preferences[acceptInvalidCertsKey] = acceptInvalidCerts
            preferences[currencyCodeKey] = currencyCode
        }
        secureStore.setApiToken(apiToken)
        secureStore.setHttpBasicUsername(httpBasicAuthUsername)
        secureStore.setHttpBasicPassword(httpBasicAuthPassword)
    }

    /** Saves the two user-facing connection values without rewriting legacy advanced settings. */
    suspend fun saveConnectionSettings(serverUrl: String, apiToken: String, currencyCode: String): Unit = identityMutex.withLock {
        identityGeneration++
        advancePersistentIdentityEpoch()
        backingDataStore.edit { preferences ->
            preferences[serverUrlKey] = serverUrl
            preferences[currencyCodeKey] = currencyCode
        }
        secureStore.setApiToken(apiToken)
    }

    suspend fun saveHttpBasicAuth(username: String, password: String): Unit = identityMutex.withLock {
        identityGeneration++
        advancePersistentIdentityEpoch()
        secureStore.setHttpBasicUsername(username)
        secureStore.setHttpBasicPassword(password)
    }

    suspend fun saveServerUrl(url: String): Unit = identityMutex.withLock {
        identityGeneration++
        advancePersistentIdentityEpoch()
        backingDataStore.edit { preferences ->
            preferences[serverUrlKey] = url
        }
    }

    suspend fun saveApiToken(token: String): Unit = identityMutex.withLock {
        identityGeneration++
        advancePersistentIdentityEpoch()
        secureStore.setApiToken(token)
    }

    suspend fun saveCurrency(currencyCode: String) {
        backingDataStore.edit { preferences ->
            preferences[currencyCodeKey] = currencyCode
        }
    }

    suspend fun saveShowShortDrivesCharges(show: Boolean) {
        backingDataStore.edit { preferences ->
            preferences[showShortDrivesChargesKey] = show
        }
    }

    suspend fun saveLanguageCode(languageCode: String) {
        languagePrefs.edit().putString("language_code", languageCode).apply()
    }

    suspend fun saveTeslamateBaseUrl(url: String) {
        backingDataStore.edit { preferences ->
            preferences[teslamateBaseUrlKey] = url
        }
    }

    suspend fun saveLastSelectedCarId(carId: Int) {
        backingDataStore.edit { preferences ->
            preferences[lastSelectedCarIdKey] = carId
        }
    }

    /**
     * Save or clear a car image override.
     *
     * @param carId The car ID to save the override for
     * @param override The override to save, or null to clear
     */
    suspend fun saveCarImageOverride(carId: Int, override: CarImageOverride?) {
        backingDataStore.edit { preferences ->
            val currentJson = preferences[carImageOverridesKey] ?: "{}"
            val currentMap = parseOverridesJson(currentJson).toMutableMap()

            if (override != null) {
                currentMap[carId] = override
            } else {
                currentMap.remove(carId)
            }

            preferences[carImageOverridesKey] = overridesToJson(currentMap)
        }
    }

    suspend fun saveChargePriceOverride(key: String, pricePerKwh: Double?) {
        backingDataStore.edit { preferences ->
            val overrides = parseChargePriceOverrides(
                preferences[chargePriceOverridesKey] ?: "{}"
            ).toMutableMap()
            if (pricePerKwh != null && pricePerKwh.isFinite() && pricePerKwh >= 0.0) {
                overrides[key] = pricePerKwh
            } else {
                overrides.remove(key)
            }
            preferences[chargePriceOverridesKey] = chargePriceOverridesToJson(overrides)
        }
    }

    suspend fun saveChargeTotalOverride(key: String, totalAmount: Double?) {
        backingDataStore.edit { preferences ->
            val overrides = parseChargePriceOverrides(
                preferences[chargeTotalOverridesKey] ?: "{}"
            ).toMutableMap()
            if (totalAmount != null && totalAmount.isFinite() && totalAmount >= 0.0) {
                overrides[key] = totalAmount
            } else {
                overrides.remove(key)
            }
            preferences[chargeTotalOverridesKey] = chargePriceOverridesToJson(overrides)
        }
    }

    /** Mark the legacy total override JSON as migrated and remove the old copy. */
    suspend fun markChargeTotalOverridesMigrated() {
        backingDataStore.edit { preferences ->
            preferences.remove(chargeTotalOverridesKey)
            preferences[chargeTotalOverridesMigratedKey] = true
        }
    }

    suspend fun saveNotificationPermissionAsked() {
        backingDataStore.edit { preferences ->
            preferences[notificationPermissionAskedKey] = true
        }
    }

    suspend fun saveTariffConfig(
        enabled: Boolean,
        peakPrice: Double,
        flatPrice: Double,
        valleyPrice: Double,
        peakRanges: String,
        flatRanges: String,
        valleyRanges: String
    ) {
        backingDataStore.edit { preferences ->
            preferences[tariffEnabledKey] = enabled
            preferences[tariffPeakPriceKey] = peakPrice.toString()
            preferences[tariffFlatPriceKey] = flatPrice.toString()
            preferences[tariffValleyPriceKey] = valleyPrice.toString()
            preferences[tariffPeakRangesKey] = peakRanges
            preferences[tariffFlatRangesKey] = flatRanges
            preferences[tariffValleyRangesKey] = valleyRanges
        }
    }

    fun observeTpmsAlertProfile(carId: Int): Flow<TpmsAlertProfile?> =
        tpmsAlertProfiles.map { it[carId] }

    suspend fun getTpmsAlertProfile(carId: Int): TpmsAlertProfile? =
        observeTpmsAlertProfile(carId).first()

    /** Save only a finite, strictly ordered profile; invalid profiles can never enable alerts. */
    suspend fun saveTpmsAlertProfile(carId: Int, profile: TpmsAlertProfile) {
        require(profile.isValid) { "TPMS alert bounds must satisfy 0 < low < target < high" }
        backingDataStore.edit { preferences ->
            val profiles = parseTpmsAlertProfiles(preferences[tpmsAlertProfilesKey] ?: "{}").toMutableMap()
            profiles[carId] = profile
            preferences[tpmsAlertProfilesKey] = tpmsAlertProfilesToJson(profiles)
        }
    }

    suspend fun clearTpmsAlertProfile(carId: Int) {
        backingDataStore.edit { preferences ->
            val profiles = parseTpmsAlertProfiles(preferences[tpmsAlertProfilesKey] ?: "{}").toMutableMap()
            profiles.remove(carId)
            preferences[tpmsAlertProfilesKey] = tpmsAlertProfilesToJson(profiles)
        }
    }

    suspend fun clearSettings(): Unit = identityMutex.withLock {
        identityGeneration++
        backingDataStore.edit { preferences ->
            val nextEpoch = Math.addExact(preferences[persistentIdentityEpochKey] ?: 0L, 1L)
            preferences.clear()
            preferences[persistentIdentityEpochKey] = nextEpoch
        }
    }
}

data class TpmsAlertProfile(
    val targetBar: Double,
    val lowBar: Double,
    val highBar: Double,
    val enabled: Boolean
) {
    val isValid: Boolean
        get() = targetBar.isFinite() && lowBar.isFinite() && highBar.isFinite() &&
            0.0 < lowBar && lowBar < targetBar && targetBar < highBar

    fun toJson(): String = JSONObject().apply {
        put("targetBar", targetBar)
        put("lowBar", lowBar)
        put("highBar", highBar)
        put("enabled", enabled)
    }.toString()

    companion object {
        /** Editable Model Y suggestion, not a universal Tesla alert threshold. */
        val modelYSuggestion = TpmsAlertProfile(2.9, 2.6, 3.4, enabled = false)

        fun fromJson(json: String): TpmsAlertProfile? = runCatching {
            val obj = JSONObject(json)
            TpmsAlertProfile(
                targetBar = obj.getDouble("targetBar"),
                lowBar = obj.getDouble("lowBar"),
                highBar = obj.getDouble("highBar"),
                enabled = obj.optBoolean("enabled", false)
            ).takeIf { it.isValid }
        }.getOrNull()
    }
}

internal fun defaultCurrencyCode(serverUrl: String?, apiToken: String): String =
    if (!serverUrl.isNullOrBlank() || apiToken.isNotBlank()) "EUR" else "CNY"

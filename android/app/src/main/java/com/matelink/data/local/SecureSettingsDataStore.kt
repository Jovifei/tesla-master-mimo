package com.matelink.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for sensitive credentials using EncryptedSharedPreferences.
 * Replaces plain-text DataStore storage for API tokens and passwords.
 */
@Singleton
class SecureSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "matelink_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _apiTokenFlow = MutableStateFlow(getApiToken())
    val apiTokenFlow: Flow<String> = _apiTokenFlow.asStateFlow()

    private val _httpBasicPasswordFlow = MutableStateFlow(getHttpBasicPassword())
    val httpBasicPasswordFlow: Flow<String> = _httpBasicPasswordFlow.asStateFlow()

    fun getApiToken(): String = securePrefs.getString(KEY_API_TOKEN, "") ?: ""

    fun setApiToken(token: String) {
        securePrefs.edit().putString(KEY_API_TOKEN, token).apply()
        _apiTokenFlow.value = token
    }

    fun getHttpBasicUsername(): String = securePrefs.getString(KEY_HTTP_BASIC_USERNAME, "") ?: ""

    fun setHttpBasicUsername(username: String) {
        securePrefs.edit().putString(KEY_HTTP_BASIC_USERNAME, username).apply()
    }

    fun getHttpBasicPassword(): String = securePrefs.getString(KEY_HTTP_BASIC_PASSWORD, "") ?: ""

    fun setHttpBasicPassword(password: String) {
        securePrefs.edit().putString(KEY_HTTP_BASIC_PASSWORD, password).apply()
        _httpBasicPasswordFlow.value = password
    }

    fun clearAll() {
        securePrefs.edit().clear().apply()
        _apiTokenFlow.value = ""
        _httpBasicPasswordFlow.value = ""
    }

    // ---- Per-instance token storage ----

    /** Get API token for a specific instance. */
    fun getInstanceToken(instanceId: String): String =
        securePrefs.getString("instance_token_$instanceId", "") ?: ""

    /** Set API token for a specific instance. */
    fun setInstanceToken(instanceId: String, token: String) {
        securePrefs.edit().putString("instance_token_$instanceId", token).apply()
    }

    /** Remove API token for a specific instance. */
    fun removeInstanceToken(instanceId: String) {
        securePrefs.edit().remove("instance_token_$instanceId").apply()
    }

    companion object {
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_HTTP_BASIC_USERNAME = "http_basic_username"
        private const val KEY_HTTP_BASIC_PASSWORD = "http_basic_password"
    }
}

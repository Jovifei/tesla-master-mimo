package com.matelink.p0

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

private val Context.qualificationDataStore by preferencesDataStore(name = "matelink_settings")

class QualificationStorageTest {
    @Test fun seedSyntheticConnectionForUpgrade() = runBlocking {
        assumeStorageFlowEnabled()
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        context.qualificationDataStore.edit { preferences ->
            preferences[stringPreferencesKey("server_url")] = SYNTHETIC_BASE_URL
        }
        securePrefs(context).edit().putString("api_token", SYNTHETIC_TOKEN).commit()

        check(readServerUrl(context) == SYNTHETIC_BASE_URL) { "synthetic server url was not stored" }
        check(readToken(context) == SYNTHETIC_TOKEN) { "synthetic credential was not stored" }
    }

    @Test fun verifySyntheticConnectionSurvivedUpgrade() = runBlocking {
        assumeStorageFlowEnabled()
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        check(readServerUrl(context) == SYNTHETIC_BASE_URL) { "synthetic server url was not retained" }
        check(readToken(context) == SYNTHETIC_TOKEN) { "synthetic credential was not retained" }
    }

    private suspend fun readServerUrl(context: Context): String {
        return context.qualificationDataStore.data.first()[stringPreferencesKey("server_url")] ?: ""
    }

    private fun readToken(context: Context): String {
        return securePrefs(context).getString("api_token", "") ?: ""
    }

    private fun securePrefs(context: Context) =
        EncryptedSharedPreferences.create(
            context,
            "matelink_secure_prefs",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    companion object {
        private const val SYNTHETIC_BASE_URL = "http://10.0.2.2:18080"
        private const val SYNTHETIC_TOKEN = "synthetic-qualification-key"
    }

    private fun assumeStorageFlowEnabled() {
        val enabled = InstrumentationRegistry.getArguments().getString("p0.storage") == "true"
        assumeTrue("upgrade storage tests run only in the isolated baseline flow", enabled)
    }
}

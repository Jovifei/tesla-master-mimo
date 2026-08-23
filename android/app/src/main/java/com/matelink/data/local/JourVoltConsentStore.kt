package com.matelink.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Versions are sent to the service only after the user actively confirms both documents. */
object JourVoltConsentDocuments {
    const val TERMS_VERSION = "2026-08-21"
    const val PRIVACY_VERSION = "2026-08-21"
}

data class JourVoltConsent(
    val termsVersion: String,
    val privacyVersion: String,
    val acceptedAtEpochMs: Long
) {
    val isCurrent: Boolean
        get() = termsVersion == JourVoltConsentDocuments.TERMS_VERSION &&
            privacyVersion == JourVoltConsentDocuments.PRIVACY_VERSION &&
            acceptedAtEpochMs > 0L
}

private val Context.jourVoltConsentDataStore by preferencesDataStore(name = "jourvolt_consent")

/**
 * Keeps only the document versions and acceptance time on-device. Tesla credentials never enter
 * this store. The service records the same versions only after successful OAuth identity proof.
 */
@Singleton
class JourVoltConsentStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val termsVersionKey = stringPreferencesKey("terms_version")
    private val privacyVersionKey = stringPreferencesKey("privacy_version")
    private val acceptedAtKey = longPreferencesKey("accepted_at_epoch_ms")

    val consent: Flow<JourVoltConsent?> = context.jourVoltConsentDataStore.data
        .map(::readConsent)

    suspend fun recordCurrent(): JourVoltConsent {
        val current = JourVoltConsent(
            termsVersion = JourVoltConsentDocuments.TERMS_VERSION,
            privacyVersion = JourVoltConsentDocuments.PRIVACY_VERSION,
            acceptedAtEpochMs = System.currentTimeMillis()
        )
        context.jourVoltConsentDataStore.edit { preferences ->
            preferences[termsVersionKey] = current.termsVersion
            preferences[privacyVersionKey] = current.privacyVersion
            preferences[acceptedAtKey] = current.acceptedAtEpochMs
        }
        return current
    }

    suspend fun hasCurrentConsent(): Boolean = consent.first()?.isCurrent == true

    suspend fun clear() {
        context.jourVoltConsentDataStore.edit { it.clear() }
    }

    private fun readConsent(preferences: Preferences): JourVoltConsent? {
        val termsVersion = preferences[termsVersionKey] ?: return null
        val privacyVersion = preferences[privacyVersionKey] ?: return null
        val acceptedAt = preferences[acceptedAtKey] ?: return null
        return JourVoltConsent(termsVersion, privacyVersion, acceptedAt)
    }
}

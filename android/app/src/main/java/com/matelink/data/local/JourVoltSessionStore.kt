package com.matelink.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class JourVoltSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val expiresAtEpochMs: Long
)

/** Stores only JourVolt session credentials; Tesla credentials never enter Android. */
@Singleton
class JourVoltSessionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "jourvolt_session_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _session = MutableStateFlow(read())
    val session: StateFlow<JourVoltSession?> = _session.asStateFlow()

    fun current(): JourVoltSession? = _session.value

    fun save(accessToken: String, refreshToken: String, expiresInSeconds: Long, userId: String) {
        val session = JourVoltSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            expiresAtEpochMs = System.currentTimeMillis() + expiresInSeconds * 1000L
        )
        prefs.edit()
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .putString(KEY_USER, session.userId)
            .putLong(KEY_EXPIRES, session.expiresAtEpochMs)
            .apply()
        _session.value = session
    }

    fun clear() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    private fun read(): JourVoltSession? {
        val access = prefs.getString(KEY_ACCESS, null)?.takeIf { it.isNotBlank() } ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null)?.takeIf { it.isNotBlank() } ?: return null
        return JourVoltSession(
            accessToken = access,
            refreshToken = refresh,
            userId = prefs.getString(KEY_USER, "") ?: "",
            expiresAtEpochMs = prefs.getLong(KEY_EXPIRES, 0L)
        )
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER = "user_id"
        const val KEY_EXPIRES = "expires_at"
    }
}

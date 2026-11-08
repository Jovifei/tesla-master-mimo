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
class JourVoltSessionStore internal constructor(private val prefs: SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) : this(sessionPreferences(context))

    private val _session = MutableStateFlow(read())
    @Volatile private var identityGeneration = 0L
    val session: StateFlow<JourVoltSession?> = _session.asStateFlow()

    @Synchronized fun current(): JourVoltSession? = _session.value

    /** Session and generation are read under the same monitor used by save/clear. */
    @Synchronized internal fun identitySnapshot(): Pair<JourVoltSession?, Long> = _session.value to identityGeneration
    /** A login identity survives token rotation and remains monotonic after store recreation. */
    @Synchronized internal fun loginIdentitySnapshot(): Pair<JourVoltSession?, Long> =
        _session.value to prefs.getLong(KEY_LOGIN_IDENTITY_EPOCH, 0L)
    @Synchronized internal fun clearIfLoginCurrent(expected: JourVoltSession?, generation: Long): Boolean {
        if (_session.value?.userId != expected?.userId ||
            prefs.getLong(KEY_LOGIN_IDENTITY_EPOCH, 0L) != generation) return false
        clear()
        return true
    }
    internal fun currentIdentityGeneration(): Long = identityGeneration
    @Synchronized internal fun persistentIdentityEpoch(): Long = prefs.getLong(KEY_IDENTITY_EPOCH, 0L)

    @Synchronized fun save(accessToken: String, refreshToken: String, expiresInSeconds: Long, userId: String) {
        persistSession(accessToken, refreshToken, expiresInSeconds, userId, newLogin = true)
    }

    private fun persistSession(accessToken: String, refreshToken: String, expiresInSeconds: Long,
                               userId: String, newLogin: Boolean) {
        identityGeneration++
        val loginEpoch = prefs.getLong(KEY_LOGIN_IDENTITY_EPOCH, 0L)
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
            .putLong(KEY_IDENTITY_EPOCH, Math.addExact(prefs.getLong(KEY_IDENTITY_EPOCH, 0L), 1L))
            .putLong(KEY_LOGIN_IDENTITY_EPOCH, if (newLogin) Math.addExact(loginEpoch, 1L) else loginEpoch)
            .commit().also { check(it) { "session_persistence_failed" } }
        _session.value = session
    }

    /** A late refresh must not replace a logout, re-login, or newer rotation. */
    @Synchronized internal fun rotateIfCurrent(
        expected: JourVoltSession, generation: Long,
        accessToken: String, refreshToken: String, expiresInSeconds: Long
    ): Boolean {
        if (_session.value != expected || identityGeneration != generation) return false
        persistSession(accessToken, refreshToken, expiresInSeconds, expected.userId, newLogin = false)
        return true
    }

    @Synchronized internal fun clearIfCurrent(expected: JourVoltSession, generation: Long): Boolean {
        if (_session.value != expected || identityGeneration != generation) return false
        clear()
        return true
    }

    @Synchronized fun clear() {
        identityGeneration++
        val nextEpoch = Math.addExact(prefs.getLong(KEY_IDENTITY_EPOCH, 0L), 1L)
        val nextLoginEpoch = Math.addExact(prefs.getLong(KEY_LOGIN_IDENTITY_EPOCH, 0L), 1L)
        check(prefs.edit().clear().putLong(KEY_IDENTITY_EPOCH, nextEpoch)
            .putLong(KEY_LOGIN_IDENTITY_EPOCH, nextLoginEpoch).commit()) { "session_persistence_failed" }
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
        const val KEY_IDENTITY_EPOCH = "history_session_epoch"
        const val KEY_LOGIN_IDENTITY_EPOCH = "login_identity_epoch"
    }
}

private fun sessionPreferences(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    return EncryptedSharedPreferences.create(context, "jourvolt_session_secure", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
}

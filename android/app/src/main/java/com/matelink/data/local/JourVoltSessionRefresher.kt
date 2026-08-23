package com.matelink.data.local

import com.matelink.BuildConfig
import com.matelink.data.api.validatedJourVoltApiBaseUrl
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Performs a single-flight JourVolt session rotation without logging tokens. */
@Singleton
class JourVoltSessionRefresher @Inject constructor(
    private val sessionStore: JourVoltSessionStore
) {
    private val refreshMutex = Mutex()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun refreshIfCurrent(staleAccessToken: String): String? = refreshMutex.withLock {
        val current = sessionStore.current() ?: return@withLock null
        if (current.accessToken != staleAccessToken) return@withLock current.accessToken

        val rawBaseUrl = if (BuildConfig.DEBUG && BuildConfig.JOURVOLT_MOCK_LOGIN) {
            BuildConfig.JOURVOLT_MOCK_BASE_URL
        } else {
            BuildConfig.JOURVOLT_API_BASE_URL
        }
        val baseUrl = validatedJourVoltApiBaseUrl(
            rawBaseUrl,
            allowLocalHttp = BuildConfig.DEBUG && BuildConfig.JOURVOLT_MOCK_LOGIN
        )
            ?.trimEnd('/')
            ?: run {
                sessionStore.clear()
                return@withLock null
            }
        val body = JSONObject().put("refresh_token", current.refreshToken)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/v1/session/refresh")
            .post(body)
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                val access = json.optString("access_token").takeIf { it.isNotBlank() }
                val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
                val expiresIn = json.optLong("expires_in", 0L)
                if (access == null || refresh == null || expiresIn <= 0L) return@use null
                sessionStore.save(access, refresh, expiresIn, current.userId)
                access
            }
        }.getOrNull() ?: run {
            sessionStore.clear()
            null
        }
    }
}

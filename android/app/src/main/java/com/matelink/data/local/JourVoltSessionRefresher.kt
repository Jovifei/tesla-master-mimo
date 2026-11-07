package com.matelink.data.local

import com.matelink.BuildConfig
import com.matelink.data.api.validatedJourVoltApiBaseUrl
import com.matelink.data.api.credentialHttpClientBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
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
    private data class Rotation(val oldAccess: String, val newAccess: String, val generation: Long)

    private val refreshMutex = Mutex()
    @Volatile private var recentRotation: Rotation? = null
    private val client = credentialHttpClientBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Returns a new/current access token when rotation succeeds.
     *
     * Crucially, transient network/server/configuration failures do not destroy
     * the local session. Only an explicit 401/403 from the refresh endpoint is
     * authoritative proof that the stored cloud session can no longer rotate.
     */
    suspend fun refreshIfCurrent(staleAccessToken: String): String? = refreshMutex.withLock {
        val (current, generation) = sessionStore.identitySnapshot()
        current ?: return@withLock null
        if (current.accessToken != staleAccessToken) {
            return@withLock recentRotation?.takeIf {
                it.oldAccess == staleAccessToken && it.newAccess == current.accessToken &&
                    it.generation == generation
            }?.newAccess
        }

        val rawBaseUrl = if (BuildConfig.DEBUG && BuildConfig.JOURVOLT_MOCK_LOGIN) {
            BuildConfig.JOURVOLT_MOCK_BASE_URL
        } else {
            BuildConfig.JOURVOLT_API_BASE_URL
        }
        val baseUrl = validatedJourVoltApiBaseUrl(
            rawBaseUrl,
            allowLocalHttp = BuildConfig.DEBUG && BuildConfig.JOURVOLT_MOCK_LOGIN
        )?.trimEnd('/') ?: return@withLock null

        val body = JSONObject().put("refresh_token", current.refreshToken)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/v1/session/refresh")
            .post(body)
            .build()

        return@withLock try {
            client.newCall(request).execute().use { response ->
                currentCoroutineContext().ensureActive()
                if (!response.isSuccessful) {
                    if (shouldClearSessionAfterRefreshFailure(response.code)) {
                        sessionStore.clearIfCurrent(current, generation)
                    }
                    return@use null
                }
                val json = runCatching {
                    JSONObject(response.body?.string().orEmpty())
                }.getOrNull() ?: return@use null
                currentCoroutineContext().ensureActive()
                val access = json.optString("access_token").takeIf { it.isNotBlank() }
                val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
                val expiresIn = json.optLong("expires_in", 0L)
                if (access == null || refresh == null || expiresIn <= 0L) return@use null
                if (!sessionStore.rotateIfCurrent(current, generation, access, refresh, expiresIn)) return@use null
                val (updated, updatedGeneration) = sessionStore.identitySnapshot()
                if (updated?.accessToken != access || updated.userId != current.userId) return@use null
                recentRotation = Rotation(staleAccessToken, access, updatedGeneration)
                access.takeIf { sessionStore.currentIdentityGeneration() == updatedGeneration }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

internal fun shouldClearSessionAfterRefreshFailure(httpStatus: Int): Boolean =
    httpStatus == 401 || httpStatus == 403

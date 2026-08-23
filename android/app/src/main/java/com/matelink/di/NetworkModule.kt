package com.matelink.di

import com.matelink.BuildConfig
import com.matelink.data.api.NominatimApi
import com.matelink.data.api.NominatimResult
import com.matelink.data.api.OpenMeteoApi
import com.matelink.data.api.OpenMeteoWeatherResponse
import com.matelink.data.api.TeslamateApi
import com.matelink.data.api.UrlSecurity
import com.matelink.data.local.ConnectionMode
import com.matelink.data.local.ConnectionModeStore
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.JourVoltSessionStore
import com.matelink.data.local.JourVoltSessionRefresher
import com.matelink.data.repository.ConnectionUrlValidation
import com.matelink.data.repository.validateConnectionUrl
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val USER_AGENT = "MateLink/${BuildConfig.VERSION_NAME}"

    private val userAgentInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("User-Agent", USER_AGENT)
            .build()
        chain.proceed(request)
    }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder()
            .build()
    }

    @Provides
    @Singleton
    fun provideTeslamateApiFactory(
        settingsDataStore: SettingsDataStore,
        jourVoltSessionStore: JourVoltSessionStore,
        jourVoltSessionRefresher: JourVoltSessionRefresher,
        connectionModeStore: ConnectionModeStore,
        moshi: Moshi
    ): TeslamateApiFactory {
        return TeslamateApiFactory(
            settingsDataStore,
            jourVoltSessionStore,
            jourVoltSessionRefresher,
            connectionModeStore,
            moshi
        )
    }

    @Provides
    @Singleton
    fun provideNominatimApi(
        moshi: Moshi,
        connectionModeStore: ConnectionModeStore
    ): NominatimApi {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        val delegate = Retrofit.Builder()
            .baseUrl("https://nominatim.openstreetmap.org/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(NominatimApi::class.java)

        return object : NominatimApi {
            override suspend fun reverseGeocode(
                latitude: Double,
                longitude: Double
            ): retrofit2.Response<NominatimResult?> {
                if (connectionModeStore.current() != ConnectionMode.SELF_HOSTED) {
                    return unavailableExternalService()
                }
                return delegate.reverseGeocode(latitude, longitude)
            }

            override suspend fun searchCountryBoundary(
                countryCode: String,
                query: String
            ): retrofit2.Response<List<NominatimResult>> {
                if (connectionModeStore.current() != ConnectionMode.SELF_HOSTED) {
                    return unavailableExternalService()
                }
                return delegate.searchCountryBoundary(countryCode, query)
            }
        }
    }

    @Provides
    @Singleton
    fun provideOpenMeteoApi(
        moshi: Moshi,
        connectionModeStore: ConnectionModeStore
    ): OpenMeteoApi {
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(userAgentInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val delegate = Retrofit.Builder()
            .baseUrl("https://archive-api.open-meteo.com/")
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenMeteoApi::class.java)

        return object : OpenMeteoApi {
            override suspend fun getHistoricalWeather(
                latitude: Double,
                longitude: Double,
                startDate: String,
                endDate: String
            ): retrofit2.Response<OpenMeteoWeatherResponse> {
                if (connectionModeStore.current() != ConnectionMode.SELF_HOSTED) {
                    return unavailableExternalService()
                }
                return delegate.getHistoricalWeather(latitude, longitude, startDate, endDate)
            }
        }
    }
}

private fun <T> unavailableExternalService(): retrofit2.Response<T> {
    throw IllegalStateException("External coordinate services are disabled in JourVolt")
}

/**
 * Cache key for API instances, combining URL and security settings.
 */
private data class ApiCacheKey(
    val baseUrl: String,
    val apiToken: String
)

internal fun normalizedApiToken(value: String): String = value.trim()

/**
 * Factory for creating TeslamateApi instances with caching support.
 *
 * Supports caching multiple API instances (e.g., for primary and secondary servers)
 * to avoid recreating clients when switching between servers during fallback.
 */
class TeslamateApiFactory(
    private val settingsDataStore: SettingsDataStore,
    private val jourVoltSessionStore: JourVoltSessionStore,
    private val jourVoltSessionRefresher: JourVoltSessionRefresher,
    private val connectionModeStore: ConnectionModeStore,
    private val moshi: Moshi
) {
    // Cache multiple API instances keyed by their configuration
    private val apiCache = mutableMapOf<ApiCacheKey, TeslamateApi>()

    /**
     * Creates or returns a cached TeslamateApi instance for the given URL.
     *
     * @param baseUrl The base URL for the API
     * @param acceptInvalidCerts Override for accepting invalid certificates. If null, uses the setting from DataStore.
     * @return A TeslamateApi instance configured for the given URL
     */
    suspend fun create(
        baseUrl: String,
        @Suppress("UNUSED_PARAMETER") acceptInvalidCerts: Boolean? = null,
        apiTokenOverride: String? = null
    ): TeslamateApi {
        val cloudMode = connectionModeStore.mode.first() == ConnectionMode.TESLA_CLOUD
        val requestedUrl = if (cloudMode) {
            BuildConfig.JOURVOLT_API_BASE_URL
        } else {
            baseUrl
        }
        val validatedUrl = when (val validation = validateConnectionUrl(requestedUrl)) {
            is ConnectionUrlValidation.Valid -> validation.normalizedUrl
            is ConnectionUrlValidation.Invalid -> throw IllegalArgumentException("Invalid API root URL")
        }
        if (UrlSecurity.classify(validatedUrl) == UrlSecurity.Verdict.Unsafe) {
            throw IllegalArgumentException("Public HTTP is not secure")
        }
        val urlVerdict = UrlSecurity.classify(validatedUrl)
        val allowsDebugLocalHttp = BuildConfig.DEBUG && urlVerdict == UrlSecurity.Verdict.LocalHttp
        if (cloudMode && urlVerdict != UrlSecurity.Verdict.Https && !allowsDebugLocalHttp) {
            throw IllegalArgumentException("JourVolt cloud API must use HTTPS")
        }
        val normalizedUrl = validatedUrl + "/"
        val settings = settingsDataStore.settings.first()
        val apiToken = if (cloudMode) {
            normalizedApiToken(jourVoltSessionStore.current()?.accessToken.orEmpty())
        } else {
            normalizedApiToken(apiTokenOverride ?: settings.apiToken)
        }

        val cacheKey = ApiCacheKey(normalizedUrl, apiToken)

        // Return cached API if available
        apiCache[cacheKey]?.let { return it }

        // Create new API instance
        val okHttpClient = createOkHttpClient(apiToken, cloudMode)

        val api = Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TeslamateApi::class.java)

        // Cache the API instance
        apiCache[cacheKey] = api

        // Limit cache size to prevent memory leaks (keep last 4 configurations)
        if (apiCache.size > 4) {
            val oldestKey = apiCache.keys.first()
            apiCache.remove(oldestKey)
        }

        return api
    }

    /**
     * Invalidates all cached API instances.
     * Call this when settings change that require recreating the API clients.
     */
    fun invalidateCache() {
        apiCache.clear()
    }

    private fun createOkHttpClient(
        apiToken: String,
        cloudMode: Boolean
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                    .header("User-Agent", "MateLink/${BuildConfig.VERSION_NAME}")
                val currentToken = if (cloudMode) {
                    jourVoltSessionStore.current()?.accessToken.orEmpty()
                } else {
                    apiToken
                }
                if (currentToken.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer $currentToken")
                }
                chain.proceed(requestBuilder.build())
            }
            .authenticator { _, response ->
                val request = response.request
                if (!cloudMode || request.header("X-JourVolt-Retry") != null) {
                    return@authenticator null
                }
                val staleToken = request.header("Authorization")
                    ?.removePrefix("Bearer ")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@authenticator null
                val refreshed = kotlinx.coroutines.runBlocking {
                    jourVoltSessionRefresher.refreshIfCurrent(staleToken)
                }
                refreshed?.let {
                    request.newBuilder()
                        .header("Authorization", "Bearer $it")
                        .header("X-JourVolt-Retry", "1")
                        .build()
                }
            }
            .connectTimeout(if (cloudMode) 10 else 1, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        // Only add logging in debug builds, and use HEADERS level to avoid OOM
        // with large response bodies (drive details can be 15MB+)
        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
                // Keep credentials (Bearer token / Basic auth) out of logcat
                redactHeader("Authorization")
            }
            builder.addInterceptor(loggingInterceptor)
        }

        return builder.build()
    }
}

package com.matelink.data.api

import com.matelink.data.local.SettingsDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApiClient @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cached values - updated via Flow collection, no runBlocking needed
    @Volatile private var cachedToken: String = ""
    @Volatile private var cachedBaseUrl: String = ""
    @Volatile private var cachedApi: TeslamateApi? = null

    private val _isConfigured = MutableStateFlow(false)
    val isConfigured: StateFlow<Boolean> = _isConfigured.asStateFlow()

    init {
        // Collect settings changes and update cache
        scope.launch {
            settingsDataStore.settings
                .map { it.serverUrl }
                .distinctUntilChanged()
                .collect { url ->
                    cachedBaseUrl = url
                    synchronized(this@ApiClient) { cachedApi = null } // Force recreate
                    _isConfigured.value = url.isNotBlank()
                }
        }
        scope.launch {
            settingsDataStore.settings
                .map { it.apiToken }
                .distinctUntilChanged()
                .collect { token ->
                    cachedToken = token
                }
        }
    }

    val api: TeslamateApi
        get() = synchronized(this) {
            if (cachedApi == null) {
                cachedApi = createApi(cachedBaseUrl)
            }
            cachedApi!!
        }

    private fun createApi(baseUrl: String): TeslamateApi {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC // Don't log request/response bodies to avoid leaking tokens
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = cachedToken // Read from cached value, not Flow
                val request = chain.request().newBuilder().apply {
                    if (token.isNotBlank()) {
                        addHeader("Authorization", "Bearer $token")
                    }
                }.build()
                chain.proceed(request)
            }
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // Handle 401/403 - could trigger re-auth flow
                if (response.code == 401 || response.code == 403) {
                    // TODO: Implement token refresh or redirect to login
                }
                response
            }
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS) // Longer for large data sets
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        // 安全校验：非空 baseUrl 必须安全（HTTPS 或 LAN/localhost），否则拒绝创建会泄露 token 的客户端
        if (baseUrl.isNotBlank() && !UrlSecurity.isSafe(baseUrl)) {
            throw IllegalArgumentException(
                "Refusing to create API client: baseUrl uses cleartext HTTP to a public host " +
                "(token would be exposed). Use HTTPS or a local/private address. baseUrl=$baseUrl"
            )
        }

        return Retrofit.Builder()
            .baseUrl(url.ifBlank { "http://localhost/" }) // Fallback for initial creation
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TeslamateApi::class.java)
    }

    fun updateBaseUrl(url: String) {
        scope.launch {
            settingsDataStore.saveServerUrl(url)
        }
    }

    fun updateToken(token: String) {
        scope.launch {
            settingsDataStore.saveApiToken(token)
        }
    }
}

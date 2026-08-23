package com.matelink.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.BuildConfig
import com.matelink.data.local.ConnectionMode
import com.matelink.data.local.ConnectionModeStore
import com.matelink.data.local.JourVoltSessionStore
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.POST
import javax.inject.Inject

private interface DebugMockApi {
    @POST("v1/dev/mock-login")
    suspend fun login(): Response<DebugMockLoginResponse>
}

@JsonClass(generateAdapter = true)
internal data class DebugMockLoginResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "expires_in") val expiresIn: Long,
    val user: DebugMockUser
)

@JsonClass(generateAdapter = true)
internal data class DebugMockUser(val id: String)

data class DebugMockState(
    val loading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DebugMockLoginViewModel @Inject constructor(
    private val moshi: Moshi,
    private val sessionStore: JourVoltSessionStore,
    private val connectionModeStore: ConnectionModeStore
) : ViewModel() {
    private val _state = MutableStateFlow(DebugMockState())
    val state: StateFlow<DebugMockState> = _state
    private var loginJob: Job? = null
    @Volatile
    private var requestGeneration: Long = 0L

    fun login(onSuccess: () -> Unit) {
        val requestId = ++requestGeneration
        loginJob?.cancel()
        _state.value = DebugMockState(loading = true)
        loginJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                check(BuildConfig.JOURVOLT_MOCK_LOGIN) { "Mock login is not available in this build" }
                val baseUrl = BuildConfig.JOURVOLT_MOCK_BASE_URL.trim().let {
                    if (it.endsWith('/')) it else "$it/"
                }
                val response = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                    .create(DebugMockApi::class.java)
                    .login()
                val body = response.body()
                check(response.isSuccessful && body != null) { "HTTP ${response.code()}" }
                if (!shouldPublishTeslaRequest(requestId, requestGeneration)) {
                    return@runCatching
                }
                sessionStore.save(body.accessToken, body.refreshToken, body.expiresIn, body.user.id)
                connectionModeStore.set(ConnectionMode.TESLA_CLOUD)
            }.onSuccess {
                if (shouldPublishTeslaRequest(requestId, requestGeneration)) {
                    withContext(Dispatchers.Main.immediate) {
                        if (shouldPublishTeslaRequest(requestId, requestGeneration)) {
                            _state.value = DebugMockState()
                            onSuccess()
                        }
                    }
                }
            }.onFailure { error ->
                if (shouldPublishTeslaRequest(requestId, requestGeneration) &&
                    error !is CancellationException
                ) {
                    _state.value = DebugMockState(error = "Mock login failed: ${error.message}")
                }
            }
        }
    }
}

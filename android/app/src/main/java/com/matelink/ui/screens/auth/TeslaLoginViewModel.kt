package com.matelink.ui.screens.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.BuildConfig
import com.matelink.data.api.validatedJourVoltApiBaseUrl
import com.matelink.data.local.ConnectionMode
import com.matelink.data.local.ConnectionModeStore
import com.matelink.data.local.JourVoltConsentStore
import com.matelink.data.local.JourVoltSessionStore
import com.matelink.R
import com.squareup.moshi.Moshi
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.Locale
import javax.inject.Inject

sealed interface TeslaLoginUiState {
    data object Idle : TeslaLoginUiState
    data object Loading : TeslaLoginUiState
    data class Error(val message: String) : TeslaLoginUiState
}

@HiltViewModel
class TeslaLoginViewModel @Inject constructor(
    private val moshi: Moshi,
    private val sessionStore: JourVoltSessionStore,
    private val connectionModeStore: ConnectionModeStore,
    private val consentStore: JourVoltConsentStore,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow<TeslaLoginUiState>(TeslaLoginUiState.Idle)
    val uiState: StateFlow<TeslaLoginUiState> = _uiState.asStateFlow()
    val isAuthenticated: StateFlow<Boolean> = sessionStore.session
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, sessionStore.current() != null)
    val hasCurrentConsent: StateFlow<Boolean> = consentStore.consent
        .map { it?.isCurrent == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var requestJob: Job? = null
    @Volatile
    private var requestGeneration: Long = 0L
    @Volatile
    private var callbackTicketInFlight: String? = null
    @Volatile
    private var handledCallbackTicket: String? = null

    fun startTeslaLogin(termsAccepted: Boolean, privacyAccepted: Boolean) {
        if (!BuildConfig.JOURVOLT_CLOUD_LOGIN) {
            _uiState.value = TeslaLoginUiState.Error(
                context.getString(R.string.tesla_login_error_not_configured)
            )
            return
        }
        if (!termsAccepted || !privacyAccepted) {
            _uiState.value = TeslaLoginUiState.Error(
                context.getString(R.string.tesla_login_consent_required)
            )
            return
        }
        val requestId = beginRequest()
        callbackTicketInFlight = null
        requestJob?.cancel()
        _uiState.value = TeslaLoginUiState.Loading
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val consent = consentStore.recordCurrent()
                val response = authApi().startAuthorization(
                    termsVersion = consent.termsVersion,
                    privacyVersion = consent.privacyVersion
                )
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    error(context.getString(teslaLoginErrorMessageRes(response.code())))
                }
                if (!isTrustedTeslaAuthorizationUrl(
                        body.authorizationUrl,
                        BuildConfig.JOURVOLT_API_BASE_URL
                    )
                ) {
                    error(context.getString(R.string.tesla_login_authorization_invalid))
                }
                if (shouldPublishTeslaRequest(requestId, requestGeneration)) {
                    withContext(Dispatchers.Main) {
                        if (shouldPublishTeslaRequest(requestId, requestGeneration)) {
                            CustomTabsIntent.Builder()
                                .setShowTitle(true)
                                .build()
                                .also { it.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                .launchUrl(context, Uri.parse(body.authorizationUrl))
                        }
                    }
                }
                if (shouldPublishTeslaRequest(requestId, requestGeneration)) {
                    _uiState.value = TeslaLoginUiState.Idle
                }
            }.onFailure { error ->
                if (shouldPublishTeslaRequest(requestId, requestGeneration) &&
                    error !is CancellationException
                ) {
                    _uiState.value = TeslaLoginUiState.Error(
                        error.message ?: context.getString(R.string.tesla_login_error_unavailable)
                    )
                }
            }
        }
    }

    fun handleAuthorizationCallback(intent: Intent?) {
        val callback = intent?.data ?: return
        if (!isTrustedTeslaCallback(callback, BuildConfig.JOURVOLT_AUTH_HOST)) {
            _uiState.value = TeslaLoginUiState.Error(
                context.getString(R.string.tesla_login_callback_invalid)
            )
            return
        }
        callback.getQueryParameter("error")?.takeIf { it.isNotBlank() }?.let {
            _uiState.value = TeslaLoginUiState.Error(
                context.getString(R.string.tesla_login_error_cancelled)
            )
            return
        }
        val ticket = callback.getQueryParameter("ticket")?.takeIf { it.isNotBlank() }
            ?: return
        if (shouldIgnoreTeslaCallbackTicket(
                ticket = ticket,
                inFlightTicket = callbackTicketInFlight,
                handledTicket = handledCallbackTicket
            )
        ) {
            return
        }
        val requestId = beginRequest()
        callbackTicketInFlight = ticket

        requestJob?.cancel()
        _uiState.value = TeslaLoginUiState.Loading
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val response = authApi().exchange(TeslaAuthExchangeRequest(ticket))
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    error(context.getString(teslaLoginErrorMessageRes(response.code())))
                }
                if (!shouldPublishTeslaRequest(requestId, requestGeneration)) {
                    return@runCatching
                }
                sessionStore.save(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken,
                    expiresInSeconds = body.expiresIn,
                    userId = body.user.id
                )
                connectionModeStore.set(ConnectionMode.TESLA_CLOUD)
                handledCallbackTicket = ticket
                callbackTicketInFlight = null
                if (shouldPublishTeslaRequest(requestId, requestGeneration)) {
                    _uiState.value = TeslaLoginUiState.Idle
                }
            }.onFailure { error ->
                if (callbackTicketInFlight == ticket &&
                    shouldPublishTeslaRequest(requestId, requestGeneration) &&
                    error !is CancellationException
                ) {
                    callbackTicketInFlight = null
                    _uiState.value = TeslaLoginUiState.Error(
                        error.message ?: context.getString(R.string.tesla_login_error_exchange)
                    )
                }
            }
        }
    }

    fun openSelfHosted(onComplete: () -> Unit) {
        val requestId = beginRequest()
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            if (shouldPublishTeslaRequest(requestId, requestGeneration)) {
                connectionModeStore.set(ConnectionMode.SELF_HOSTED)
                onComplete()
            }
        }
    }

    fun logout(onComplete: () -> Unit) {
        val session = sessionStore.current()
        beginRequest()
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            if (session != null) {
                withTimeoutOrNull(3_000L) {
                    runCatching { authApi().logout("Bearer ${session.accessToken}") }
                }
            }
            sessionStore.clear()
            _uiState.value = TeslaLoginUiState.Idle
            withContext(Dispatchers.Main.immediate) { onComplete() }
            requestJob = null
        }
    }

    fun reauthorize(onReady: () -> Unit) {
        logout {
            onReady()
        }
    }

    fun deleteAccount(onSuccess: (String?) -> Unit, onFailure: () -> Unit) {
        val session = sessionStore.current()
        if (session == null) {
            onSuccess(null)
            return
        }
        val requestId = beginRequest()
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            var deletionResponse: TeslaAccountDeletionResponse? = null
            val deleted = withTimeoutOrNull(10_000L) {
                runCatching {
                    authApi().deleteAccount("Bearer ${session.accessToken}").also { response ->
                        if (response.isSuccessful) deletionResponse = response.body()
                    }.isSuccessful
                }.getOrDefault(false)
            } ?: false
            if (deleted && shouldPublishTeslaRequest(requestId, requestGeneration)) {
                sessionStore.clear()
                consentStore.clear()
                _uiState.value = TeslaLoginUiState.Idle
                withContext(Dispatchers.Main.immediate) {
                    onSuccess(deletionResponse?.teslaConsentRevokeUrl)
                }
            } else if (shouldPublishTeslaRequest(requestId, requestGeneration)) {
                withContext(Dispatchers.Main.immediate) { onFailure() }
            }
            requestJob = null
        }
    }

    private fun authApi(): TeslaAuthApi {
        val usesDebugMockBaseUrl = BuildConfig.JOURVOLT_MOCK_LOGIN
        val rawBaseUrl = if (usesDebugMockBaseUrl) {
            BuildConfig.JOURVOLT_MOCK_BASE_URL
        } else {
            BuildConfig.JOURVOLT_API_BASE_URL
        }
        val baseUrl = validatedJourVoltApiBaseUrl(
            raw = rawBaseUrl,
            allowLocalHttp = usesDebugMockBaseUrl
        )
            ?: error("JourVolt cloud API must be an HTTPS root URL")
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TeslaAuthApi::class.java)
    }

    private fun beginRequest(): Long {
        requestGeneration += 1
        return requestGeneration
    }
}

internal fun teslaLoginErrorMessageRes(code: Int): Int = when (code) {
    400 -> R.string.tesla_login_error_request
    401, 403 -> R.string.tesla_login_error_authorization
    429 -> R.string.tesla_login_error_rate_limit
    in 500..599 -> R.string.tesla_login_error_service
    else -> R.string.tesla_login_error_generic
}

internal fun shouldIgnoreTeslaCallbackTicket(
    ticket: String,
    inFlightTicket: String?,
    handledTicket: String?
): Boolean = ticket.isBlank() || ticket == inFlightTicket || ticket == handledTicket

internal fun shouldPublishTeslaRequest(requestId: Long, currentRequestId: Long): Boolean =
    requestId == currentRequestId

internal fun isTrustedTeslaConsentRevokeUrl(raw: String): Boolean {
    val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return false
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    val hasClientId = uri.rawQuery.orEmpty().split('&').any { parameter ->
        parameter.substringBefore('=') == "revoke_client_id" &&
            parameter.substringAfter('=', "").isNotBlank()
    }
    return uri.scheme.equals("https", ignoreCase = true) &&
        host in setOf("auth.tesla.com", "auth.tesla.cn") &&
        uri.path == "/user/revoke/consent" &&
        hasClientId
}

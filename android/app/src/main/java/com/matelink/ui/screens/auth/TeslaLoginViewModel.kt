package com.matelink.ui.screens.auth

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.BuildConfig
import com.matelink.data.api.models.TelemetryPairingStatus
import com.matelink.data.api.validatedJourVoltApiBaseUrl
import com.matelink.data.local.ConnectionMode
import com.matelink.data.local.ConnectionModeStore
import com.matelink.data.local.JourVoltConsentStore
import com.matelink.data.local.JourVoltSessionStore
import com.matelink.data.local.TeslaOnboardingPhase
import com.matelink.data.local.TeslaOnboardingSnapshot
import com.matelink.data.local.TeslaOnboardingStateStore
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TeslamateRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject

sealed interface TeslaLoginUiState {
    data object Idle : TeslaLoginUiState
    data object Loading : TeslaLoginUiState
    data class Error(val message: String) : TeslaLoginUiState
}

sealed interface TeslaLoginOnboardingState {
    data object Idle : TeslaLoginOnboardingState
    data object Checking : TeslaLoginOnboardingState
    data class PairingRequired(
        val carId: Int,
        val virtualKeyUrl: String?
    ) : TeslaLoginOnboardingState
    data class PermissionRequired(val carId: Int?, val reason: String? = null) : TeslaLoginOnboardingState
    data class Blocked(val reason: String? = null) : TeslaLoginOnboardingState
    data object Pending : TeslaLoginOnboardingState
    data object Ready : TeslaLoginOnboardingState
}

internal fun shouldSurfaceTeslaVirtualKey(pairing: TelemetryPairingStatus): Boolean =
    pairing.configSynced != true && pairing.status.equals("pairing_required", ignoreCase = true)

@HiltViewModel
class TeslaLoginViewModel @Inject constructor(
    private val moshi: Moshi,
    private val sessionStore: JourVoltSessionStore,
    private val connectionModeStore: ConnectionModeStore,
    private val consentStore: JourVoltConsentStore,
    private val teslamateRepository: TeslamateRepository,
    private val settingsRepository: SettingsRepository,
    private val onboardingStateStore: TeslaOnboardingStateStore,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow<TeslaLoginUiState>(TeslaLoginUiState.Idle)
    val uiState: StateFlow<TeslaLoginUiState> = _uiState.asStateFlow()
    private val _pendingAuthorizationUrl = MutableStateFlow<String?>(null)
    val pendingAuthorizationUrl: StateFlow<String?> = _pendingAuthorizationUrl.asStateFlow()
    private val _openDashboardAfterLogin = MutableStateFlow(false)
    val openDashboardAfterLogin: StateFlow<Boolean> = _openDashboardAfterLogin.asStateFlow()
    private val _revealLoginError = MutableStateFlow(false)
    val revealLoginError: StateFlow<Boolean> = _revealLoginError.asStateFlow()
    private val _reauthorizing = MutableStateFlow(false)
    val reauthorizing: StateFlow<Boolean> = _reauthorizing.asStateFlow()
    private val _postLoginOnboarding = MutableStateFlow<TeslaLoginOnboardingState>(TeslaLoginOnboardingState.Idle)
    val postLoginOnboarding: StateFlow<TeslaLoginOnboardingState> = _postLoginOnboarding.asStateFlow()
    private val _teslaPairingFlowPending = MutableStateFlow(false)
    val teslaPairingFlowPending: StateFlow<Boolean> = _teslaPairingFlowPending.asStateFlow()
    private val _onboardingStateRestored = MutableStateFlow(false)
    val onboardingStateRestored: StateFlow<Boolean> = _onboardingStateRestored.asStateFlow()
    val isAuthenticated: StateFlow<Boolean> = sessionStore.session
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, sessionStore.current() != null)
    val hasCurrentConsent: StateFlow<Boolean> = consentStore.consent
        .map { it?.isCurrent == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var requestJob: Job? = null
    private val requestGeneration = AtomicLong(0L)
    @Volatile
    private var callbackTicketInFlight: String? = null
    @Volatile
    private var handledCallbackTicket: String? = null
    private var pairingRetryUsed = false
    private var onboardingPersistenceJob: Job? = null
    private var onboardingRestoreJob: Job? = null
    private var externalPairingReturnPending = false
    private val sessionCommitLock = ReentrantLock()

    init {
        onboardingRestoreJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                restorePersistedOnboarding()
            } finally {
                _onboardingStateRestored.value = true
            }
        }
    }

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
        resetPostLoginOnboarding()
        val requestId = invalidateCurrentRequest()
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
                    _pendingAuthorizationUrl.value = body.authorizationUrl
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
        if (!isTeslaOAuthCallbackPath(callback.path)) {
            return
        }
        if (!isTrustedTeslaCallback(callback, BuildConfig.JOURVOLT_AUTH_HOST)) {
            _uiState.value = TeslaLoginUiState.Error(
                context.getString(R.string.tesla_login_callback_invalid)
            )
            _revealLoginError.value = true
            return
        }
        val ticket = callback.getQueryParameter("ticket")?.takeIf { it.isNotBlank() }
        val errorCode = callback.getQueryParameter("error")?.takeIf { it.isNotBlank() }
        if (ticket == null) {
            if (errorCode != null) {
                _uiState.value = TeslaLoginUiState.Error(
                    context.getString(teslaLoginCallbackErrorRes(errorCode))
                )
                _revealLoginError.value = true
            }
            return
        }
        when (
            teslaCallbackReplayDecision(
                ticket = ticket,
                inFlightTicket = callbackTicketInFlight,
                handledTicket = handledCallbackTicket,
                hasSession = sessionStore.current() != null
            )
        ) {
            TeslaCallbackReplayDecision.Ignore -> return
            TeslaCallbackReplayDecision.OpenDashboard -> {
                val requestId = invalidateCurrentRequest()
                requestJob?.cancel()
                _reauthorizing.value = false
                _uiState.value = TeslaLoginUiState.Idle
                _openDashboardAfterLogin.value = false
                publishOnboardingChecking()
                requestJob = viewModelScope.launch(Dispatchers.IO) {
                    runPostLoginOnboardingSafely(requestId)
                }
                return
            }
            TeslaCallbackReplayDecision.Process -> Unit
        }
        val requestId = invalidateCurrentRequest()
        callbackTicketInFlight = ticket

        requestJob?.cancel()
        _uiState.value = TeslaLoginUiState.Loading
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val response = authApi().exchange(TeslaAuthExchangeRequest(ticket))
                val body = response.body()
                if (!response.isSuccessful || body == null) {
                    if (shouldTreatTeslaExchangeFailureAsSuccess(
                            httpStatus = response.code(),
                            hasSession = sessionStore.current() != null
                        )
                    ) {
                        handledCallbackTicket = ticket
                        callbackTicketInFlight = null
                        _postLoginOnboarding.value = TeslaLoginOnboardingState.Checking
                        _reauthorizing.value = false
                        _uiState.value = TeslaLoginUiState.Idle
                        _openDashboardAfterLogin.value = false
                        runPostLoginOnboardingSafely(requestId)
                        return@runCatching
                    }
                    error(context.getString(teslaLoginErrorMessageRes(response.code())))
                }
                if (!shouldPublishTeslaRequest(requestId, requestGeneration)) {
                    return@runCatching
                }
                val committed = withContext(NonCancellable) {
                    sessionCommitLock.lock()
                    try {
                        if (!shouldPublishTeslaRequest(requestId, requestGeneration)) {
                            false
                        } else {
                            sessionStore.save(
                                accessToken = body.accessToken,
                                refreshToken = body.refreshToken,
                                expiresInSeconds = body.expiresIn,
                                userId = body.user.id
                            )
                            runBlocking { connectionModeStore.set(ConnectionMode.TESLA_CLOUD) }
                            handledCallbackTicket = ticket
                            callbackTicketInFlight = null
                            true
                        }
                    } finally {
                        sessionCommitLock.unlock()
                    }
                }
                if (!committed) return@runCatching
                _postLoginOnboarding.value = TeslaLoginOnboardingState.Checking
                _reauthorizing.value = false
                if (shouldPublishTeslaRequest(requestId, requestGeneration)) {
                    _uiState.value = TeslaLoginUiState.Idle
                    runPostLoginOnboardingSafely(requestId)
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
                    _revealLoginError.value = true
                }
            }
        }
    }

    fun consumePendingAuthorizationUrl() {
        _pendingAuthorizationUrl.value = null
    }

    fun consumeDashboardAfterLogin() {
        _openDashboardAfterLogin.value = false
    }

    fun consumeRevealLoginError() {
        _revealLoginError.value = false
    }

    fun openSelfHosted(onComplete: () -> Unit) {
        val requestId = invalidateCurrentRequest()
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
        _reauthorizing.value = false
        val requestId = invalidateCurrentRequest()
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            if (session != null) {
                withTimeoutOrNull(3_000L) {
                    try {
                        authApi().logout("Bearer ${session.accessToken}")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Local session cleanup still proceeds when the remote logout is unavailable.
                    }
                }
            }
            if (!shouldPublishTeslaRequest(requestId, requestGeneration) ||
                sessionStore.current() != session
            ) return@launch
            sessionStore.clear()
            resetPostLoginOnboarding()
            _uiState.value = TeslaLoginUiState.Idle
            withContext(Dispatchers.Main.immediate) { onComplete() }
        }
    }

    fun reauthorize(onReady: () -> Unit) {
        invalidateCurrentRequest()
        requestJob?.cancel()
        callbackTicketInFlight = null
        _pendingAuthorizationUrl.value = null
        resetPostLoginOnboarding()
        _reauthorizing.value = true
        _uiState.value = TeslaLoginUiState.Idle
        onReady()
    }

    fun cancelReauthorization() {
        val wasReauthorizing = _reauthorizing.value
        invalidateCurrentRequest()
        requestJob?.cancel()
        requestJob = null
        _reauthorizing.value = false
        callbackTicketInFlight = null
        _pendingAuthorizationUrl.value = null
        if (wasReauthorizing || sessionStore.current() == null) {
            resetPostLoginOnboarding()
        }
        _uiState.value = TeslaLoginUiState.Idle
    }

    fun markTeslaPairingFlowLaunched() {
        if (_postLoginOnboarding.value is TeslaLoginOnboardingState.PairingRequired &&
            !externalPairingReturnPending
        ) {
            externalPairingReturnPending = true
            _teslaPairingFlowPending.value = true
            persistCurrentOnboarding()
        }
    }

    fun onTeslaPairingFlowResumed() {
        if (!externalPairingReturnPending) return
        externalPairingReturnPending = false
        _teslaPairingFlowPending.value = false
        val pairing = _postLoginOnboarding.value as? TeslaLoginOnboardingState.PairingRequired
            ?: return
        val requestId = invalidateCurrentRequest()
        requestJob?.cancel()
        val shouldConfigure = !pairingRetryUsed
        pairingRetryUsed = true
        publishOnboardingChecking(pairing.carId, pairing.virtualKeyUrl)
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            if (shouldConfigure) retryTelemetryAfterPairingSafely(requestId, pairing.carId)
            else refreshPairingAfterReturnSafely(requestId, pairing.carId)
        }
    }

    fun continueAfterTeslaPairing() {
        if (_postLoginOnboarding.value is TeslaLoginOnboardingState.PairingRequired) {
            externalPairingReturnPending = false
            _teslaPairingFlowPending.value = false
            continueToDashboard()
        }
    }

    fun continueAfterTeslaOnboarding() {
        when (_postLoginOnboarding.value) {
            is TeslaLoginOnboardingState.PermissionRequired,
            is TeslaLoginOnboardingState.Blocked -> continueToDashboard()
            else -> Unit
        }
    }

    fun deleteAccount(onSuccess: (String?) -> Unit, onFailure: () -> Unit) {
        val session = sessionStore.current()
        if (session == null) {
            onSuccess(null)
            return
        }
        val requestId = invalidateCurrentRequest()
        requestJob?.cancel()
        requestJob = viewModelScope.launch(Dispatchers.IO) {
            var deletionResponse: TeslaAccountDeletionResponse? = null
            val deleted = withTimeoutOrNull(10_000L) {
                try {
                    authApi().deleteAccount("Bearer ${session.accessToken}").also { response ->
                        if (response.isSuccessful) deletionResponse = response.body()
                    }.isSuccessful
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            } ?: false
            if (deleted && shouldPublishTeslaRequest(requestId, requestGeneration) &&
                sessionStore.current() == session
            ) {
                sessionStore.clear()
                resetPostLoginOnboarding()
                consentStore.clear()
                _reauthorizing.value = false
                _uiState.value = TeslaLoginUiState.Idle
                withContext(Dispatchers.Main.immediate) {
                    onSuccess(deletionResponse?.teslaConsentRevokeUrl)
                }
            } else if (shouldPublishTeslaRequest(requestId, requestGeneration)) {
                withContext(Dispatchers.Main.immediate) { onFailure() }
            }
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

    private fun beginRequest(): Long = requestGeneration.incrementAndGet()

    private fun invalidateCurrentRequest(): Long {
        sessionCommitLock.lock()
        return try {
            requestGeneration.incrementAndGet()
        } finally {
            sessionCommitLock.unlock()
        }
    }

    private fun shouldPublishTeslaRequest(requestId: Long, currentRequest: AtomicLong): Boolean =
        requestId == currentRequest.get()

    private suspend fun restorePersistedOnboarding() {
        val snapshot = onboardingStateStore.state.first()
        val session = sessionStore.current()
        if (snapshot.phase == TeslaOnboardingPhase.IDLE) return
        if (session?.userId != snapshot.accountId) {
            onboardingStateStore.save(TeslaOnboardingSnapshot())
            return
        }
        pairingRetryUsed = snapshot.retryUsed
        externalPairingReturnPending = snapshot.launchPending
        _teslaPairingFlowPending.value = snapshot.launchPending
        when (snapshot.phase) {
            TeslaOnboardingPhase.CHECKING -> {
                _postLoginOnboarding.value = TeslaLoginOnboardingState.Checking
                val requestId = beginRequest()
                requestJob = viewModelScope.launch(Dispatchers.IO) {
                    runPostLoginOnboardingSafely(requestId)
                }
            }
            TeslaOnboardingPhase.PAIRING_REQUIRED -> {
                val carId = snapshot.vehicleId
                if (carId == null) {
                    _postLoginOnboarding.value = TeslaLoginOnboardingState.Checking
                    val requestId = beginRequest()
                    requestJob = viewModelScope.launch(Dispatchers.IO) {
                        runPostLoginOnboardingSafely(requestId)
                    }
                } else {
                    _postLoginOnboarding.value = TeslaLoginOnboardingState.PairingRequired(
                        carId = carId,
                        virtualKeyUrl = snapshot.virtualKeyUrl
                    )
                    if (snapshot.launchPending) {
                        withContext(Dispatchers.Main.immediate) { onTeslaPairingFlowResumed() }
                    }
                }
            }
            TeslaOnboardingPhase.PERMISSION_REQUIRED -> {
                _postLoginOnboarding.value = TeslaLoginOnboardingState.PermissionRequired(
                    carId = snapshot.vehicleId,
                    reason = "permission_required"
                )
            }
            TeslaOnboardingPhase.BLOCKED -> {
                _postLoginOnboarding.value = TeslaLoginOnboardingState.Blocked("billing_blocked")
            }
            TeslaOnboardingPhase.PENDING -> {
                _postLoginOnboarding.value = TeslaLoginOnboardingState.Checking
                val requestId = beginRequest()
                requestJob = viewModelScope.launch(Dispatchers.IO) {
                    runPostLoginOnboardingSafely(requestId)
                }
            }
            TeslaOnboardingPhase.READY -> _postLoginOnboarding.value = TeslaLoginOnboardingState.Ready
            TeslaOnboardingPhase.IDLE -> Unit
        }
    }

    private suspend fun runPostLoginOnboardingSafely(requestId: Long) {
        try {
            runPostLoginOnboarding(requestId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            proceedToDashboardIfCurrent(requestId)
        }
    }

    private suspend fun runPostLoginOnboarding(requestId: Long) {
        if (!shouldPublishTeslaRequest(requestId, requestGeneration)) return
        publishOnboardingChecking()
        val carsResult = teslamateRepository.getCars()
        val cars = when (carsResult) {
            is ApiResult.Success -> carsResult.data
            is ApiResult.Error -> {
                if (isPermissionRequired(carsResult)) {
                    publishPermissionRequired(null, carsResult.details, requestId)
                } else {
                    proceedToDashboardIfCurrent(requestId)
                }
                return
            }
        }
        if (!shouldPublishTeslaRequest(requestId, requestGeneration)) return
        val selectedCarId = settingsRepository.currentCarId.first()
        val car = cars.firstOrNull { it.carId == selectedCarId } ?: cars.firstOrNull()
        if (car == null) {
            proceedToDashboardIfCurrent(requestId)
            return
        }
        publishPairingResult(requestId, car.carId, teslamateRepository.getTelemetryPairingStatus(car.carId))
    }

    private suspend fun retryTelemetryAfterPairingSafely(requestId: Long, carId: Int) {
        try {
            retryTelemetryAfterPairing(requestId, carId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            proceedToDashboardIfCurrent(requestId)
        }
    }

    private suspend fun retryTelemetryAfterPairing(requestId: Long, carId: Int) {
        if (!shouldPublishTeslaRequest(requestId, requestGeneration)) return
        val carsResult = teslamateRepository.getCars()
        val cars = when (carsResult) {
            is ApiResult.Success -> carsResult.data
            is ApiResult.Error -> {
                if (isPermissionRequired(carsResult)) {
                    publishPermissionRequired(carId, carsResult.details, requestId)
                } else {
                    proceedToDashboardIfCurrent(requestId)
                }
                return
            }
        }
        if (!shouldPublishTeslaRequest(requestId, requestGeneration) || cars.none { it.carId == carId }) {
            proceedToDashboardIfCurrent(requestId)
            return
        }
        val configure = teslamateRepository.configureTelemetry(carId)
        if (!shouldPublishTeslaRequest(requestId, requestGeneration)) return
        when (configure) {
            is ApiResult.Error -> when {
                isPermissionRequired(configure) -> {
                    publishPermissionRequired(carId, configure.details, requestId)
                    return
                }
                configure.details.equals("billing_blocked", ignoreCase = true) -> {
                    publishBlocked(configure.details, requestId)
                    return
                }
            }
            is ApiResult.Success -> Unit
        }
        refreshPairingAfterReturn(requestId, carId)
    }

    private suspend fun refreshPairingAfterReturnSafely(requestId: Long, carId: Int) {
        try {
            refreshPairingAfterReturn(requestId, carId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            proceedToDashboardIfCurrent(requestId)
        }
    }

    private suspend fun refreshPairingAfterReturn(requestId: Long, carId: Int) {
        if (!shouldPublishTeslaRequest(requestId, requestGeneration)) return
        publishPairingResult(requestId, carId, teslamateRepository.getTelemetryPairingStatus(carId))
    }

    private fun publishPairingResult(
        requestId: Long,
        carId: Int,
        result: ApiResult<com.matelink.data.api.models.TelemetryPairingStatus>
    ) {
        if (!shouldPublishTeslaRequest(requestId, requestGeneration)) return
        when (result) {
            is ApiResult.Success -> {
                val pairing = result.data
                when {
                    shouldSurfaceTeslaVirtualKey(pairing) -> {
                        externalPairingReturnPending = false
                        _teslaPairingFlowPending.value = false
                        _postLoginOnboarding.value = TeslaLoginOnboardingState.PairingRequired(
                            carId = carId,
                            virtualKeyUrl = pairing.virtualKeyUrl
                        )
                        persistOnboarding(TeslaOnboardingPhase.PAIRING_REQUIRED, carId, pairing.virtualKeyUrl)
                    }
                    isPermissionRequired(pairing) ->
                        publishPermissionRequired(carId, pairing.status, requestId)
                    pairing.status.equals("billing_blocked", ignoreCase = true) ->
                        publishBlocked(pairing.status, requestId)
                    pairing.configSynced == true -> completeOnboardingIfCurrent(requestId)
                    else -> proceedToDashboardIfCurrent(requestId)
                }
            }
            is ApiResult.Error -> when {
                isPermissionRequired(result) -> publishPermissionRequired(carId, result.details, requestId)
                result.details.equals("billing_blocked", ignoreCase = true) -> publishBlocked(result.details, requestId)
                result.details.equals("pairing_required", ignoreCase = true) -> {
                    if (!shouldPublishTeslaRequest(requestId, requestGeneration)) return
                    _postLoginOnboarding.value = TeslaLoginOnboardingState.PairingRequired(carId, null)
                    persistOnboarding(TeslaOnboardingPhase.PAIRING_REQUIRED, carId, null)
                }
                else -> proceedToDashboardIfCurrent(requestId)
            }
        }
    }

    private fun isPermissionRequired(pairing: TelemetryPairingStatus): Boolean =
        pairing.status.equals("permission_required", ignoreCase = true) ||
            pairing.errorClass.equals("permission_required", ignoreCase = true) ||
            pairing.errorClass.equals("reauthorization", ignoreCase = true)

    private fun isPermissionRequired(result: ApiResult.Error): Boolean =
        result.code == 401 || result.code == 403 ||
            result.details.equals("permission_required", ignoreCase = true) ||
            result.details.equals("reauthorization", ignoreCase = true)

    private fun publishOnboardingChecking(carId: Int? = null, virtualKeyUrl: String? = null) {
        _postLoginOnboarding.value = TeslaLoginOnboardingState.Checking
        _teslaPairingFlowPending.value = false
        persistOnboarding(TeslaOnboardingPhase.CHECKING, carId, virtualKeyUrl)
    }

    private fun publishPermissionRequired(carId: Int?, reason: String?, requestId: Long? = null) {
        if (requestId != null && !shouldPublishTeslaRequest(requestId, requestGeneration)) return
        externalPairingReturnPending = false
        _teslaPairingFlowPending.value = false
        _postLoginOnboarding.value = TeslaLoginOnboardingState.PermissionRequired(carId, reason)
        persistOnboarding(TeslaOnboardingPhase.PERMISSION_REQUIRED, carId, null)
    }

    private fun publishBlocked(reason: String?, requestId: Long? = null) {
        if (requestId != null && !shouldPublishTeslaRequest(requestId, requestGeneration)) return
        externalPairingReturnPending = false
        _teslaPairingFlowPending.value = false
        _postLoginOnboarding.value = TeslaLoginOnboardingState.Blocked(reason)
        persistOnboarding(TeslaOnboardingPhase.BLOCKED, null, null)
    }

    private fun proceedToDashboardIfCurrent(requestId: Long) {
        if (!shouldPublishTeslaRequest(requestId, requestGeneration)) return
        proceedToDashboard(pending = true)
    }

    private fun completeOnboardingIfCurrent(requestId: Long) {
        if (!shouldPublishTeslaRequest(requestId, requestGeneration)) return
        proceedToDashboard(pending = false)
    }

    private fun proceedToDashboard(pending: Boolean) {
        _postLoginOnboarding.value = if (pending) {
            TeslaLoginOnboardingState.Pending
        } else {
            TeslaLoginOnboardingState.Ready
        }
        _openDashboardAfterLogin.value = true
        persistOnboarding(if (pending) TeslaOnboardingPhase.PENDING else TeslaOnboardingPhase.READY)
    }

    private fun continueToDashboard() {
        proceedToDashboard(pending = true)
    }

    private fun persistCurrentOnboarding() {
        val state = _postLoginOnboarding.value
        val phase = when (state) {
            TeslaLoginOnboardingState.Idle -> TeslaOnboardingPhase.IDLE
            TeslaLoginOnboardingState.Checking -> TeslaOnboardingPhase.CHECKING
            is TeslaLoginOnboardingState.PairingRequired -> TeslaOnboardingPhase.PAIRING_REQUIRED
            is TeslaLoginOnboardingState.PermissionRequired -> TeslaOnboardingPhase.PERMISSION_REQUIRED
            is TeslaLoginOnboardingState.Blocked -> TeslaOnboardingPhase.BLOCKED
            TeslaLoginOnboardingState.Pending -> TeslaOnboardingPhase.PENDING
            TeslaLoginOnboardingState.Ready -> TeslaOnboardingPhase.READY
        }
        val carId = when (state) {
            is TeslaLoginOnboardingState.PairingRequired -> state.carId
            is TeslaLoginOnboardingState.PermissionRequired -> state.carId
            else -> null
        }
        val url = (state as? TeslaLoginOnboardingState.PairingRequired)?.virtualKeyUrl
        persistOnboarding(phase, carId, url)
    }

    private fun persistOnboarding(
        phase: TeslaOnboardingPhase,
        carId: Int? = null,
        virtualKeyUrl: String? = null
    ) {
        if (phase == TeslaOnboardingPhase.IDLE) {
            onboardingPersistenceJob?.cancel()
            onboardingPersistenceJob = viewModelScope.launch(Dispatchers.IO) {
                onboardingStateStore.save(TeslaOnboardingSnapshot())
            }
            return
        }
        val snapshot = TeslaOnboardingSnapshot(
            phase = phase,
            accountId = sessionStore.current()?.userId,
            vehicleId = carId,
            virtualKeyUrl = virtualKeyUrl,
            launchPending = _teslaPairingFlowPending.value,
            retryUsed = pairingRetryUsed
        )
        onboardingPersistenceJob?.cancel()
        onboardingPersistenceJob = viewModelScope.launch(Dispatchers.IO) {
            onboardingStateStore.save(snapshot)
        }
    }

    private fun resetPostLoginOnboarding() {
        onboardingRestoreJob?.cancel()
        _postLoginOnboarding.value = TeslaLoginOnboardingState.Idle
        _openDashboardAfterLogin.value = false
        externalPairingReturnPending = false
        _teslaPairingFlowPending.value = false
        pairingRetryUsed = false
        persistOnboarding(TeslaOnboardingPhase.IDLE)
    }

}

internal fun teslaLoginErrorMessageRes(code: Int): Int = when (code) {
    400 -> R.string.tesla_login_error_request
    401, 403 -> R.string.tesla_login_error_authorization
    429 -> R.string.tesla_login_error_rate_limit
    in 500..599 -> R.string.tesla_login_error_service
    else -> R.string.tesla_login_error_generic
}

internal fun teslaLoginCallbackErrorRes(errorCode: String): Int = when (errorCode) {
    "access_denied", "cancelled", "user_cancelled" -> R.string.tesla_login_error_cancelled
    "unauthorized_client", "invalid_client" -> R.string.tesla_login_error_token_config
    else -> R.string.tesla_login_error_exchange
}

internal enum class TeslaCallbackReplayDecision {
    Process,
    Ignore,
    OpenDashboard
}

internal fun teslaCallbackReplayDecision(
    ticket: String,
    inFlightTicket: String?,
    handledTicket: String?,
    hasSession: Boolean
): TeslaCallbackReplayDecision = when {
    ticket.isBlank() -> TeslaCallbackReplayDecision.Ignore
    ticket == inFlightTicket -> TeslaCallbackReplayDecision.Ignore
    ticket == handledTicket && hasSession -> TeslaCallbackReplayDecision.OpenDashboard
    ticket == handledTicket -> TeslaCallbackReplayDecision.Ignore
    else -> TeslaCallbackReplayDecision.Process
}

internal fun shouldIgnoreTeslaCallbackTicket(
    ticket: String,
    inFlightTicket: String?,
    handledTicket: String?
): Boolean = teslaCallbackReplayDecision(
    ticket = ticket,
    inFlightTicket = inFlightTicket,
    handledTicket = handledTicket,
    hasSession = false
) != TeslaCallbackReplayDecision.Process

internal fun shouldTreatTeslaExchangeFailureAsSuccess(
    httpStatus: Int,
    hasSession: Boolean
): Boolean = hasSession && httpStatus in setOf(401, 403)

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

package com.matelink.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.matelink.R
import com.matelink.data.api.UrlSecurity
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.ConnectionMode
import com.matelink.data.local.ConnectionModeStore
import com.matelink.data.local.TirePosition
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.ConnectionTestOutcome
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.repository.ConnectionUrlValidation
import com.matelink.data.repository.validateConnectionUrl
import com.matelink.data.repository.SentryStateRepository
import com.matelink.data.repository.TpmsStateRepository
import com.matelink.notification.SentryNotificationManager
import com.matelink.data.sync.DataSyncWorker
import com.matelink.data.sync.SyncManager
import com.matelink.data.sync.TpmsPressureWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val apiToken: String = "",
    val secondaryServerUrl: String = "",
    val httpBasicAuthUsername: String = "",
    val httpBasicAuthPassword: String = "",
    val acceptInvalidCerts: Boolean = false,
    val currencyCode: String = "CNY",
    val showShortDrivesCharges: Boolean = false,
    val languageCode: String = "",
    val mockMode: Boolean = false,
    val isLoading: Boolean = true,
    val isTesting: Boolean = false,
    val isSaving: Boolean = false,
    val isResyncing: Boolean = false,
    val testResult: TestResult? = null,
    val connectionWarning: String? = null,
    val error: String? = null,
    val successMessage: String? = null,
    val needsRecreate: Boolean = false,
    val isFirstRunSetup: Boolean = false
)

/**
 * Represents the result of testing a single server connection.
 */
sealed class ServerTestResult {
    data class Success(
        val carCount: Int = 0,
        val firstCarName: String? = null,
        val warning: String? = null
    ) : ServerTestResult()
    data class Failure(val message: String, val hint: String? = null) : ServerTestResult()
}

data class TestResult(
    val primaryResult: ServerTestResult,
    val secondaryResult: ServerTestResult? = null
)

internal fun serverUrlForDisplay(savedUrl: String): String = savedUrl.ifBlank { "https://" }

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val connectionModeStore: ConnectionModeStore,
    private val settingsRepository: SettingsRepository,
    private val repository: TeslamateRepository,
    private val syncManager: SyncManager,
    private val tpmsStateRepository: TpmsStateRepository,
    private val sentryStateRepository: SentryStateRepository,
    private val sentryNotificationManager: SentryNotificationManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val settings = settingsDataStore.settings.first()
            val mockMode = settingsRepository.mockMode.first()
            _uiState.value = _uiState.value.copy(
                serverUrl = serverUrlForDisplay(settings.serverUrl),
                apiToken = settings.apiToken,
                connectionWarning = localHttpWarning(settings.serverUrl),
                currencyCode = settings.currencyCode,
                showShortDrivesCharges = settings.showShortDrivesCharges,
                languageCode = settings.languageCode,
                mockMode = mockMode,
                isFirstRunSetup = !settings.isConfigured && !mockMode,
                isLoading = false
            )
        }
    }

    fun updateServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(
            serverUrl = url,
            testResult = null,
            connectionWarning = localHttpWarning(url),
            error = null
        )
    }

    fun updateApiToken(token: String) {
        _uiState.value = _uiState.value.copy(
            apiToken = token,
            testResult = null,
            error = null
        )
    }

    // Retained only for compatibility with stored legacy settings; P0.5 no longer renders editors for them.
    fun updateSecondaryServerUrl(url: String) {
        _uiState.value = _uiState.value.copy(secondaryServerUrl = url, testResult = null, error = null)
    }

    fun updateHttpBasicAuthUsername(username: String) {
        _uiState.value = _uiState.value.copy(httpBasicAuthUsername = username, testResult = null, error = null)
    }

    fun updateHttpBasicAuthPassword(password: String) {
        _uiState.value = _uiState.value.copy(httpBasicAuthPassword = password, testResult = null, error = null)
    }

    fun updateAcceptInvalidCerts(accept: Boolean) {
        _uiState.value = _uiState.value.copy(acceptInvalidCerts = accept, testResult = null, error = null)
    }

    fun updateCurrency(currencyCode: String) {
        _uiState.value = _uiState.value.copy(currencyCode = currencyCode)
        viewModelScope.launch {
            settingsDataStore.saveCurrency(currencyCode)
        }
    }

    fun updateShowShortDrivesCharges(show: Boolean) {
        _uiState.value = _uiState.value.copy(showShortDrivesCharges = show)
        viewModelScope.launch {
            settingsDataStore.saveShowShortDrivesCharges(show)
        }
    }

    fun updateLanguage(languageCode: String) {
        _uiState.value = _uiState.value.copy(languageCode = languageCode)
        viewModelScope.launch {
            settingsDataStore.saveLanguageCode(languageCode)
            // Apply the locale change immediately
            val changed = com.matelink.locale.LocaleHelper.applyLocale(context, languageCode)
            if (changed) {
                // Signal that the activity needs to be recreated
                _uiState.value = _uiState.value.copy(needsRecreate = true)
            }
        }
    }

    fun clearNeedsRecreate() {
        _uiState.value = _uiState.value.copy(needsRecreate = false)
    }

    fun updateMockMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(
            mockMode = enabled,
            isFirstRunSetup = _uiState.value.serverUrl.isBlank() && !enabled,
        )
        viewModelScope.launch {
            settingsRepository.setMockMode(enabled)
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTesting = true, testResult = null, error = null)

            val validation = validateConnectionUrl(_uiState.value.serverUrl)
            if (validation is ConnectionUrlValidation.Invalid) {
                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    testResult = TestResult(ServerTestResult.Failure(validation.message))
                )
                return@launch
            }
            val url = (validation as ConnectionUrlValidation.Valid).normalizedUrl
            if (UrlSecurity.classify(url) == UrlSecurity.Verdict.Unsafe) {
                _uiState.value = _uiState.value.copy(
                    isTesting = false,
                    testResult = TestResult(ServerTestResult.Failure("Public HTTP is not secure"))
                )
                return@launch
            }
            val primaryResult = when (val result = repository.testConnection(
                serverUrl = url,
                acceptInvalidCerts = false,
                apiToken = _uiState.value.apiToken
            )) {
                is ApiResult.Success -> result.data.toServerTestResult()
                is ApiResult.Error -> ServerTestResult.Failure(controlledConnectionError(result))
            }

            _uiState.value = _uiState.value.copy(
                isTesting = false,
                testResult = TestResult(primaryResult)
            )
        }
    }

    /**
     * Fetches global settings from the API and caches the base_url.
     * This runs silently - failures don't affect the user experience.
     */
    private suspend fun fetchAndCacheGlobalSettings() {
        when (val result = repository.getGlobalSettings()) {
            is ApiResult.Success -> {
                result.data.settings?.teslamateUrls?.baseUrl?.let { url ->
                    settingsDataStore.saveTeslamateBaseUrl(url.trimEnd('/'))
                }
            }
            is ApiResult.Error -> {
                // Silent fail - this is optional functionality
                // Older Teslamate API versions may not have this endpoint
            }
        }
    }

    fun saveSettings(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, error = null)

            try {
                val validation = validateConnectionUrl(_uiState.value.serverUrl)
                if (validation is ConnectionUrlValidation.Invalid && !_uiState.value.mockMode) {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = validation.message
                    )
                    return@launch
                }
                val url = (validation as? ConnectionUrlValidation.Valid)?.normalizedUrl.orEmpty()
                if (! _uiState.value.mockMode && UrlSecurity.classify(url) == UrlSecurity.Verdict.Unsafe) {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = context.getString(R.string.settings_public_http_unsafe)
                    )
                    return@launch
                }
                settingsDataStore.saveConnectionSettings(
                    serverUrl = url,
                    apiToken = _uiState.value.apiToken,
                    currencyCode = _uiState.value.currencyCode
                )
                connectionModeStore.set(ConnectionMode.SELF_HOSTED)

                // Trigger sync after settings are saved (handles first-time setup)
                triggerImmediateSync()

                _uiState.value = _uiState.value.copy(isSaving = false)
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = context.getString(R.string.error_save_settings)
                )
            }
        }
    }

    fun clearTestResult() {
        _uiState.value = _uiState.value.copy(testResult = null)
    }

    private fun ConnectionTestOutcome.toServerTestResult(): ServerTestResult {
        return if (isSuccessful) {
            ServerTestResult.Success(
                carCount = carCount,
                firstCarName = firstCarName,
                warning = readinessWarning
            )
        } else {
            ServerTestResult.Failure(summary, failureHint)
        }
    }

    private fun localHttpWarning(url: String): String? = when (val validation = validateConnectionUrl(url)) {
        is ConnectionUrlValidation.Valid -> if (UrlSecurity.classify(validation.normalizedUrl) == UrlSecurity.Verdict.LocalHttp) {
            context.getString(R.string.settings_local_http_warning)
        } else null
        is ConnectionUrlValidation.Invalid -> null
    }

    private fun controlledConnectionError(result: ApiResult.Error): String = when (result.code) {
        401, 403 -> context.getString(R.string.settings_api_key_invalid)
        else -> when (result.message) {
            "Public HTTP is not secure" -> context.getString(R.string.settings_public_http_unsafe)
            "Connection timed out" -> context.getString(R.string.settings_connection_timeout)
            "Server returned unrecognised data" -> context.getString(R.string.settings_unrecognised_server_data)
            else -> context.getString(R.string.settings_server_unreachable)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearSuccessMessage() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }

    fun forceResync() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isResyncing = true, error = null)
            try {
                // Get all cars and do a full reset (delete all cached data) for each
                when (val result = repository.getCars()) {
                    is ApiResult.Success -> {
                        for (car in result.data) {
                            syncManager.fullResetSync(car.carId)
                        }
                        // Trigger immediate sync via WorkManager
                        triggerImmediateSync()
                        _uiState.value = _uiState.value.copy(
                            isResyncing = false,
                            successMessage = context.getString(R.string.resync_started)
                        )
                    }
                    is ApiResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isResyncing = false,
                            error = context.getString(R.string.error_resync, result.message)
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isResyncing = false,
                    error = context.getString(R.string.error_resync, e.message ?: "")
                )
            }
        }
    }

    private fun triggerImmediateSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<DataSyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(DataSyncWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            DataSyncWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
    }

    // ==================== Debug Functions ====================

    /**
     * Simulate a TPMS warning for testing purposes.
     * Shows a test notification immediately.
     * Only use in debug builds.
     */
    fun simulateTpmsWarning(tire: TirePosition) {
        viewModelScope.launch {
            // Save state for consistency
            tpmsStateRepository.simulateWarning(1, tire)

            // Show notification immediately for testing
            createNotificationChannel()
            val tireName = getTireFullName(tire)
            showTpmsNotification(
                title = context.getString(R.string.tpms_notification_title),
                body = context.getString(R.string.tpms_notification_body, "Test Car", tireName)
            )

            _uiState.value = _uiState.value.copy(
                successMessage = context.getString(R.string.debug_tpms_simulated, tire.name)
            )
        }
    }

    /**
     * Clear the TPMS warning state for testing purposes.
     * Shows a "cleared" notification immediately.
     * Only use in debug builds.
     */
    fun clearTpmsWarning() {
        viewModelScope.launch {
            // Clear state
            tpmsStateRepository.clearWarning(1)

            // Show notification immediately for testing
            createNotificationChannel()
            showTpmsNotification(
                title = context.getString(R.string.tpms_notification_title),
                body = context.getString(R.string.tpms_notification_cleared, "Test Car")
            )

            _uiState.value = _uiState.value.copy(
                successMessage = "TPMS state cleared"
            )
        }
    }

    /**
     * Simulate a sentry alert event for testing purposes.
     * Increments the event counter and fires a notification immediately.
     * Only use in debug builds.
     */
    fun simulateSentryEvent() {
        viewModelScope.launch {
            val carId = 1
            // Fetch current position so simulated events get an address
            val statusResult = repository.getCarStatus(carId)
            val status = (statusResult as? ApiResult.Success)?.data?.status
            val count = sentryStateRepository.forceIncrementEventCount(
                carId,
                latitude = status?.latitude,
                longitude = status?.longitude,
                geofence = status?.geofence
            )

            sentryNotificationManager.showSentryAlert(
                carName = "Test Car",
                carId = carId,
                eventCount = count
            )

            _uiState.value = _uiState.value.copy(
                successMessage = context.getString(R.string.debug_sentry_simulated, count)
            )
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            TpmsPressureWorker.CHANNEL_ID,
            context.getString(R.string.tpms_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.tpms_channel_description)
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun showTpmsNotification(title: String, body: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        val notification = NotificationCompat.Builder(context, TpmsPressureWorker.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(2001, notification)
    }

    private fun getTireFullName(tire: TirePosition): String {
        return when (tire) {
            TirePosition.FL -> context.getString(R.string.tire_fl_full)
            TirePosition.FR -> context.getString(R.string.tire_fr_full)
            TirePosition.RL -> context.getString(R.string.tire_rl_full)
            TirePosition.RR -> context.getString(R.string.tire_rr_full)
        }
    }

    /**
     * Run TPMS check immediately (for debugging).
     */
    fun runTpmsCheckNow() {
        TpmsPressureWorker.runNow(context)
        _uiState.value = _uiState.value.copy(
            successMessage = "TPMS check triggered - check logcat for TpmsPressureWorker"
        )
    }
}

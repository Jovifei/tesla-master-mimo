package com.matelink.data.repository

import android.util.Log
import com.matelink.data.api.TeslamateApi
import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.GlobalSettingsData
import com.matelink.data.api.models.Units
import com.matelink.data.api.models.UpdateData
import com.matelink.data.local.AppSettings
import com.matelink.data.local.SettingsDataStore
import com.matelink.di.TeslamateApiFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(
        val message: String,
        val code: Int? = null,
        val details: String? = null
    ) : ApiResult<Nothing>()
}

data class CarStatusWithUnits(
    val status: CarStatus,
    val units: Units
)

/**
 * Typed outcome of the current-charge endpoint: the server answering
 * "no active charge" is an authoritative response, distinct from errors.
 */
sealed class CurrentChargeOutcome {
    data class Active(val detail: ChargeDetail) : CurrentChargeOutcome()
    data object NoActiveCharge : CurrentChargeOutcome()
}

/**
 * Represents exceptions that should trigger a fallback to the secondary server.
 * These are network-level errors where the server is unreachable, not application-level errors.
 */
private fun Throwable.isNetworkError(): Boolean {
    return this is SocketTimeoutException ||
            this is ConnectException ||
            this is UnknownHostException ||
            this is SSLException ||
            this is java.io.IOException && message?.contains("connection", ignoreCase = true) == true
}

/**
 * Checks if an exception is a JSON parsing error.
 * These errors indicate the server returned something that isn't valid JSON
 * or doesn't match the expected schema.
 */
private fun Throwable.isJsonParsingError(): Boolean {
    return this is JsonDataException ||
            this is JsonEncodingException ||
            (this is java.io.IOException && message?.contains("JsonReader", ignoreCase = true) == true)
}

@Singleton
class TeslamateRepository @Inject constructor(
    private val apiFactory: TeslamateApiFactory,
    private val settingsDataStore: SettingsDataStore,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "TeslamateRepository"
    }

    private suspend fun isMockMode(): Boolean =
        settingsRepository.mockMode.firstOrNull() == true

    // Cache: true = endpoint exists (API 1.24+), false = 404 (older API)
    private val currentChargeApiAvailable = mutableMapOf<Int, Boolean>()

    /**
     * Check whether the current charge endpoint is available for the given car.
     * Makes a dedicated HTTP call and checks only the status code: 200 means the endpoint exists,
     * anything else (e.g. 404 on old TM versions) means it doesn't.
     * Result is cached for the app session since the API version doesn't change at runtime.
     */
    suspend fun isCurrentChargeAvailable(carId: Int): Boolean {
        if (isMockMode()) { currentChargeApiAvailable[carId] = true; return true }
        currentChargeApiAvailable[carId]?.let { return it }
        val result = executeWithFallback { api ->
            val response = api.getCurrentCharge(carId)
            if (response.code() == 200) ApiResult.Success(true)
            else ApiResult.Error("Not available", response.code())
        }
        val available = result is ApiResult.Success
        currentChargeApiAvailable[carId] = available
        return available
    }

    private suspend fun getSettings(): AppSettings = settingsDataStore.settings.first()

    private suspend fun getApiForUrl(url: String): TeslamateApi? {
        if (url.isBlank()) return null
        return apiFactory.create(url)
    }

    /**
     * Executes an API call with automatic fallback to the secondary server if configured.
     *
     * The fallback is triggered only for network-level errors (timeout, connection refused,
     * DNS failure, SSL errors). HTTP errors (4xx, 5xx) do NOT trigger fallback because
     * they indicate the server is reachable but returned an error.
     *
     * @param apiCall The API call to execute, given a TeslamateApi instance
     * @return The result of the API call
     */
    private suspend fun <T> executeWithFallback(
        apiCall: suspend (TeslamateApi) -> ApiResult<T>
    ): ApiResult<T> {
        val settings = getSettings()

        if (settings.serverUrl.isBlank()) {
            return ApiResult.Error("Server not configured")
        }

        // Try primary server first
        val primaryApi = getApiForUrl(settings.serverUrl)
            ?: return ApiResult.Error("Server not configured")

        val primaryResult = try {
            apiCall(primaryApi)
        } catch (e: Exception) {
            if (e.isNetworkError() && settings.hasSecondaryServer) {
                Log.d(TAG, "Primary server failed with network error, trying secondary: ${e.message}")
                null // Will try secondary
            } else {
                // Not a network error or no secondary server, return the error
                return when {
                    e is javax.net.ssl.SSLHandshakeException ->
                        ApiResult.Error("SSL certificate error. Enable 'Accept invalid certificates' for self-signed certs.")
                    e.isJsonParsingError() ->
                        ApiResult.Error(
                            message = "Invalid response from server",
                            details = "The server returned an unexpected response that could not be parsed.\n\n" +
                                    "This usually means:\n" +
                                    "• The API URL might be incorrect\n" +
                                    "• The server is returning an error page\n" +
                                    "• TeslaMate API is not properly configured\n\n" +
                                    "Technical details: ${e.message}"
                        )
                    else -> ApiResult.Error(e.message ?: "Connection failed")
                }
            }
        }

        // If primary succeeded or returned an HTTP error, return it
        if (primaryResult != null) {
            // Only fallback on network errors, not on HTTP errors
            if (primaryResult is ApiResult.Success) {
                return primaryResult
            }
            // For HTTP errors, don't fallback - the server is reachable
            if (primaryResult is ApiResult.Error && primaryResult.code != null) {
                return primaryResult
            }
        }

        // Try secondary server if available
        if (settings.hasSecondaryServer) {
            Log.d(TAG, "Trying secondary server: ${settings.secondaryServerUrl}")
            val secondaryApi = getApiForUrl(settings.secondaryServerUrl)
                ?: return primaryResult ?: ApiResult.Error("Secondary server not configured")

            return try {
                apiCall(secondaryApi)
            } catch (e: Exception) {
                Log.d(TAG, "Secondary server also failed: ${e.message}")
                // Both servers failed, return a combined error message
                when {
                    e is javax.net.ssl.SSLHandshakeException ->
                        ApiResult.Error("Both servers failed. SSL certificate error on secondary server.")
                    e.isJsonParsingError() ->
                        ApiResult.Error(
                            message = "Invalid response from secondary server",
                            details = "The secondary server returned an unexpected response.\n\n" +
                                    "Technical details: ${e.message}"
                        )
                    else -> ApiResult.Error("Both servers unreachable: ${e.message}")
                }
            }
        }

        // No secondary server, return the primary error
        return primaryResult ?: ApiResult.Error("Connection failed")
    }

    suspend fun testConnection(
        serverUrl: String,
        acceptInvalidCerts: Boolean = false,
        apiToken: String? = null
    ): ApiResult<ConnectionTestOutcome> {
        when (val validation = validateConnectionUrl(serverUrl)) {
            is ConnectionUrlValidation.Invalid -> return ApiResult.Error(validation.message)
            is ConnectionUrlValidation.Valid -> {
                if (isMockMode()) {
                    val cars = MockDataProvider.getCars()
                    return ApiResult.Success(
                        ConnectionTestOutcome(
                            ping = ConnectionStepResult.Success,
                            readiness = ConnectionStepResult.Success,
                            cars = ConnectionStepResult.Success,
                            carCount = cars.size,
                            firstCarName = cars.firstOrNull()?.displayName
                        )
                    )
                }
                return runConnectionProbe(validation.normalizedUrl, acceptInvalidCerts, apiToken)
            }
        }
    }

    private suspend fun runConnectionProbe(
        serverUrl: String,
        acceptInvalidCerts: Boolean,
        apiToken: String?
    ): ApiResult<ConnectionTestOutcome> {
        return try {
            val api = apiFactory.create(serverUrl, acceptInvalidCerts, apiTokenOverride = apiToken)

            val pingResponse = api.ping()
            if (!pingResponse.isSuccessful) {
                return ApiResult.Success(
                    ConnectionTestOutcome(
                        ping = httpFailure("Ping failed", pingResponse.code())
                    )
                )
            }

            val readinessResult = try {
                val readinessResponse = api.readyz()
                when {
                    readinessResponse.isSuccessful -> ConnectionStepResult.Success
                    readinessResponse.code() == 404 -> ConnectionStepResult.Warning(
                        message = "Readiness endpoint is unavailable",
                        hint = "Continuing with vehicle check"
                    )
                    else -> ConnectionStepResult.Warning(
                        message = "Readiness check returned HTTP ${readinessResponse.code()}",
                        hint = "Continuing with vehicle check"
                    )
                }
            } catch (e: Exception) {
                ConnectionStepResult.Warning(
                    message = "Readiness check failed: ${e.message ?: "unknown error"}",
                    hint = "Continuing with vehicle check"
                )
            }

            val carsResponse = api.getCars()
            val carsResult = if (carsResponse.isSuccessful) {
                val cars = carsResponse.body()?.data?.cars ?: emptyList()
                if (cars.isEmpty()) {
                    ConnectionStepResult.Failure(
                        message = "No cars returned by TeslaMate",
                        hint = "Check TeslaMate API permissions and data availability"
                    )
                } else {
                    ConnectionStepResult.Success
                }
            } else {
                httpFailure("Vehicle check failed", carsResponse.code())
            }

            val cars = if (carsResult is ConnectionStepResult.Success) {
                carsResponse.body()?.data?.cars ?: emptyList()
            } else {
                emptyList()
            }

            ApiResult.Success(
                ConnectionTestOutcome(
                    ping = ConnectionStepResult.Success,
                    readiness = readinessResult,
                    cars = carsResult,
                    carCount = cars.size,
                    firstCarName = cars.firstOrNull()?.displayName
                )
            )
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            ApiResult.Error("SSL certificate error. Enable 'Accept invalid certificates' for self-signed certs.")
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Connection failed")
        }
    }

    private fun httpFailure(prefix: String, code: Int): ConnectionStepResult.Failure {
        val hint = when (code) {
            401, 403 -> "Check your API token or HTTP Basic Auth credentials"
            404 -> "Enter the TeslaMateApi-compatible API root URL, not Grafana or TeslaMate Web UI, and do not add /api or /api/v1"
            in 500..599 -> "TeslaMate API is reachable but returned a server error"
            else -> "Check the TeslaMate URL and network access"
        }
        return ConnectionStepResult.Failure("$prefix: HTTP $code", hint)
    }

    suspend fun getCars(): ApiResult<List<CarData>> {
        if (isMockMode()) return ApiResult.Success(MockDataProvider.getCars())
        return executeWithFallback { api ->
            try {
                val response = api.getCars()
                if (response.isSuccessful) {
                    val cars = response.body()?.data?.cars ?: emptyList()
                    ApiResult.Success(cars)
                } else {
                    ApiResult.Error("Failed to fetch cars: ${response.code()}", response.code())
                }
            } catch (e: Exception) {
                throw e // Let executeWithFallback handle it
            }
        }
    }

    suspend fun getCar(carId: Int): ApiResult<CarData> {
        if (isMockMode()) {
            val car = MockDataProvider.getCars().firstOrNull { it.carId == carId }
                ?: return ApiResult.Error("No car data returned")
            return ApiResult.Success(car)
        }
        return executeWithFallback { api ->
            try {
                val response = api.getCar(carId)
                if (response.isSuccessful) {
                    val car = response.body()?.data?.cars?.firstOrNull()
                    if (car != null) {
                        ApiResult.Success(car)
                    } else {
                        ApiResult.Error("No car data returned")
                    }
                } else {
                    ApiResult.Error("Failed to fetch car: ${response.code()}", response.code())
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun getCarStatus(carId: Int): ApiResult<CarStatusWithUnits> {
        if (isMockMode()) return ApiResult.Success(CarStatusWithUnits(MockDataProvider.getCarStatus(), MockDataProvider.getUnits()))
        return executeWithFallback { api ->
            try {
                val response = api.getCarStatus(carId)
                if (response.isSuccessful) {
                    val data = response.body()?.data
                    val status = data?.status
                    val units = data?.units ?: Units()
                    if (status != null) {
                        ApiResult.Success(CarStatusWithUnits(status, units))
                    } else {
                        ApiResult.Error("No status data returned")
                    }
                } else {
                    ApiResult.Error("Failed to fetch status: ${response.code()}", response.code())
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun getCharges(
        carId: Int,
        startDate: String? = null,
        endDate: String? = null,
        page: Int = 1,
        show: Int = 50000
    ): ApiResult<List<ChargeData>> {
        if (isMockMode()) return ApiResult.Success(MockDataProvider.getCharges())
        return executeWithFallback { api ->
            try {
                val response = api.getCharges(carId, startDate, endDate, page = page, show = show)
                if (response.isSuccessful) {
                    val charges = response.body()?.data?.charges ?: emptyList()
                    ApiResult.Success(charges)
                } else {
                    ApiResult.Error("Failed to fetch charges: ${response.code()}", response.code())
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun getCurrentCharge(carId: Int): ApiResult<CurrentChargeOutcome> {
        if (isMockMode()) return ApiResult.Success(CurrentChargeOutcome.NoActiveCharge)
        return executeWithFallback { api ->
            try {
                val response = api.getCurrentCharge(carId)
                if (response.isSuccessful) {
                    val body = response.body()
                    val detail = body?.data?.charge
                    when {
                        detail != null -> ApiResult.Success(CurrentChargeOutcome.Active(detail))
                        // TeslamateAPI answers 200 + {"error": "..."} (or 204) when there is
                        // no active charge — an authoritative answer, not a failure. At charge
                        // start this is returned for a short while before the charge appears.
                        body?.error != null || response.code() == 204 ->
                            ApiResult.Success(CurrentChargeOutcome.NoActiveCharge)
                        else -> ApiResult.Error("No current charge data returned")
                    }
                } else {
                    ApiResult.Error("Failed to fetch current charge: ${response.code()}", response.code())
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun getChargeDetail(carId: Int, chargeId: Int): ApiResult<ChargeDetail> {
        if (isMockMode()) return ApiResult.Success(MockDataProvider.getChargeDetail(chargeId))
        return executeWithFallback { api ->
            try {
                val response = api.getChargeDetail(carId, chargeId)
                if (response.isSuccessful) {
                    val detail = response.body()?.data?.charge
                    if (detail != null) {
                        ApiResult.Success(detail)
                    } else {
                        ApiResult.Error("No charge detail returned")
                    }
                } else {
                    ApiResult.Error("Failed to fetch charge detail: ${response.code()}", response.code())
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun getDrives(
        carId: Int,
        startDate: String? = null,
        endDate: String? = null,
        page: Int = 1,
        show: Int = 50000
    ): ApiResult<List<DriveData>> {
        if (isMockMode()) return ApiResult.Success(MockDataProvider.getDrives())
        return executeWithFallback { api ->
            try {
                val response = api.getDrives(carId, startDate, endDate, page = page, show = show)
                if (response.isSuccessful) {
                    val drives = response.body()?.data?.drives ?: emptyList()
                    ApiResult.Success(drives)
                } else {
                    ApiResult.Error("Failed to fetch drives: ${response.code()}", response.code())
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun getDriveDetail(carId: Int, driveId: Int): ApiResult<DriveDetail> {
        if (isMockMode()) return ApiResult.Success(MockDataProvider.getDriveDetail(driveId))
        return executeWithFallback { api ->
            try {
                val response = api.getDriveDetail(carId, driveId)
                if (response.isSuccessful) {
                    val detail = response.body()?.data?.drive
                    if (detail != null) {
                        ApiResult.Success(detail)
                    } else {
                        ApiResult.Error("No drive detail returned")
                    }
                } else {
                    ApiResult.Error("Failed to fetch drive detail: ${response.code()}", response.code())
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun getBatteryHealth(carId: Int): ApiResult<BatteryHealth> {
        if (isMockMode()) return ApiResult.Success(MockDataProvider.getBatteryHealth())
        return executeWithFallback { api ->
            try {
                val response = api.getBatteryHealth(carId)
                if (response.isSuccessful) {
                    val health = response.body()?.data?.batteryHealth
                    if (health != null) {
                        ApiResult.Success(health)
                    } else {
                        ApiResult.Error("No battery health data returned")
                    }
                } else {
                    ApiResult.Error("Failed to fetch battery health: ${response.code()}", response.code())
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun getUpdates(carId: Int): ApiResult<List<UpdateData>> {
        if (isMockMode()) return ApiResult.Success(MockDataProvider.getUpdates())
        return executeWithFallback { api ->
            try {
                val response = api.getUpdates(carId, page = 1, show = 50000)
                if (response.isSuccessful) {
                    val updates = response.body()?.data?.updates ?: emptyList()
                    ApiResult.Success(updates)
                } else {
                    ApiResult.Error("Failed to fetch updates: ${response.code()}", response.code())
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun getGlobalSettings(): ApiResult<GlobalSettingsData> {
        if (isMockMode()) return ApiResult.Success(MockDataProvider.getGlobalSettings())
        return executeWithFallback { api ->
            try {
                val response = api.getGlobalSettings()
                if (response.isSuccessful) {
                    val data = response.body()?.data
                    if (data != null) {
                        ApiResult.Success(data)
                    } else {
                        ApiResult.Error("No global settings data returned")
                    }
                } else {
                    ApiResult.Error("Failed to fetch global settings: ${response.code()}", response.code())
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }
}

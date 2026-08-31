package com.matelink.data.repository

import com.matelink.data.api.UrlSecurity
import com.matelink.BuildConfig
import com.matelink.data.api.TeslamateApi
import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DataReadiness
import com.matelink.data.api.models.DataReadinessItem
import com.matelink.data.api.models.DataReadinessResponse
import com.matelink.data.api.models.GlobalSettingsData
import com.matelink.data.api.models.Units
import com.matelink.data.api.models.AdapterSnapshot
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.data.api.models.StandbyWindowData
import com.matelink.data.api.models.TelemetryConfigureResponse
import com.matelink.data.api.models.TelemetryConfigureResult
import com.matelink.data.api.models.TelemetryPairingResponse
import com.matelink.data.api.models.TelemetryPairingStatus
import com.matelink.data.api.models.UpdateData
import com.matelink.data.local.AppSettings
import com.matelink.data.local.ConnectionMode
import com.matelink.data.local.ConnectionModeStore
import com.matelink.data.local.SettingsDataStore
import com.matelink.di.TeslamateApiFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.CancellationException
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import retrofit2.Response

enum class ApiErrorKind {
    AUTH_REQUIRED,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    NETWORK,
    CONFIGURATION,
    INVALID_RESPONSE,
    UNKNOWN
}

fun apiErrorKindFor(code: Int?, message: String? = null): ApiErrorKind = when {
    code == 401 || code == 403 -> ApiErrorKind.AUTH_REQUIRED
    code == 429 -> ApiErrorKind.RATE_LIMITED
    code != null && code in 500..599 -> ApiErrorKind.SERVICE_UNAVAILABLE
    message?.contains("not configured", ignoreCase = true) == true -> ApiErrorKind.CONFIGURATION
    message?.contains("unrecognised", ignoreCase = true) == true -> ApiErrorKind.INVALID_RESPONSE
    message?.contains("unreachable", ignoreCase = true) == true ||
        message?.contains("timed out", ignoreCase = true) == true -> ApiErrorKind.NETWORK
    else -> ApiErrorKind.UNKNOWN
}

sealed class ApiResult<out T> {
    data class Success<T>(
        val data: T,
        val metadata: ApiResponseMetadata? = null
    ) : ApiResult<T>()
    data class Error(
        val message: String,
        val code: Int? = null,
        val details: String? = null,
        val kind: ApiErrorKind = apiErrorKindFor(code, message)
    ) : ApiResult<Nothing>()
}

internal fun compatibilityDataReadiness(carId: Int): DataReadiness = DataReadiness(
    capabilityVersion = 0,
    vehicleUid = "self-hosted:car:$carId",
    items = listOf("live_status", "location", "tpms", "drives", "charges", "battery_health").map {
        DataReadinessItem(
            key = it,
            status = "unknown",
            source = "legacy_compatibility",
            messageKey = "data_readiness_legacy_compatibility",
            action = "none"
        )
    }
)

internal fun dataReadinessResultForResponse(
    response: Response<DataReadinessResponse>,
    carId: Int,
    allowLegacyCompatibility: Boolean = false
): ApiResult<DataReadiness> = when {
    response.code() == 404 && allowLegacyCompatibility -> ApiResult.Success(compatibilityDataReadiness(carId))
    response.code() == 404 -> ApiResult.Error("Data readiness endpoint unavailable", response.code())
    response.isSuccessful -> response.body()?.data?.let { ApiResult.Success(it) }
        ?: ApiResult.Error("Data readiness unavailable", response.code())
    else -> ApiResult.Error("Failed to fetch data readiness: ${response.code()}", response.code())
}

private val telemetryErrorCodes = setOf(
    "pairing_required",
    "permission_required",
    "billing_blocked",
    "telemetry_error",
    "telemetry_not_configured"
)

internal fun telemetryErrorCodeForBody(body: String?): String? {
    val code = Regex("\\\"error\\\"\\s*:\\s*\\\"([a-z_]+)\\\"")
        .find(body.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
    return code?.takeIf(telemetryErrorCodes::contains)
}

internal fun telemetryPairingResultForResponse(
    response: Response<TelemetryPairingResponse>
): ApiResult<TelemetryPairingStatus> = when {
    response.isSuccessful -> response.body()?.data?.let { ApiResult.Success(it) }
        ?: ApiResult.Error("Fleet Telemetry status unavailable", response.code(), details = "telemetry_error")
    else -> ApiResult.Error(
        message = "Fleet Telemetry status unavailable",
        code = response.code(),
        details = telemetryErrorCodeForBody(response.errorBody()?.string()) ?: "telemetry_error"
    )
}

internal fun telemetryConfigureResultForResponse(
    response: Response<TelemetryConfigureResponse>
): ApiResult<TelemetryConfigureResult> = when {
    response.isSuccessful -> response.body()?.data?.let { ApiResult.Success(it) }
        ?: ApiResult.Error("Fleet Telemetry configuration unavailable", response.code(), details = "telemetry_error")
    else -> ApiResult.Error(
        message = "Fleet Telemetry configuration unavailable",
        code = response.code(),
        details = telemetryErrorCodeForBody(response.errorBody()?.string()) ?: "telemetry_error"
    )
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
    private val settingsRepository: SettingsRepository,
    private val connectionModeStore: ConnectionModeStore
) : DataReadinessDataSource {
    private suspend fun isMockMode(): Boolean =
        BuildConfig.JOURVOLT_MOCK_LOGIN && settingsRepository.mockMode.firstOrNull() == true

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

    /** Executes requests only against the explicitly saved primary server. */
    private suspend fun <T> executeWithFallback(
        apiCall: suspend (TeslamateApi) -> ApiResult<T>
    ): ApiResult<T> {
        val settings = getSettings()
        val mode = connectionModeStore.mode.first()
        val serverUrl = if (mode == ConnectionMode.TESLA_CLOUD) {
            BuildConfig.JOURVOLT_API_BASE_URL
        } else {
            settings.serverUrl
        }
        if (serverUrl.isBlank()) {
            return ApiResult.Error("Server not configured")
        }
        val primaryApi = getApiForUrl(serverUrl)
            ?: return ApiResult.Error("Server not configured")
        return try {
            apiCall(primaryApi)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.Error(connectionErrorMessage(e))
        }
    }

    suspend fun testConnection(
        serverUrl: String,
        acceptInvalidCerts: Boolean = false,
        apiToken: String? = null
    ): ApiResult<ConnectionTestOutcome> {
        when (val validation = validateConnectionUrl(serverUrl)) {
            is ConnectionUrlValidation.Invalid -> return ApiResult.Error(validation.message)
            is ConnectionUrlValidation.Valid -> {
                if (UrlSecurity.classify(validation.normalizedUrl) == UrlSecurity.Verdict.Unsafe) {
                    return ApiResult.Error("Public HTTP is not secure")
                }
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
            } catch (e: CancellationException) {
                throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            ApiResult.Error("Server certificate cannot be verified")
        } catch (e: Exception) {
            ApiResult.Error(connectionErrorMessage(e))
        }
    }

    private fun connectionErrorMessage(error: Exception): String = when {
        error is SocketTimeoutException -> "Connection timed out"
        error is ConnectException || error is UnknownHostException -> "Server is temporarily unreachable"
        error is javax.net.ssl.SSLHandshakeException -> "Server certificate cannot be verified"
        error.isJsonParsingError() -> "Server returned unrecognised data"
        else -> "Server is temporarily unreachable"
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
        if (isMockMode()) {
            val cars = MockDataProvider.getCars()
            return ApiResult.Success(cars)
        }
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

    override suspend fun getCar(carId: Int): ApiResult<CarData> {
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

    override suspend fun getCarStatus(carId: Int): ApiResult<CarStatusWithUnits> {
        if (isMockMode()) return ApiResult.Success(CarStatusWithUnits(MockDataProvider.getCarStatus(), MockDataProvider.getUnits()))
        return executeWithFallback { api ->
            try {
                val response = api.getCarStatus(carId)
                if (response.isSuccessful) {
                    val body = response.body()
                    val data = body?.data
                    val status = data?.status
                    val units = data?.units ?: Units()
                    if (status != null) {
                        ApiResult.Success(CarStatusWithUnits(status, units))
                    } else {
                        ApiResult.Error(body?.error ?: "No status data returned")
                    }
                } else {
                    ApiResult.Error("Failed to fetch status: ${response.code()}", response.code())
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    suspend fun getAdapterSnapshot(carId: Int): ApiResult<AdapterSnapshot> {
        if (isMockMode()) return ApiResult.Error("Adapter is not used in mock mode")
        return executeWithFallback { api ->
            val response = api.getAdapterSnapshot(carId)
            val snapshot = response.body()?.data
            when {
                response.isSuccessful && snapshot != null -> ApiResult.Success(snapshot)
                else -> ApiResult.Error(
                    response.body()?.error ?: "Adapter snapshot unavailable",
                    response.code()
                )
            }
        }
    }

    suspend fun getParkedDetail(
        carId: Int,
        olderDriveId: Int,
        newerDriveId: Int
    ): ApiResult<ParkedDetailData> {
        if (isMockMode()) return ApiResult.Error("Parked details require real data")
        return executeWithFallback { api ->
            val response = api.getParkedDetail(carId, olderDriveId, newerDriveId)
            val parked = response.body()?.data
            when {
                response.isSuccessful && parked != null -> ApiResult.Success(parked)
                else -> ApiResult.Error(
                    response.body()?.error ?: "Parked detail unavailable",
                    response.code()
                )
            }
        }
    }

    suspend fun getStandbyWindows(carId: Int): ApiResult<List<StandbyWindowData>> {
        if (isMockMode()) return ApiResult.Error("Standby analysis requires real adapter data")
        return executeWithFallback { api ->
            val response = api.getStandby(carId)
            val windows = response.body()?.data?.windows
            when {
                response.isSuccessful && windows != null -> ApiResult.Success(windows)
                else -> ApiResult.Error(
                    response.body()?.error ?: "Standby data unavailable",
                    response.code()
                )
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
        if (isMockMode()) {
            return ApiResult.Success(
                MockDataProvider.getCharges(),
                ApiResponseMetadata(availability = "available", source = BuildConfig.JOURVOLT_MOCK_SOURCE, coveragePercent = 100.0)
            )
        }
        return executeWithFallback { api ->
            try {
                val response = api.getCharges(carId, startDate, endDate, page = page, show = show)
                if (response.isSuccessful) {
                    val body = response.body()?.data
                    ApiResult.Success(
                        body?.charges ?: emptyList(),
                        body?.meta?.toApiResponseMetadata()
                    )
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
        if (isMockMode()) {
            return ApiResult.Success(
                MockDataProvider.getDrives(),
                ApiResponseMetadata(availability = "available", source = BuildConfig.JOURVOLT_MOCK_SOURCE, coveragePercent = 100.0)
            )
        }
        return executeWithFallback { api ->
            try {
                val response = api.getDrives(carId, startDate, endDate, page = page, show = show)
                if (response.isSuccessful) {
                    val body = response.body()?.data
                    ApiResult.Success(
                        body?.drives ?: emptyList(),
                        body?.meta?.toApiResponseMetadata()
                    )
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
        if (isMockMode()) {
            return ApiResult.Success(
                MockDataProvider.getBatteryHealth(),
                ApiResponseMetadata(availability = "available", source = BuildConfig.JOURVOLT_MOCK_SOURCE, coveragePercent = 100.0)
            )
        }
        return executeWithFallback { api ->
            try {
                val response = api.getBatteryHealth(carId)
                if (response.isSuccessful) {
                    val body = response.body()?.data
                    val health = body?.batteryHealth
                    if (health != null) {
                        ApiResult.Success(health, body?.meta?.toApiResponseMetadata())
                    } else {
                        ApiResult.Error("Battery health unavailable")
                    }
                } else {
                    ApiResult.Error("Failed to fetch battery health: ${response.code()}", response.code())
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    override suspend fun getDataReadiness(carId: Int): ApiResult<DataReadiness> {
        if (isMockMode()) return ApiResult.Success(compatibilityDataReadiness(carId))
        val allowLegacyCompatibility = connectionModeStore.mode.first() == ConnectionMode.SELF_HOSTED
        return executeWithFallback { api ->
            dataReadinessResultForResponse(
                api.getDataReadiness(carId),
                carId,
                allowLegacyCompatibility
            )
        }
    }

    override suspend fun getTelemetryPairingStatus(carId: Int): ApiResult<TelemetryPairingStatus> {
        if (isMockMode()) {
            return ApiResult.Success(TelemetryPairingStatus(status = "telemetry_not_configured"))
        }
        return executeWithFallback { api ->
            telemetryPairingResultForResponse(api.getTelemetryPairing(carId))
        }
    }

    override suspend fun configureTelemetry(carId: Int): ApiResult<TelemetryConfigureResult> {
        if (isMockMode()) {
            return ApiResult.Error(
                message = "Fleet Telemetry is not configured",
                details = "telemetry_not_configured"
            )
        }
        return executeWithFallback { api ->
            telemetryConfigureResultForResponse(api.configureTelemetry(carId))
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

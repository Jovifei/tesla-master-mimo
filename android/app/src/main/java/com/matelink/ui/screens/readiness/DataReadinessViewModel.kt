package com.matelink.ui.screens.readiness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.DataReadiness
import com.matelink.data.api.models.TelemetryPairingStatus
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextResolver
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.DataReadinessDataSource
import com.matelink.data.repository.LegacyHistoryMigrationEligibility
import com.matelink.data.repository.LegacyHistoryMigrationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

data class DataReadinessUiState(
    val isLoading: Boolean = true,
    val data: DataReadiness? = null,
    val error: String? = null,
    val migrationEligibility: LegacyHistoryMigrationEligibility? = null,
    val migrationTarget: VehicleContext? = null,
    val targetVehicleName: String? = null,
    val migrationBindingRequired: Boolean = false,
    val isMigrating: Boolean = false,
    val migrationComplete: Boolean = false,
    val pairing: TelemetryPairingStatus? = null,
    /** Never rendered directly; presentation maps this known code to localized copy. */
    val telemetryErrorCode: String? = null,
    val isConfiguringTelemetry: Boolean = false,
    val isTelemetryActivationPending: Boolean = false,
    val pairingLinkUnavailable: Boolean = false
)

@HiltViewModel
class DataReadinessViewModel @Inject constructor(
    private val repository: DataReadinessDataSource,
    private val vehicleContextRepository: VehicleContextResolver,
    private val migrationRepository: LegacyHistoryMigrationService
) : ViewModel() {
    private val _uiState = MutableStateFlow(DataReadinessUiState())
    val uiState: StateFlow<DataReadinessUiState> = _uiState.asStateFlow()

    private val pollingPolicy = TelemetryPollingPolicy()
    private val configureGate = TelemetryConfigureGate()
    private var loadedCarId: Int? = null
    private var loadedCar: CarData? = null
    private var loadJob: Job? = null
    private var configureJob: Job? = null
    private var pollJob: Job? = null
    private var loadGeneration = 0L
    private var configureGeneration = 0L
    private var configureLease: TelemetryConfigureGate.Lease? = null
    private var pollGeneration = 0L
    private var pageIsActive = true
    private var screenWasPaused = false

    fun setCarId(carId: Int) {
        if (loadedCarId == carId) return
        configureGeneration++
        loadedCarId = carId
        configureJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isConfiguringTelemetry = false,
            isTelemetryActivationPending = false,
            pairingLinkUnavailable = false
        )
        stopTelemetryPolling()
        load(carId)
    }

    fun refresh() {
        loadedCarId?.let(::load)
    }

    fun onScreenPaused() {
        pageIsActive = false
        screenWasPaused = true
        stopTelemetryPolling()
    }

    /** Returning from Tesla only refreshes authoritative status; it never configures telemetry. */
    fun onScreenResumed() {
        pageIsActive = true
        if (screenWasPaused) refresh()
    }

    fun reportPairingLinkUnavailable() {
        _uiState.value = _uiState.value.copy(pairingLinkUnavailable = true)
    }

    fun configureTelemetry() {
        val carId = loadedCarId ?: return
        val generation = configureGeneration
        val lease = configureGate.tryStart(generation) ?: return
        configureLease = lease
        configureJob = viewModelScope.launch {
            if (!isCurrentConfigure(generation, carId, lease)) return@launch
            _uiState.value = _uiState.value.copy(
                isConfiguringTelemetry = true,
                telemetryErrorCode = null,
                pairingLinkUnavailable = false
            )
            try {
                when (val result = repository.configureTelemetry(carId)) {
                    is ApiResult.Success -> {
                        if (!isCurrentConfigure(generation, carId, lease)) return@launch
                        _uiState.value = _uiState.value.copy(
                            isConfiguringTelemetry = false,
                            isTelemetryActivationPending = true,
                            telemetryErrorCode = null,
                            pairing = TelemetryPairingStatus(status = "waiting_vehicle", configSynced = null)
                        )
                        if (pageIsActive) startTelemetryPolling(carId)
                    }
                    is ApiResult.Error -> {
                        if (!isCurrentConfigure(generation, carId, lease)) return@launch
                        _uiState.value = _uiState.value.copy(
                            isConfiguringTelemetry = false,
                            telemetryErrorCode = result.details ?: "telemetry_error",
                            isTelemetryActivationPending = false
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                configureGate.finish(lease)
                if (configureLease === lease) configureLease = null
            }
        }
    }

    private fun load(carId: Int) {
        loadJob?.cancel()
        stopTelemetryPolling()
        val generation = ++loadGeneration
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val readinessRequest = async { repository.getDataReadiness(carId) }
                val pairingRequest = async { repository.getTelemetryPairingStatus(carId) }
                val readinessResult = readinessRequest.await()
                val pairingResult = pairingRequest.await()
                if (!isCurrentLoad(generation, carId)) return@launch

                val car = (repository.getCar(carId) as? ApiResult.Success)?.data
                if (!isCurrentLoad(generation, carId)) return@launch
                loadedCar = car
                val context = car?.let { vehicleContextRepository.resolve(it) }
                if (!isCurrentLoad(generation, carId)) return@launch
                val currentOdometer = (repository.getCarStatus(carId) as? ApiResult.Success)
                    ?.data?.status?.odometer
                if (!isCurrentLoad(generation, carId)) return@launch
                val eligibility = car?.let {
                    migrationRepository.inspect(
                        legacyCarId = carId,
                        currentModel = it.carDetails?.model,
                        currentVehicleFingerprint = context?.stableIdentity,
                        currentObservedOdometer = currentOdometer
                    )
                }
                if (!isCurrentLoad(generation, carId)) return@launch

                val pairing = (pairingResult as? ApiResult.Success)?.data
                val pairingErrorCode = (pairingResult as? ApiResult.Error)?.details
                    ?: if (pairingResult is ApiResult.Error) "telemetry_error" else null
                val previous = _uiState.value
                _uiState.value = when (readinessResult) {
                    is ApiResult.Success -> DataReadinessUiState(
                        isLoading = false,
                        data = readinessResult.data,
                        migrationEligibility = eligibility,
                        migrationTarget = context,
                        targetVehicleName = car?.displayName,
                        migrationBindingRequired = migrationBindingRequired(eligibility),
                        pairing = pairing,
                        telemetryErrorCode = pairingErrorCode,
                        isConfiguringTelemetry = previous.isConfiguringTelemetry,
                        isTelemetryActivationPending = previous.isTelemetryActivationPending && pairing?.configSynced != true
                    )
                    is ApiResult.Error -> DataReadinessUiState(
                        isLoading = false,
                        error = readinessResult.message,
                        migrationEligibility = eligibility,
                        migrationTarget = context,
                        targetVehicleName = car?.displayName,
                        migrationBindingRequired = migrationBindingRequired(eligibility),
                        pairing = pairing,
                        telemetryErrorCode = pairingErrorCode,
                        isConfiguringTelemetry = previous.isConfiguringTelemetry,
                        isTelemetryActivationPending = previous.isTelemetryActivationPending && pairing?.configSynced != true
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isCurrentLoad(generation, carId)) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                }
            }
        }
    }

    private fun startTelemetryPolling(carId: Int) {
        stopTelemetryPolling()
        val generation = ++pollGeneration
        pollJob = viewModelScope.launch {
            withTimeout(TelemetryPollingPolicy.MAXIMUM_WINDOW_MS) {
                var elapsedMs = 0L
                while (
                    pollingPolicy.shouldContinue(elapsedMs, generation, pollGeneration, pageIsActive) &&
                        loadedCarId == carId
                ) {
                    when (val result = repository.getTelemetryPairingStatus(carId)) {
                        is ApiResult.Success -> {
                            if (!isCurrentPoll(generation, carId)) return@withTimeout
                            _uiState.value = _uiState.value.copy(
                                pairing = result.data,
                                telemetryErrorCode = null,
                                isTelemetryActivationPending = result.data.configSynced != true,
                                pairingLinkUnavailable = false
                            )
                            if (result.data.configSynced == true) return@withTimeout
                        }
                        is ApiResult.Error -> {
                            if (!isCurrentPoll(generation, carId)) return@withTimeout
                            _uiState.value = _uiState.value.copy(
                                telemetryErrorCode = result.details ?: "telemetry_error"
                            )
                        }
                    }
                    val delayMs = pollingPolicy.nextDelayMs(elapsedMs) ?: return@withTimeout
                    delay(delayMs)
                    elapsedMs += delayMs
                }
            }
        }
    }

    private fun stopTelemetryPolling() {
        pollGeneration++
        pollJob?.cancel()
        pollJob = null
    }

    private fun isCurrentLoad(generation: Long, carId: Int): Boolean =
        generation == loadGeneration && loadedCarId == carId

    private fun isCurrentConfigure(
        generation: Long,
        carId: Int,
        lease: TelemetryConfigureGate.Lease
    ): Boolean =
        lease.generation == generation &&
            generation == configureGeneration &&
            loadedCarId == carId &&
            configureLease === lease

    private fun isCurrentPoll(generation: Long, carId: Int): Boolean =
        pollingPolicy.shouldContinue(0L, generation, pollGeneration, pageIsActive) && loadedCarId == carId

    private fun migrationBindingRequired(eligibility: LegacyHistoryMigrationEligibility?): Boolean =
        eligibility?.reason == com.matelink.data.repository.LegacyHistoryMigrationBlockReason.ARCHIVE_MARKER_UNAVAILABLE

    fun migrateLegacyHistory() {
        val legacyCarId = loadedCarId ?: return
        val target = _uiState.value.migrationTarget ?: return
        val eligibility = _uiState.value.migrationEligibility ?: return
        if (!eligibility.eligible || _uiState.value.isMigrating) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isMigrating = true, error = null)
            try {
                migrationRepository.migrate(
                    legacyCarId = legacyCarId,
                    targetHistoryCarId = target.localHistoryCarId,
                    eligibility = eligibility
                )
                _uiState.value = _uiState.value.copy(
                    isMigrating = false,
                    migrationComplete = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isMigrating = false,
                    error = e.message
                )
            }
        }
    }

    /** Called only after the user explicitly confirms their legacy archive target. */
    fun recordExplicitUpgradeOrigin() {
        val carId = loadedCarId ?: return
        val car = loadedCar ?: return
        if (!_uiState.value.migrationBindingRequired || _uiState.value.isMigrating) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isMigrating = true, error = null)
            try {
                if (!vehicleContextRepository.recordExplicitUpgradeOrigin(car)) {
                    _uiState.value = _uiState.value.copy(isMigrating = false)
                    return@launch
                }
                load(carId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isMigrating = false, error = e.message)
            }
        }
    }

    override fun onCleared() {
        loadJob?.cancel()
        configureJob?.cancel()
        stopTelemetryPolling()
        super.onCleared()
    }
}

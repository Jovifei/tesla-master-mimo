package com.matelink.ui.screens.charges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.Units
import com.matelink.data.local.ChargeSessionStateDataStore
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.CurrentChargeOutcome
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.telemetry.SnapshotEvidence
import com.matelink.domain.telemetry.SnapshotFreshness
import com.matelink.domain.telemetry.snapshotEvidence
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CurrentChargeUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val chargeDetail: ChargeDetail? = null,
    val units: Units? = null,
    val stats: ChargeDetailStats? = null,
    val isDcCharge: Boolean = false,
    val isUnsupportedApi: Boolean = false,
    val isNotCharging: Boolean = false,
    /** Car status reports charging but the charge isn't in the API yet (TeslaMate DB lag at charge start). */
    val isChargeStarting: Boolean = false,
    val isDcFinishedPluggedIn: Boolean = false,
    val dcFinishedSince: String? = null,
    val timeToFullCharge: Double? = null,
    val chargeLimitSoc: Int? = null,
    val chargePortDoorOpen: Boolean? = null,
    val chargerPhases: Int? = null,
    val chargerVoltage: Double? = null,
    val chargerActualCurrent: Double? = null,
    val chargeCurrentRequest: Double? = null,
    val chargeCurrentRequestMax: Double? = null,
    val scheduledChargingStartTime: String? = null,
    val snapshotFreshness: SnapshotFreshness = SnapshotFreshness.UNAVAILABLE,
    val snapshotSource: String? = null,
    val snapshotObservedAt: String? = null,
    val snapshotMixedSources: Boolean = false,
    /** Charge points in chronological order (reversed from API's newest-first) */
    val chronologicalPoints: List<ChargePoint> = emptyList()
)

private data class VehicleStatusEvidence(
    val status: CarStatus?,
    val units: Units?,
    val evidence: SnapshotEvidence
)

@HiltViewModel
class CurrentChargeViewModel @Inject constructor(
    private val repository: TeslamateRepository,
    private val chargeSessionStateDataStore: ChargeSessionStateDataStore,
    private val vehicleContextRepository: VehicleContextRepository
) : ViewModel() {

    companion object {
        private const val REFRESH_INTERVAL_MS = 30_000L

        /** Poll quickly while waiting for a just-started charge to appear in the API. */
        private const val CHARGE_STARTING_REFRESH_INTERVAL_MS = 4_000L
    }

    private val _uiState = MutableStateFlow(CurrentChargeUiState())
    val uiState: StateFlow<CurrentChargeUiState> = _uiState.asStateFlow()

    private var carId: Int? = null
    private var refreshJob: Job? = null

    fun loadCurrentCharge(carId: Int) {
        this.carId = carId
        startRefreshLoop()
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                fetchData()
                val interval = if (_uiState.value.isChargeStarting) {
                    CHARGE_STARTING_REFRESH_INTERVAL_MS
                } else {
                    REFRESH_INTERVAL_MS
                }
                delay(interval)
            }
        }
    }

    private suspend fun fetchData() {
        val carId = this.carId ?: return
        val historyCarId = vehicleContextRepository.requireLocalHistoryCarId(carId)

        if (_uiState.value.chargeDetail == null) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }

        // Fetch current charge and the best available vehicle snapshot concurrently.
        val (chargeResult, vehicleStatus) = coroutineScope {
            val charge = async { repository.getCurrentCharge(carId) }
            val status = async { loadVehicleStatus(carId) }
            charge.await() to status.await()
        }

        val status = vehicleStatus.status
        val units = vehicleStatus.units ?: _uiState.value.units
        val timeToFullCharge = status?.timeToFullCharge
        val chargeLimitSoc = status?.chargeLimitSoc
        val chargePortDoorOpen = status?.chargingDetails?.chargePortDoorOpen
        val chargerPhases = status?.chargingDetails?.chargerPhases
        val chargerVoltage = status?.chargerVoltageValue
        val chargerActualCurrent = status?.chargerActualCurrentValue
        val chargeCurrentRequest = status?.chargeCurrentRequestValue
        val chargeCurrentRequestMax = status?.chargeCurrentRequestMaxValue
        val scheduledChargingStartTime = status?.scheduledChargingStartTime
        val isDcChargeFromStatus = status?.chargingDetails?.chargerPhases?.let { it == 0 }

        // Never preserve stale instantaneous charging values after a snapshot failure.
        _uiState.update {
            it.copy(
                units = units,
                timeToFullCharge = timeToFullCharge,
                chargeLimitSoc = chargeLimitSoc,
                chargePortDoorOpen = chargePortDoorOpen,
                chargerPhases = chargerPhases,
                chargerVoltage = chargerVoltage,
                chargerActualCurrent = chargerActualCurrent,
                chargeCurrentRequest = chargeCurrentRequest,
                chargeCurrentRequestMax = chargeCurrentRequestMax,
                scheduledChargingStartTime = scheduledChargingStartTime,
                snapshotFreshness = vehicleStatus.evidence.freshness,
                snapshotSource = vehicleStatus.evidence.source,
                snapshotObservedAt = vehicleStatus.evidence.observedAt,
                snapshotMixedSources = vehicleStatus.evidence.isMixed
            )
        }

        // Persist the DC flag while the session is still live; post-completion we can
        // only tell whether a session was DC from this stored value.
        if (status?.isCharging == true && status.isDcCharging) {
            chargeSessionStateDataStore.setLastSessionDc(historyCarId, true)
        } else if (status?.pluggedIn == false) {
            chargeSessionStateDataStore.clear(historyCarId)
        }

        val wasDcSession = chargeSessionStateDataStore.wasLastSessionDc(historyCarId)
        val isDcFinishedPluggedIn = status?.isChargeCompletePluggedIn == true && wasDcSession
        val stateSince = status?.stateSince

        when (chargeResult) {
            is ApiResult.Success -> when (val outcome = chargeResult.data) {
                is CurrentChargeOutcome.Active -> {
                    val detail = outcome.detail

                    // API returns charge_details sorted newest-first; reverse to chronological
                    val chronoPoints = detail.chargePoints?.reversed() ?: emptyList()
                    val detailWithChronoPoints = detail.copy(chargePoints = chronoPoints)

                    val stats = ChargeStatsCalculator.calculateStats(detailWithChronoPoints)
                    val isDcCharge = isDcChargeFromStatus ?: ChargeStatsCalculator.detectDcCharge(detailWithChronoPoints)

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isChargeStarting = false,
                            chargeDetail = detailWithChronoPoints,
                            stats = stats,
                            isDcCharge = isDcCharge,
                            isUnsupportedApi = false,
                            isNotCharging = detail.isCharging == false && !isDcFinishedPluggedIn,
                            isDcFinishedPluggedIn = isDcFinishedPluggedIn,
                            dcFinishedSince = if (isDcFinishedPluggedIn) stateSince else null,
                            chronologicalPoints = chronoPoints,
                            error = null
                        )
                    }
                }
                CurrentChargeOutcome.NoActiveCharge -> when {
                    status?.isCharging == true -> {
                        // The car is charging but TeslaMate hasn't materialized the charge
                        // in the API yet (happens for the first moments of every session).
                        // Show the "charge starting" state and let the loop poll fast.
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isChargeStarting = true,
                                                            error = null
                            )
                        }
                    }
                    isDcFinishedPluggedIn -> {
                        // DC charge finished but still plugged — keep showing last data with warning
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isChargeStarting = false,
                                isNotCharging = false,
                                isDcFinishedPluggedIn = true,
                                dcFinishedSince = stateSince,
                                error = null
                            )
                        }
                        // Keep refresh loop running to detect unplug
                    }
                    status == null -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isChargeStarting = false,
                                isNotCharging = false
                            )
                        }
                    }
                    else -> {
                        // Status confirms: charging has stopped and cable unplugged
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isChargeStarting = false,
                                isNotCharging = true,
                                isDcFinishedPluggedIn = false,
                                error = null
                            )
                        }
                        refreshJob?.cancel()
                    }
                }
            }
            is ApiResult.Error -> {
                when (chargeResult.code) {
                    404 -> {
                        _uiState.update {
                            it.copy(isLoading = false, isUnsupportedApi = true, error = null)
                        }
                        refreshJob?.cancel()
                    }
                    else -> {
                        // Network problem or server error — never treat as "not charging".
                        // Show the error, keep any data on screen and keep polling.
                        _uiState.update {
                            it.copy(isLoading = false, error = chargeResult.message)
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadVehicleStatus(carId: Int): VehicleStatusEvidence {
        return when (val adapter = repository.getAdapterSnapshot(carId)) {
            is ApiResult.Success -> VehicleStatusEvidence(
                status = adapter.data.status,
                units = adapter.data.units,
                evidence = snapshotEvidence(
                    adapter.data.source,
                    adapter.data.observedAt,
                    adapter.data.fieldSources
                )
            )
            is ApiResult.Error -> when (val legacy = repository.getCarStatus(carId)) {
                is ApiResult.Success -> VehicleStatusEvidence(
                    status = legacy.data.status,
                    units = legacy.data.units,
                    evidence = snapshotEvidence("teslamate_api", null, emptyMap())
                )
                is ApiResult.Error -> VehicleStatusEvidence(
                    status = null,
                    units = null,
                    evidence = snapshotEvidence(null, null, emptyMap())
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
    }
}

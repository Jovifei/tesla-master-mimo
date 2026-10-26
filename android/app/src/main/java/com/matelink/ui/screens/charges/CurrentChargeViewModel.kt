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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.domain.analytics.AnalysisPageIdentity
import com.matelink.util.parseIsoInstant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import com.matelink.domain.analytics.captureAnalysisPageIdentity
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CurrentChargeUiState(
    val isLoading: Boolean = true,
    val isCachedDetail: Boolean = false,
    val error: String? = null,
    val chargeDetail: ChargeDetail? = null,
    val units: Units? = null,
    val stats: ChargeDetailStats? = null,
    val isDcCharge: Boolean? = null,
    val isUnsupportedApi: Boolean = false,
    val isNotCharging: Boolean = false,
    /** Car status reports charging but the charge isn't in the API yet (TeslaMate DB lag at charge start). */
    val isChargeStarting: Boolean = false,
    val isDcFinishedPluggedIn: Boolean = false,
    val dcFinishedSince: String? = null,
    val timeToFullCharge: Double? = null,
    val chargeLimitSoc: Int? = null,
    val instantPowerKw: Double? = null,
    val currentAddedEnergyKwh: Double? = null,
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

internal data class VehicleStatusEvidence(
    val status: CarStatus?,
    val units: Units?,
    val evidence: SnapshotEvidence
)

internal interface CurrentChargePageSource {
    val scopeChanges: kotlinx.coroutines.flow.Flow<Unit> get() = kotlinx.coroutines.flow.emptyFlow()
    suspend fun identity(carId: Int): com.matelink.domain.analytics.AnalysisPageIdentity
    suspend fun current(carId: Int): ApiResult<CurrentChargeOutcome>
    suspend fun status(carId: Int): VehicleStatusEvidence
    suspend fun setLastDc(localId: Int, dc: Boolean)
    suspend fun clearSession(localId: Int)
    suspend fun wasLastDc(localId: Int): Boolean
}

private class RepositoryCurrentChargeSource(
    private val repository: TeslamateRepository,
    private val sessions: ChargeSessionStateDataStore,
    private val contexts: VehicleContextRepository
) : CurrentChargePageSource {
    override val scopeChanges = contexts.historyScopeChanges.map { Unit }
    override suspend fun identity(carId: Int) = contexts.captureAnalysisPageIdentity(carId)
    override suspend fun current(carId: Int) = repository.getCurrentCharge(carId)
    override suspend fun setLastDc(localId: Int, dc: Boolean) = sessions.setLastSessionDc(localId, dc)
    override suspend fun clearSession(localId: Int) = sessions.clear(localId)
    override suspend fun wasLastDc(localId: Int) = sessions.wasLastSessionDc(localId)
    override suspend fun status(carId: Int): VehicleStatusEvidence {
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
                    evidence = snapshotEvidence(legacy.metadata?.source ?: "teslamate_api", legacy.metadata?.observedAt, emptyMap())
                )
                is ApiResult.Error -> VehicleStatusEvidence(
                    status = null,
                    units = null,
                    evidence = snapshotEvidence(null, null, emptyMap())
                )
            }
        }
    }

}

@HiltViewModel
class CurrentChargeViewModel internal constructor(private val source: CurrentChargePageSource) : ViewModel() {
    @Inject constructor(repository: TeslamateRepository, sessions: ChargeSessionStateDataStore,
        contexts: VehicleContextRepository) : this(RepositoryCurrentChargeSource(repository, sessions, contexts))

    companion object {
        private const val REFRESH_INTERVAL_MS = 30_000L

        /** Poll quickly while waiting for a just-started charge to appear in the API. */
        private const val CHARGE_STARTING_REFRESH_INTERVAL_MS = 4_000L
    }

    private val _uiState = MutableStateFlow(CurrentChargeUiState())
    val uiState: StateFlow<CurrentChargeUiState> = _uiState.asStateFlow()

    private var carId: Int? = null
    private var refreshJob: Job? = null
    private var generation = 0L
    private var activeIdentity: AnalysisPageIdentity? = null

    init {
        viewModelScope.launch {
            source.scopeChanges.collect {
                val previous = activeIdentity
                if (previous?.stillCurrent?.invoke() == false) {
                    generation++
                    val request = generation
                    val remote = carId
                    refreshJob?.cancel()
                    activeIdentity = null
                    _uiState.value = CurrentChargeUiState(isLoading = false, error = "history_identity_unavailable")
                    val renewed = try {
                        remote?.let { withTimeoutOrNull(2_000L) { source.identity(it) } }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (generation == request && carId == remote && renewed != null &&
                        renewed.context == previous.context && renewed.stillCurrent()) {
                        activeIdentity = renewed
                        startRefreshLoop()
                    }
                }
            }
        }
    }

    fun loadCurrentCharge(carId: Int) {
        if (this.carId != carId) _uiState.value = CurrentChargeUiState()
        this.carId = carId
        generation++
        startRefreshLoop()
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                try {
                    withTimeout(20_000L) { fetchData() }
                } catch (e: TimeoutCancellationException) {
                    _uiState.update { it.copy(isLoading = false, error = "history_sync_timeout") }
                } catch (e: HistoryIdentityUnavailableException) {
                    _uiState.value = CurrentChargeUiState(isLoading = false, error = "history_identity_unavailable")
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _uiState.update { it.copy(isLoading = false, error = "history_read_failed") }
                }
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
        val request = generation
        val identity = source.identity(carId)
        suspend fun checkCurrent() {
            currentCoroutineContext().ensureActive()
            if (request != generation || this.carId != carId || !identity.stillCurrent())
                throw HistoryIdentityUnavailableException()
        }
        checkCurrent()
        activeIdentity = identity
        val historyCarId = identity.context.localHistoryCarId

        if (_uiState.value.chargeDetail == null) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }

        // Fetch current charge and the best available vehicle snapshot concurrently.
        val (chargeResult, vehicleStatus) = coroutineScope {
            val charge = async { source.current(carId) }
            val status = async { source.status(carId) }
            charge.await() to status.await()
        }

        checkCurrent()
        val status = vehicleStatus.status
        val units = vehicleStatus.units ?: _uiState.value.units
        val timeToFullCharge = status?.timeToFullCharge?.takeIf { it.isFinite() && it >= 0.0 }
        val chargeLimitSoc = status?.chargeLimitSoc?.takeIf { it in 0..100 }
        val chargePortDoorOpen = status?.chargingDetails?.chargePortDoorOpen
        val chargerPhases = status?.chargingDetails?.chargerPhases
        val chargerVoltage = status?.chargerVoltageValue?.takeIf { it.isFinite() && it >= 0.0 }
        val chargerActualCurrent = status?.chargerActualCurrentValue?.takeIf { it.isFinite() && it >= 0.0 }
        val chargeCurrentRequest = status?.chargeCurrentRequestValue?.takeIf { it.isFinite() && it >= 0.0 }
        val chargeCurrentRequestMax = status?.chargeCurrentRequestMaxValue?.takeIf { it.isFinite() && it >= 0.0 }
        val scheduledChargingStartTime = status?.scheduledChargingStartTime
        val isDcChargeFromStatus = status?.chargingDetails?.chargerPhases?.let { phases ->
            when (phases) {
                0 -> true
                in 1..3 -> false
                else -> null
            }
        }

        // Persist the DC flag while the session is still live; post-completion we can
        // only tell whether a session was DC from this stored value.
        if (status?.isCharging == true && status.isDcCharging) {
            checkCurrent()
            source.setLastDc(historyCarId, true)
            checkCurrent()
        } else if (status?.isCharging == true && isDcChargeFromStatus == false) {
            checkCurrent()
            source.setLastDc(historyCarId, false)
            checkCurrent()
        } else if (status?.pluggedIn == false) {
            checkCurrent()
            source.clearSession(historyCarId)
            checkCurrent()
        }

        val wasDcSession = source.wasLastDc(historyCarId)
        checkCurrent()
        if (chargeResult is ApiResult.Error) {
            if (chargeResult.code == 404) {
                _uiState.value = CurrentChargeUiState(isLoading = false, isUnsupportedApi = true)
                refreshJob?.cancel()
            } else _uiState.update { old -> old.copy(isLoading = false, isCachedDetail = old.chargeDetail != null,
                isChargeStarting = false, isNotCharging = false, isDcFinishedPluggedIn = false, dcFinishedSince = null,
                instantPowerKw = null, currentAddedEnergyKwh = null, timeToFullCharge = null, chargeLimitSoc = null,
                chargePortDoorOpen = null, chargerPhases = null, chargerVoltage = null, chargerActualCurrent = null,
                chargeCurrentRequest = null, chargeCurrentRequestMax = null, scheduledChargingStartTime = null,
                snapshotFreshness = if (old.chargeDetail != null) SnapshotFreshness.HISTORY else SnapshotFreshness.UNAVAILABLE,
                error = chargeResult.message) }
            return
        }

        // Never preserve stale instantaneous charging values after a snapshot failure.
        _uiState.update {
            it.copy(
                instantPowerKw = currentChargeInstantPower(
                    ((chargeResult as? ApiResult.Success)?.data as? CurrentChargeOutcome.Active)?.detail, vehicleStatus),
                currentAddedEnergyKwh = currentChargeAddedEnergy(
                    ((chargeResult as? ApiResult.Success)?.data as? CurrentChargeOutcome.Active)?.detail, vehicleStatus),
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

        val isDcFinishedPluggedIn = status?.isChargeCompletePluggedIn == true && wasDcSession
        // Vehicle online/state time is not proof of when charging finished.
        val activeDetail = ((chargeResult as? ApiResult.Success)?.data as? CurrentChargeOutcome.Active)?.detail
        val stateSince = activeDetail?.takeIf { it.isCharging != true }?.endDate
            ?: _uiState.value.chargeDetail?.takeIf { it.isCharging != true }?.endDate

        when (chargeResult) {
            is ApiResult.Success -> when (val outcome = chargeResult.data) {
                is CurrentChargeOutcome.Active -> {
                    val detail = outcome.detail

                    // API returns charge_details sorted newest-first; reverse to chronological
                    val chronoPoints = currentChargeChronologicalPoints(detail)
                    val detailWithChronoPoints = detail.copy(chargePoints = chronoPoints)

                    val stats = currentChargeStats(detailWithChronoPoints)
                    val isDcCharge = isDcChargeFromStatus ?: ChargeStatsCalculator.detectChargeType(detailWithChronoPoints).toDcFlag()

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isChargeStarting = false,
                            isCachedDetail = false,
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
                                chargeDetail = null, stats = null, chronologicalPoints = emptyList(),
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
                                isNotCharging = false, chargeDetail = null, stats = null, chronologicalPoints = emptyList()
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
            is ApiResult.Error -> Unit // handled before any new snapshot publication
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

private fun ChargeType.toDcFlag(): Boolean? = when (this) {
    ChargeType.DC -> true
    ChargeType.AC -> false
    ChargeType.UNKNOWN -> null
}

internal fun currentChargeChronologicalPoints(detail: ChargeDetail): List<ChargePoint> =
    detail.chargePoints.orEmpty().mapNotNull { point -> parseIsoInstant(point.date)?.let { it to point } }
        .sortedBy { it.first }.map { it.second }

internal fun currentChargeEnergyKwh(detail: ChargeDetail): Double? {
    if (detail.sourceRecordIdentity.conflicted) return null
    val receipt = detail.currentEnergyReceipt
    if (receipt != null) {
        if (receipt.version != 1 || receipt.unit != "kWh" || receipt.source != "fleet_api" ||
            receipt.sourceField != "charge_state.charge_energy_added" ||
            receipt.method != "provider_current_plug_counter" || receipt.quality != "reported" ||
            parseIsoInstant(receipt.observedAt) == null) return null
        return receipt.valueKwh?.takeIf { it.isFinite() && it >= 0.0 }
    }
    return detail.batteryInputKwh
}

internal fun currentChargeStats(detail: ChargeDetail): ChargeDetailStats {
    // These are sample extrema/means, not a claim about the unobserved plug-in boundary.
    val times = currentChargeChronologicalPoints(detail).mapNotNull { parseIsoInstant(it.date) }
    val sampled = if (detail.isCharging == true && times.isNotEmpty()) detail.copy(
        startDate = detail.startDate ?: times.first().toString(),
        endDate = detail.endDate ?: times.last().toString()) else detail
    return ChargeStatsCalculator.calculateStats(sampled).copy(
        energyAdded = detail.batteryInputKwh, energyUsed = detail.inputEnergyKwh,
        efficiency = com.matelink.domain.analytics.qualifiedAcEnergyBalance(
            detail.energyContract, detail.startDate, detail.endDate, detail.chargeType)?.efficiencyPercent)
}

internal fun currentChargeInstantPower(detail: ChargeDetail?, snapshot: VehicleStatusEvidence,
    now: java.time.Instant = java.time.Instant.now()): Double? {
    fun fresh(source: String?, observedAt: String?): Boolean {
        val observed = parseIsoInstant(observedAt) ?: return false
        return source in setOf("fleet_api", "telemetry_mqtt", "live_mqtt") &&
            !observed.isAfter(now.plusSeconds(5)) && !observed.isBefore(now.minusSeconds(120))
    }
    fun admitted(value: Double?) = value?.takeIf { it.isFinite() && it >= 0.0 && it.toFloat().isFinite() }
    if (fresh(snapshot.evidence.fieldSources["charger_power"] ?: snapshot.evidence.source, snapshot.evidence.observedAt))
        admitted(snapshot.status?.chargerPowerValue)?.let { return it }
    if (fresh(detail?.source, detail?.observedAt)) return admitted(detail?.chargerPower)
    return null
}

internal fun currentChargeAddedEnergy(detail: ChargeDetail?, snapshot: VehicleStatusEvidence,
    now: java.time.Instant = java.time.Instant.now()): Double? {
    if (detail == null || detail.sourceRecordIdentity.conflicted) return null
    val qualified = currentChargeEnergyKwh(detail)
    if (qualified != null || detail.currentEnergyReceipt != null) return qualified
    // This independently received official current-plug counter does not
    // certify the history/window metric or resurrect an unqualified raw scalar.
    val observed = parseIsoInstant(snapshot.evidence.observedAt) ?: return null
    if (snapshot.evidence.source != "fleet_api" || observed.isAfter(now.plusSeconds(5)) ||
        observed.isBefore(now.minusSeconds(120)) ||
        snapshot.evidence.fieldSources["charge_energy_added"]?.let { it != "fleet_api" } == true) return null
    val status = snapshot.status ?: return null
    if (!status.isCharging && !status.isChargeCompletePluggedIn) return null
    return status.chargingDetails?.chargeEnergyAdded?.takeIf { it.isFinite() && it >= 0.0 }
}

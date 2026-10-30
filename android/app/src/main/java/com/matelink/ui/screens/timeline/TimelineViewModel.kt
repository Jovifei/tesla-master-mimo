package com.matelink.ui.screens.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.matelink.R
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.VerifiedHistoryReadContext
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.model.Currency
import com.matelink.domain.analytics.ChargeCostSource
import com.matelink.domain.analytics.EffectiveChargeCost
import com.matelink.domain.analytics.findChargeCostOverride
import com.matelink.domain.analytics.projectEffectiveChargeCost
import com.matelink.domain.history.LatestHistoryLoad
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.matelink.util.parseIsoInstant
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

sealed class TimelineEvent {
    abstract val timestamp: String
    abstract val date: String
    abstract val time: String
    abstract val instant: Instant

    data class DriveEvent(
        val driveId: Int,
        override val timestamp: String,
        override val date: String,
        override val time: String,
        override val instant: Instant,
        val startAddress: String?,
        val endAddress: String?,
        val distance: Double?, // Canonical kilometres for the timeline's km label.
        val durationMin: Int?,
        val durationStr: String?,
        val startBatteryLevel: Int?,
        val endBatteryLevel: Int?,
        val efficiencyWhKm: Double?
    ) : TimelineEvent()

    data class ChargeEvent(
        val chargeId: Int,
        override val timestamp: String,
        override val date: String,
        override val time: String,
        override val instant: Instant,
        val address: String?,
        val energyAdded: Double?,
        val cost: Double?,
        val currencySymbol: String? = null,
        val costSource: ChargeCostSource = ChargeCostSource.UNAVAILABLE,
        val costQualificationReason: String? = null,
        val durationMin: Int?,
        val durationStr: String?,
        val startBatteryLevel: Int?,
        val endBatteryLevel: Int?
    ) : TimelineEvent()
}

data class TimelineUiState(
    val isLoading: Boolean = true,
    val events: List<TimelineEvent> = emptyList(),
    val groupedEvents: Map<String, List<TimelineEvent>> = emptyMap(),
    val error: String? = null
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: UnifiedHistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val settingsDataStore: SettingsDataStore,
    private val chargeCostOverrideStore: ChargeCostOverrideStore,
    private val vehicleContexts: VehicleContextRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()
    private val latestLoad = LatestHistoryLoad()
    private var activeHistoryProof: VerifiedHistoryReadContext? = null

    init {
        viewModelScope.launch {
            settingsRepository.currentCarId.collect { carId ->
                val proof = activeHistoryProof
                if (proof != null && proof.context.remoteApiCarId != carId) {
                    activeHistoryProof = null
                    _uiState.value = TimelineUiState(isLoading = true)
                }
                loadTimeline()
            }
        }
        viewModelScope.launch {
            vehicleContexts.historyScopeChanges.collect { scope ->
                val proof = activeHistoryProof
                if (proof != null && proof.scope != scope) {
                    activeHistoryProof = null
                    _uiState.value = TimelineUiState(isLoading = true)
                    loadTimeline()
                }
            }
        }
    }

    fun refresh() {
        loadTimeline()
    }

    private fun loadTimeline() {
        latestLoad.launch(viewModelScope) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            var capturedScope: HistoryReadScope? = null
            var requestedCarId: Int? = null
            suspend fun publishFailure(message: String) {
                val retain = canRetainPublishedHistory(requestedCarId, capturedScope)
                ensureCurrent()
                val previous = _uiState.value
                _uiState.value = if (retain && previous.events.isNotEmpty()) {
                    previous.copy(isLoading = false, error = message)
                } else {
                    activeHistoryProof = null
                    TimelineUiState(isLoading = false, error = message)
                }
            }
            try {
                val carId = settingsRepository.currentCarId.first()
                requestedCarId = carId
                capturedScope = vehicleContexts.captureReadScope()
                if (activeHistoryProof != null && !canRetainPublishedHistory(carId, capturedScope)) {
                    ensureCurrent()
                    activeHistoryProof = null
                    _uiState.value = TimelineUiState(isLoading = true)
                }

                val history = when (val result = repository.load(carId)) {
                    is ApiResult.Success -> result.data
                    is ApiResult.Error -> {
                        ensureCurrent()
                        if (vehicleContexts.captureReadScope() != capturedScope) return@launch
                        if (settingsRepository.currentCarId.first() != carId) return@launch
                        publishFailure(appContext.getString(R.string.error_loading_data))
                        return@launch
                    }
                }

                val proof = history.verifiedReadContext ?: run {
                    ensureCurrent()
                    activeHistoryProof = null
                    _uiState.value = TimelineUiState(isLoading = false, error = "history_identity_unavailable")
                    return@launch
                }
                if (proof.scope != capturedScope) throw CancellationException("Timeline scope changed")
                suspend fun checkProof() {
                    ensureCurrent()
                    if (settingsRepository.currentCarId.first() != carId ||
                        !repository.isContextCurrent(proof)) throw CancellationException("Timeline scope changed")
                }
                checkProof()
                val records = chargeCostOverrideStore.getAllRecords()
                val currency = settingsDataStore.settings.first().currencyCode
                checkProof()
                val events = mergeTimelineEvents(history.drives, history.charges) { charge ->
                    projectEffectiveChargeCost(charge, proof.context.localHistoryCarId,
                        findChargeCostOverride(records, proof.context.localHistoryCarId, charge, currency), currency)
                }
                val grouped = events.groupBy { it.date }
                val warning = history.drivesSyncError ?: history.chargesSyncError
                checkProof()
                activeHistoryProof = proof
                _uiState.value = TimelineUiState(
                    isLoading = false,
                    events = events,
                    groupedEvents = grouped,
                    error = warning?.let {
                        appContext.getString(if (it == "history_partial") R.string.history_sync_partial else R.string.history_sync_cached)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureCurrent()
                val scope = capturedScope
                if (scope != null && runCatching { vehicleContexts.captureReadScope() }.getOrNull() != scope)
                    return@launch
                publishFailure(e.message ?: appContext.getString(R.string.error_loading_data))
            }
        }
    }

    /** An account scope alone cannot authorize retaining another vehicle's published events. */
    private suspend fun canRetainPublishedHistory(carId: Int?, scope: HistoryReadScope?): Boolean {
        val proof = activeHistoryProof ?: return false
        if (carId == null || scope == null || proof.scope != scope || proof.context.remoteApiCarId != carId)
            return false
        return settingsRepository.currentCarId.first() == carId && repository.isContextCurrent(proof)
    }
}

private data class TimelineMoment(val instant: Instant, val date: String, val time: String)

private fun timelineMoment(raw: String?): TimelineMoment? {
    val instant = parseIsoInstant(raw) ?: return null
    val local = instant.atZone(ZoneId.systemDefault())
    return TimelineMoment(
        instant = instant,
        date = local.toLocalDate().toString(),
        time = local.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    )
}

/** Sort by physical time; equal instants keep a deterministic type/ID order. */
internal fun mergeTimelineEvents(
    drives: List<DriveData>, charges: List<ChargeData>,
    costFor: (ChargeData) -> EffectiveChargeCost = { EffectiveChargeCost(null, ChargeCostSource.UNAVAILABLE) }
): List<TimelineEvent> {
    val events = drives.mapNotNull { it.toTimelineDriveEvent() } +
        charges.mapNotNull { it.toTimelineChargeEvent(costFor(it)) }
    return events.sortedWith(
        compareByDescending<TimelineEvent> { it.instant }
            .thenBy { if (it is TimelineEvent.DriveEvent) 0 else 1 }
            .thenBy { event ->
                when (event) {
                    is TimelineEvent.DriveEvent -> event.driveId
                    is TimelineEvent.ChargeEvent -> event.chargeId
                }
            }
    )
}

/** Projection consumed by the timeline; invalid timestamps never become a dated event. */
internal fun DriveData.toTimelineDriveEvent(): TimelineEvent.DriveEvent? {
    val startTs = startDate ?: return null
    val moment = timelineMoment(startTs) ?: return null
    return TimelineEvent.DriveEvent(
        driveId = id,
        timestamp = startTs,
        date = moment.date,
        time = moment.time,
        instant = moment.instant,
        startAddress = startAddress,
        endAddress = endAddress,
        distance = distanceKm,
        durationMin = durationMin,
        durationStr = durationStr,
        startBatteryLevel = startBatteryLevel,
        endBatteryLevel = endBatteryLevel,
        efficiencyWhKm = efficiencyWhKm
    )
}

private fun ChargeData.toTimelineChargeEvent(effectiveCost: EffectiveChargeCost): TimelineEvent.ChargeEvent? {
    val startTs = startDate ?: return null
    val moment = timelineMoment(startTs) ?: return null
    return TimelineEvent.ChargeEvent(
        chargeId = chargeId,
        timestamp = startTs,
        date = moment.date,
        time = moment.time,
        instant = moment.instant,
        address = address,
        energyAdded = chargeEnergyAdded,
        cost = effectiveCost.cost,
        currencySymbol = effectiveCost.currencyCode?.let { Currency.findByCode(it).symbol },
        costSource = effectiveCost.source,
        costQualificationReason = effectiveCost.qualificationReason,
        durationMin = durationMin,
        durationStr = durationStr,
        startBatteryLevel = startBatteryLevel,
        endBatteryLevel = endBatteryLevel
    )
}

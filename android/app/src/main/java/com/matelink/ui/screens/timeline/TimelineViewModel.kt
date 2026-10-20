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
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    init {
        loadTimeline()
    }

    fun refresh() {
        loadTimeline()
    }

    private fun loadTimeline() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val carId = settingsRepository.currentCarId.first()

                val history = when (val result = repository.load(carId)) {
                    is ApiResult.Success -> result.data
                    is ApiResult.Error -> {
                        _uiState.value = TimelineUiState(
                            isLoading = false, error = appContext.getString(R.string.error_loading_data)
                        )
                        return@launch
                    }
                }

                val events = mergeTimelineEvents(history.drives, history.charges)
                val grouped = events.groupBy { it.date }
                val warning = history.drivesSyncError ?: history.chargesSyncError

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
                _uiState.value = TimelineUiState(
                    isLoading = false,
                    error = e.message
                )
            }
        }
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
internal fun mergeTimelineEvents(drives: List<DriveData>, charges: List<ChargeData>): List<TimelineEvent> {
    val events = drives.mapNotNull { it.toTimelineDriveEvent() } + charges.mapNotNull { it.toTimelineChargeEvent() }
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

private fun ChargeData.toTimelineChargeEvent(): TimelineEvent.ChargeEvent? {
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
        cost = cost,
        durationMin = durationMin,
        durationStr = durationStr,
        startBatteryLevel = startBatteryLevel,
        endBatteryLevel = endBatteryLevel
    )
}

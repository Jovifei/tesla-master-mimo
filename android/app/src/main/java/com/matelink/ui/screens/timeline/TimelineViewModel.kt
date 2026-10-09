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
import javax.inject.Inject

sealed class TimelineEvent {
    abstract val timestamp: String
    abstract val date: String

    data class DriveEvent(
        val driveId: Int,
        override val timestamp: String,
        override val date: String,
        val startAddress: String?,
        val endAddress: String?,
        val distance: Double?,
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

                val events = mergeTimeline(history.drives, history.charges)
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

    private fun mergeTimeline(
        drives: List<DriveData>,
        charges: List<ChargeData>
    ): List<TimelineEvent> {
        val driveEvents = drives.map { drive ->
            val startTs = drive.startDate ?: ""
            TimelineEvent.DriveEvent(
                driveId = drive.id,
                timestamp = startTs,
                date = extractDate(startTs),
                startAddress = drive.startAddress,
                endAddress = drive.endAddress,
                distance = drive.distance,
                durationMin = drive.durationMin,
                durationStr = drive.durationStr,
                startBatteryLevel = drive.startBatteryLevel,
                endBatteryLevel = drive.endBatteryLevel,
                efficiencyWhKm = drive.efficiencyWhKm
            )
        }

        val chargeEvents = charges.map { charge ->
            val startTs = charge.startDate ?: ""
            TimelineEvent.ChargeEvent(
                chargeId = charge.chargeId,
                timestamp = startTs,
                date = extractDate(startTs),
                address = charge.address,
                energyAdded = charge.chargeEnergyAdded,
                cost = charge.cost,
                durationMin = charge.durationMin,
                durationStr = charge.durationStr,
                startBatteryLevel = charge.startBatteryLevel,
                endBatteryLevel = charge.endBatteryLevel
            )
        }

        return (driveEvents + chargeEvents)
            .sortedByDescending { it.timestamp }
    }

    private fun extractDate(isoTimestamp: String): String {
        // Extract YYYY-MM-DD from ISO 8601 timestamp
        return isoTimestamp.take(10).ifEmpty { "Unknown" }
    }
}

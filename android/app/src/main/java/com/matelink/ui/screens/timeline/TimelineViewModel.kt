package com.matelink.ui.screens.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TeslamateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val repository: TeslamateRepository,
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

                val drivesResult = repository.getDrives(carId)
                val chargesResult = repository.getCharges(carId)

                val drives = when (drivesResult) {
                    is com.matelink.data.repository.ApiResult.Success -> drivesResult.data
                    is com.matelink.data.repository.ApiResult.Error -> emptyList()
                }
                val charges = when (chargesResult) {
                    is com.matelink.data.repository.ApiResult.Success -> chargesResult.data
                    is com.matelink.data.repository.ApiResult.Error -> emptyList()
                }

                val events = mergeTimeline(drives, charges)
                val grouped = events.groupBy { it.date }

                _uiState.value = TimelineUiState(
                    isLoading = false,
                    events = events,
                    groupedEvents = grouped
                )
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

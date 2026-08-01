package com.matelink.ui.screens.vampire

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.AnalysisWindow
import com.matelink.domain.analytics.StandbyCause
import com.matelink.domain.analytics.standbyAttribution
import com.matelink.domain.analytics.selectWindow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Represents a single idle-drain period between two charge sessions.
 *
 * @param startDate     When the previous charge ended (idle period start).
 * @param endDate       When the next charge started (idle period end).
 * @param drainPercent  Battery % dropped while idle (no drive in this window).
 * @param hoursIdle     Duration of the idle period in hours.
 * @param avgPowerW     Estimated average power draw in watts.
 * @param dateKey       Date string for grouping (yyyy-MM-dd).
 */
data class IdleDrainPeriod(
    val startDate: String,
    val endDate: String,
    val drainPercent: Int,
    val hoursIdle: Double,
    val avgPowerW: Double,
    val dateKey: String,
    val energyKwh: Double,
    val location: String?,
    val cause: StandbyCause,
    val confidence: Float
)

data class VampireUiState(
    val isLoading: Boolean = true,
    val selectedWindow: AnalysisWindow = AnalysisWindow.ALL_TIME,
    val totalDrainPercent: Int = 0,
    val totalDrainKwh: Double = 0.0,
    val avgPowerW: Double = 0.0,
    val idlePeriods: List<IdleDrainPeriod> = emptyList(),
    val dailyDrains: List<DailyDrain> = emptyList(),
    val error: String? = null
)

data class DailyDrain(
    val date: String,
    val totalDrainPercent: Int,
    val totalDrainKwh: Double,
    val avgPowerW: Double,
    val periodCount: Int
)

@HiltViewModel
class VampireViewModel @Inject constructor(
    private val historyRepository: AnalysisHistoryRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VampireUiState())
    val uiState = _uiState.asStateFlow()
    private var allPeriods: List<IdleDrainPeriod> = emptyList()
    private var customStart: LocalDate? = null
    private var customEnd: LocalDate? = null

    companion object {
        // Approximate usable battery capacity in kWh for power estimation.
        // Used when rated range data is unavailable. 75 kWh is a common midpoint
        // across Model 3/Y variants (LR/Performance).
        private const val ESTIMATED_BATTERY_KWH = 75.0
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = VampireUiState(isLoading = true)
            try {
                val carId = settingsRepository.currentCarId.first()

                val historyResult = historyRepository.load(carId)

                if (historyResult is ApiResult.Error) {
                    _uiState.value = VampireUiState(
                        isLoading = false,
                        error = historyResult.message
                    )
                    return@launch
                }

                val history = (historyResult as ApiResult.Success).data
                val charges = history.charges
                val drives = history.drives

                val periods = computeIdleDrainPeriods(charges, drives)

                allPeriods = periods
                recalculate(_uiState.value.selectedWindow)
            } catch (e: Exception) {
                _uiState.value = VampireUiState(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    fun selectWindow(window: AnalysisWindow) {
        customStart = null
        customEnd = null
        _uiState.value = _uiState.value.copy(selectedWindow = window)
        recalculate(window)
    }

    fun selectCustomRange(start: LocalDate, end: LocalDate) {
        customStart = start
        customEnd = end
        _uiState.value = _uiState.value.copy(selectedWindow = AnalysisWindow.CUSTOM)
        recalculate(AnalysisWindow.CUSTOM)
    }

    private fun recalculate(window: AnalysisWindow) {
        val dated = allPeriods.mapNotNull { period ->
            runCatching { LocalDate.parse(period.dateKey) }.getOrNull()?.let { DatedIdle(it, period) }
        }
        val selected = selectWindow(dated, window, LocalDate.now(), customStart, customEnd).map { it.period }
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            totalDrainPercent = selected.sumOf { it.drainPercent },
            totalDrainKwh = selected.sumOf { it.energyKwh },
            avgPowerW = selected.takeIf { it.isNotEmpty() }?.map { it.avgPowerW }?.average() ?: 0.0,
            idlePeriods = selected,
            dailyDrains = groupByDay(selected)
        )
    }

    /**
     * Identify idle-drain periods between consecutive charges.
     *
     * Algorithm:
     * 1. Sort charges chronologically by start_date.
     * 2. For each pair of consecutive charges (prev end -> next start),
     *    check if any drive occurred in that window.
     * 3. If no drive: the battery % drop from prev.end to next.start is
     *    vampire drain.
     * 4. If a drive occurred: skip (the drop is from driving, not standby).
     */
    private fun computeIdleDrainPeriods(
        charges: List<ChargeData>,
        drives: List<DriveData>
    ): List<IdleDrainPeriod> {
        if (charges.size < 2) return emptyList()

        val sortedCharges = charges
            .filter { it.startDate != null && it.endDate != null }
            .sortedBy { it.startDate }

        val sortedDrives = drives
            .filter { it.startDate != null && it.endDate != null }
            .sortedBy { it.startDate }

        val periods = mutableListOf<IdleDrainPeriod>()

        for (i in 0 until sortedCharges.size - 1) {
            val prevCharge = sortedCharges[i]
            val nextCharge = sortedCharges[i + 1]

            val prevEndDate = prevCharge.endDate ?: continue
            val nextStartDate = nextCharge.startDate ?: continue

            val prevEndLevel = prevCharge.endBatteryLevel ?: continue
            val nextStartLevel = nextCharge.startBatteryLevel ?: continue

            val drainDrop = prevEndLevel - nextStartLevel

            // Only consider periods where battery actually dropped
            if (drainDrop <= 0) continue

            // Check if any drive occurred between prev charge end and next charge start
            val hasDrive = sortedDrives.any { drive ->
                val driveStart = drive.startDate ?: return@any false
                val driveEnd = drive.endDate ?: return@any false
                isOverlapping(prevEndDate, nextStartDate, driveStart, driveEnd)
            }

            if (hasDrive) continue

            // Calculate idle hours
            val hoursIdle = try {
                val prevEnd = OffsetDateTime.parse(prevEndDate)
                val nextStart = OffsetDateTime.parse(nextStartDate)
                val minutes = ChronoUnit.MINUTES.between(prevEnd, nextStart)
                if (minutes > 0) minutes / 60.0 else continue
            } catch (e: Exception) {
                continue
            }

            // Estimate average power draw (watts)
            // drainDrop% of battery capacity over the idle hours
            val energyWh = (drainDrop / 100.0) * ESTIMATED_BATTERY_KWH * 1000.0
            val avgPowerW = if (hoursIdle > 0) energyWh / hoursIdle else 0.0
            val attribution = standbyAttribution(false, false, false)

            val dateKey = try {
                OffsetDateTime.parse(prevEndDate).toLocalDate().toString()
            } catch (e: Exception) {
                prevEndDate.take(10)
            }

            periods.add(
                IdleDrainPeriod(
                    startDate = prevEndDate,
                    endDate = nextStartDate,
                    drainPercent = drainDrop,
                    hoursIdle = hoursIdle,
                    avgPowerW = avgPowerW,
                    dateKey = dateKey,
                    energyKwh = energyWh / 1000.0,
                    location = prevCharge.address ?: nextCharge.address,
                    cause = attribution.cause,
                    // A battery drop proves detected consumption, not its cause.
                    confidence = attribution.confidence
                )
            )
        }

        return periods.sortedByDescending { it.startDate }
    }

    /**
     * Check if two time ranges overlap.
     */
    private fun isOverlapping(
        rangeAStart: String, rangeAEnd: String,
        rangeBStart: String, rangeBEnd: String
    ): Boolean {
        return try {
            val aStart = OffsetDateTime.parse(rangeAStart)
            val aEnd = OffsetDateTime.parse(rangeAEnd)
            val bStart = OffsetDateTime.parse(rangeBStart)
            val bEnd = OffsetDateTime.parse(rangeBEnd)
            aStart.isBefore(bEnd) && bStart.isBefore(aEnd)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Group idle drain periods by date and aggregate.
     */
    private fun groupByDay(periods: List<IdleDrainPeriod>): List<DailyDrain> {
        return periods
            .groupBy { it.dateKey }
            .map { (date, dayPeriods) ->
                DailyDrain(
                    date = date,
                    totalDrainPercent = dayPeriods.sumOf { it.drainPercent },
                    totalDrainKwh = dayPeriods.sumOf { it.energyKwh },
                    avgPowerW = dayPeriods.map { it.avgPowerW }.average(),
                    periodCount = dayPeriods.size
                )
            }
            .sortedByDescending { it.date }
            .take(30) // Show last 30 days
    }

    fun refresh() = load()

    private data class DatedIdle(
        override val date: LocalDate,
        val period: IdleDrainPeriod
    ) : com.matelink.domain.analytics.DatedSourceRecord {
        override val id: Int get() = period.startDate.hashCode()
    }
}

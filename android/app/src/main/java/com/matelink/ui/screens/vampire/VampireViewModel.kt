package com.matelink.ui.screens.vampire

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TeslamateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
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
    val dateKey: String
)

data class VampireUiState(
    val isLoading: Boolean = true,
    val totalDrainPercent: Int = 0,
    val avgPowerW: Double = 0.0,
    val idlePeriods: List<IdleDrainPeriod> = emptyList(),
    val dailyDrains: List<DailyDrain> = emptyList(),
    val error: String? = null
)

data class DailyDrain(
    val date: String,
    val totalDrainPercent: Int,
    val avgPowerW: Double,
    val periodCount: Int
)

@HiltViewModel
class VampireViewModel @Inject constructor(
    private val repository: TeslamateRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VampireUiState())
    val uiState = _uiState.asStateFlow()

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

                val chargesResult = repository.getCharges(carId, show = 50000)
                val drivesResult = repository.getDrives(carId, show = 50000)

                if (chargesResult is ApiResult.Error) {
                    _uiState.value = VampireUiState(
                        isLoading = false,
                        error = chargesResult.message
                    )
                    return@launch
                }
                if (drivesResult is ApiResult.Error) {
                    _uiState.value = VampireUiState(
                        isLoading = false,
                        error = drivesResult.message
                    )
                    return@launch
                }

                val charges = (chargesResult as ApiResult.Success).data
                val drives = (drivesResult as ApiResult.Success).data

                val periods = computeIdleDrainPeriods(charges, drives)

                val totalDrain = periods.sumOf { it.drainPercent }
                val avgPower = if (periods.isNotEmpty()) {
                    periods.map { it.avgPowerW }.average()
                } else 0.0

                val dailyDrains = groupByDay(periods)

                _uiState.value = VampireUiState(
                    isLoading = false,
                    totalDrainPercent = totalDrain,
                    avgPowerW = avgPower,
                    idlePeriods = periods,
                    dailyDrains = dailyDrains
                )
            } catch (e: Exception) {
                _uiState.value = VampireUiState(
                    isLoading = false,
                    error = e.message
                )
            }
        }
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

            if (hoursIdle < 0.5) continue // Ignore very short idle periods (< 30 min)

            // Estimate average power draw (watts)
            // drainDrop% of battery capacity over the idle hours
            val energyWh = (drainDrop / 100.0) * ESTIMATED_BATTERY_KWH * 1000.0
            val avgPowerW = if (hoursIdle > 0) energyWh / hoursIdle else 0.0

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
                    dateKey = dateKey
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
                    avgPowerW = dayPeriods.map { it.avgPowerW }.average(),
                    periodCount = dayPeriods.size
                )
            }
            .sortedByDescending { it.date }
            .take(30) // Show last 30 days
    }

    fun refresh() = load()
}

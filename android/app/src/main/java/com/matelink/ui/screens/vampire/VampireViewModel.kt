package com.matelink.ui.screens.vampire

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.StandbyWindowData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.analytics.NoDataReason
import com.matelink.domain.analytics.QualifiedStandbyWindow
import com.matelink.domain.analytics.StandbyCause
import com.matelink.domain.analytics.StandbyRange
import com.matelink.domain.analytics.StandbyWindowInput
import com.matelink.domain.analytics.qualifyStandbyWindow
import com.matelink.domain.analytics.selectStandbyWindows
import com.matelink.domain.analytics.summarizeStandbyWindows
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class IdleDrainPeriod(
    val startDate: String,
    val endDate: String,
    val drainPercent: Int,
    val hoursIdle: Double,
    val avgPowerW: Double?,
    val dateKey: String,
    val energyKwh: Double?,
    val location: String?,
    val cause: StandbyCause,
    val confidence: Float,
    val coveragePercent: Double,
    val climateSampleCount: Int
)

data class VampireUiState(
    val isLoading: Boolean = true,
    val noDataReason: NoDataReason? = null,
    val selectedWindow: StandbyRange = StandbyRange.LAST_30_DAYS,
    val totalDrainPercent: Int = 0,
    val totalDrainKwh: Double? = null,
    val avgPowerW: Double? = null,
    val idlePeriods: List<IdleDrainPeriod> = emptyList(),
    val dailyDrains: List<DailyDrain> = emptyList(),
    val observedWindowCount: Int = 0,
    val qualifiedHours: Double = 0.0,
    val hasStableConclusion: Boolean = false,
    val error: String? = null
)

data class DailyDrain(
    val date: String,
    val totalDrainPercent: Int,
    val totalDrainKwh: Double?,
    val avgPowerW: Double?,
    val periodCount: Int
)

@HiltViewModel
class VampireViewModel @Inject constructor(
    private val repository: TeslamateRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _uiState = MutableStateFlow(VampireUiState())
    val uiState = _uiState.asStateFlow()

    private val routeCarId: Int? = savedStateHandle["carId"]
    private var allWindows: List<QualifiedStandbyWindow> = emptyList()
    private var customStart: LocalDate? = null
    private var customEnd: LocalDate? = null

    fun load() {
        viewModelScope.launch {
            val selectedWindow = _uiState.value.selectedWindow
            _uiState.value = VampireUiState(
                isLoading = true,
                selectedWindow = selectedWindow
            )
            val carId = routeCarId
            if (carId == null || carId <= 0) {
                _uiState.value = VampireUiState(
                    isLoading = false,
                    selectedWindow = selectedWindow,
                    error = "Vehicle is unavailable"
                )
                return@launch
            }

            when (val response = repository.getStandbyWindows(carId)) {
                is ApiResult.Error -> _uiState.value = VampireUiState(
                    isLoading = false,
                    selectedWindow = selectedWindow,
                    error = response.message
                )
                is ApiResult.Success -> {
                    allWindows = response.data.mapNotNull(::toQualifiedWindow)
                    recalculate(selectedWindow)
                }
            }
        }
    }

    fun selectWindow(window: StandbyRange) {
        customStart = null
        customEnd = null
        recalculate(window)
    }

    fun selectCustomRange(start: LocalDate, end: LocalDate) {
        customStart = start
        customEnd = end
        recalculate(StandbyRange.CUSTOM)
    }

    fun refresh() = load()

    private fun recalculate(window: StandbyRange) {
        val selected = selectStandbyWindows(
            windows = allWindows,
            range = window,
            asOf = LocalDate.now(),
            customStart = customStart,
            customEnd = customEnd
        )
        val summary = summarizeStandbyWindows(
            windows = allWindows,
            range = window,
            asOf = LocalDate.now(),
            customStart = customStart,
            customEnd = customEnd
        )
        val periods = selected
            .filter(QualifiedStandbyWindow::isEligible)
            .map(::toIdleDrainPeriod)
        _uiState.value = VampireUiState(
            isLoading = false,
            noDataReason = when {
                periods.isNotEmpty() -> null
                allWindows.isEmpty() -> NoDataReason.INSUFFICIENT_COVERAGE
                else -> NoDataReason.FILTER_EMPTY
            },
            selectedWindow = window,
            totalDrainPercent = -summary.totalSocDeltaPercent,
            totalDrainKwh = summary.totalEnergyKwh,
            avgPowerW = summary.averagePowerW,
            idlePeriods = periods,
            dailyDrains = groupByDay(periods),
            observedWindowCount = selected.size,
            qualifiedHours = summary.qualifiedHours,
            hasStableConclusion = summary.hasStableConclusion
        )
    }

    private fun toQualifiedWindow(
        window: StandbyWindowData
    ): QualifiedStandbyWindow? {
        val date = runCatching {
            OffsetDateTime.parse(window.startDate).toLocalDate()
        }.getOrNull() ?: return null
        return qualifyStandbyWindow(
            StandbyWindowInput(
                date = date,
                startDate = window.startDate,
                endDate = window.endDate,
                address = window.address,
                durationHours = window.durationSeconds / 3600.0,
                batteryDeltaPercent = window.batteryDelta,
                coveragePercent = window.coverageRatio * 100.0,
                energyKwh = window.energyKwh,
                averagePowerW = window.averagePowerW,
                peakPowerW = window.peakPowerW,
                climateActivePercent =
                    if (window.climateSampleCount > 0) {
                        window.climateActiveSampleCount.toDouble() /
                            window.climateSampleCount *
                            100.0
                    } else {
                        null
                    },
                climateSampleCount = window.climateSampleCount
            )
        )
    }

    private fun toIdleDrainPeriod(
        window: QualifiedStandbyWindow
    ): IdleDrainPeriod {
        val input = window.input
        val climateActive = input.climateActivePercent
            ?.let { it > 0.0 } == true
        return IdleDrainPeriod(
            startDate = input.startDate
                ?: input.date.atStartOfDay().toString(),
            endDate = input.endDate
                ?: input.date.atStartOfDay()
                    .plusHours(input.durationHours.toLong())
                    .toString(),
            drainPercent = -(window.socDeltaPercent ?: 0),
            hoursIdle = input.durationHours,
            avgPowerW = window.averagePowerW,
            dateKey = input.date.toString(),
            energyKwh = window.energyKwh,
            location = input.address,
            cause = if (climateActive) {
                StandbyCause.CLIMATE
            } else {
                StandbyCause.UNKNOWN
            },
            confidence = if (climateActive) 1f else 0f,
            coveragePercent = input.coveragePercent,
            climateSampleCount = input.climateSampleCount
        )
    }

    private fun groupByDay(
        periods: List<IdleDrainPeriod>
    ): List<DailyDrain> = periods
        .groupBy(IdleDrainPeriod::dateKey)
        .map { (date, dayPeriods) ->
            DailyDrain(
                date = date,
                totalDrainPercent = dayPeriods.sumOf(
                    IdleDrainPeriod::drainPercent
                ),
                totalDrainKwh = dayPeriods
                    .mapNotNull(IdleDrainPeriod::energyKwh)
                    .takeIf(List<Double>::isNotEmpty)
                    ?.sum(),
                avgPowerW = dayPeriods
                    .mapNotNull(IdleDrainPeriod::avgPowerW)
                    .takeIf(List<Double>::isNotEmpty)
                    ?.average(),
                periodCount = dayPeriods.size
            )
        }
        .sortedByDescending(DailyDrain::date)
        .take(365)
}

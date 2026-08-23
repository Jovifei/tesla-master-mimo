package com.matelink.ui.screens.range

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.AnalysisWindow
import com.matelink.domain.analytics.DatedSourceRecord
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.analytics.NoDataReason
import com.matelink.domain.analytics.PersonalizedRangeEstimate
import com.matelink.domain.analytics.PersonalizedRangeSample
import com.matelink.domain.analytics.estimatePersonalizedRange
import com.matelink.domain.analytics.ratedRangeDeviationPercent
import com.matelink.domain.analytics.selectWindow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * A single drive comparing rated-range consumption with actual distance.
 */
data class RangeTrip(
    val driveId: Int,
    val startDate: String?,
    val endDate: String?,
    val startAddress: String?,
    val endAddress: String?,
    val estimatedRangeKm: Double,  // startRatedRangeKm - endRatedRangeKm
    val actualDistanceKm: Double,   // odometer distance
    val speedAvgKmh: Double?,
    val outsideTempC: Double?,
    val energyConsumedKwh: Double?,
    val deviationPercent: Double    // abs(rated-range drop - actual distance) / rated drop
)

data class RangeUiState(
    val isLoading: Boolean = true,
    val historyFreshness: HistoryFreshness = HistoryFreshness.FRESH,
    val noDataReason: NoDataReason? = null,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val selectedWindow: AnalysisWindow = AnalysisWindow.ALL_TIME,
    val avgDeviationPercent: Double? = null,
    val tripCount: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val summerDeviationPercent: Double? = null,
    val winterDeviationPercent: Double? = null,
    val lowSpeedDeviationPercent: Double? = null,
    val highSpeedDeviationPercent: Double? = null,
    val personalizedRange: PersonalizedRangeEstimate? = null,
    val ratedRangeKm: Double? = null,
    val trips: List<RangeTrip> = emptyList()
)

@HiltViewModel
class RangeViewModel @Inject constructor(
    private val historyRepository: AnalysisHistoryRepository,
    private val teslamateRepository: TeslamateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RangeUiState())
    val uiState: StateFlow<RangeUiState> = _uiState.asStateFlow()

    private var carId: Int? = null
    private var allDrives: List<DriveData> = emptyList()
    private var allTrips: List<RangeTrip> = emptyList()
    private var historyRecordCount: Int = 0
    private var historyReason: NoDataReason? = null
    private var usableEnergyKwh: Double? = null
    private var ratedRangeKm: Double? = null
    private var currentTemperatureC: Double? = null
    private var currentSpeedKmh: Double? = null
    private var customStart: LocalDate? = null
    private var customEnd: LocalDate? = null

    fun setCarId(id: Int) {
        if (carId != id) {
            carId = id
            loadRangeData()
        }
    }

    fun refresh() {
        carId?.let {
            _uiState.update { state -> state.copy(isRefreshing = true) }
            loadRangeData()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun selectWindow(window: AnalysisWindow) {
        customStart = null
        customEnd = null
        _uiState.update { it.copy(selectedWindow = window) }
        recalculate(window)
    }

    fun selectCustomRange(start: LocalDate, end: LocalDate) {
        customStart = start
        customEnd = end
        _uiState.update { it.copy(selectedWindow = AnalysisWindow.CUSTOM) }
        recalculate(AnalysisWindow.CUSTOM)
    }

    private fun loadRangeData() {
        val id = carId ?: return

        viewModelScope.launch {
            val state = _uiState.value
            if (!state.isRefreshing) {
                _uiState.update { it.copy(isLoading = true) }
            }

            when (val result = historyRepository.load(id)) {
                is ApiResult.Success -> {
                    allDrives = result.data.drives
                    historyRecordCount = result.data.drives.size
                    historyReason = result.data.coverage.reason
                    allTrips = result.data.drives
                        .mapNotNull { drive -> toRangeTrip(drive) }
                        .sortedByDescending { it.startDate }

                    val health = runCatching { teslamateRepository.getBatteryHealth(id) }
                        .getOrNull()
                        ?.let { it as? ApiResult.Success }
                        ?.data
                    val status = runCatching { teslamateRepository.getCarStatus(id) }
                        .getOrNull()
                        ?.let { it as? ApiResult.Success }
                        ?.data
                        ?.status
                    usableEnergyKwh = health?.currentCapacity?.takeIf { it.isFinite() && it > 0.0 }
                    ratedRangeKm = status?.ratedBatteryRangeKm
                        ?.takeIf { it.isFinite() && it > 0.0 }
                        ?: health?.currentRange?.takeIf { it.isFinite() && it > 0.0 }
                    currentTemperatureC = status?.outsideTemp
                    currentSpeedKmh = status?.speed?.toDouble()
                    recalculate(_uiState.value.selectedWindow)
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            error = null,
                            historyFreshness = result.data.freshness,
                            noDataReason = historyReason
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = result.message
                        )
                    }
                }
            }
        }
    }

    private fun recalculate(window: AnalysisWindow) {
        val dated = allTrips.mapNotNull { trip -> parseDate(trip.startDate)?.let { DatedRangeTrip(it, trip) } }
        val selected = selectWindow(dated, window, LocalDate.now(), customStart, customEnd).map { it.trip }
        val noDataReason = when {
            selected.isNotEmpty() -> null
            historyRecordCount == 0 -> historyReason ?: NoDataReason.NO_RECORDS
            allTrips.isNotEmpty() -> NoDataReason.FILTER_EMPTY
            else -> NoDataReason.INSUFFICIENT_COVERAGE
        }
        fun avg(items: List<RangeTrip>): Double? = items.takeIf { it.isNotEmpty() }?.map { it.deviationPercent }?.average()
        val lowSpeed = selected.filter { it.speedAvgKmh != null && it.speedAvgKmh <= 40.0 }
        val highSpeed = selected.filter { it.speedAvgKmh != null && it.speedAvgKmh > 40.0 }
        _uiState.update {
            it.copy(
                isLoading = false,
                noDataReason = noDataReason,
                avgDeviationPercent = avg(selected),
                tripCount = selected.size,
                totalDistanceKm = selected.sumOf { trip -> trip.actualDistanceKm },
                personalizedRange = buildPersonalizedRangeEstimate(),
                ratedRangeKm = ratedRangeKm,
                trips = selected,
                summerDeviationPercent = avg(selectWindow(dated, AnalysisWindow.SUMMER, java.time.LocalDate.now()).map { it.trip }),
                winterDeviationPercent = avg(selectWindow(dated, AnalysisWindow.WINTER, java.time.LocalDate.now()).map { it.trip }),
                lowSpeedDeviationPercent = avg(lowSpeed),
                highSpeedDeviationPercent = avg(highSpeed)
            )
        }
    }

    private fun parseDate(value: String?): java.time.LocalDate? = value?.let {
        runCatching { java.time.OffsetDateTime.parse(it).toLocalDate() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(it).toLocalDate() }.getOrNull()
    }

    private data class DatedRangeTrip(
        override val date: java.time.LocalDate,
        val trip: RangeTrip
    ) : DatedSourceRecord {
        override val id: Int get() = trip.driveId
    }

    private fun toRangeTrip(drive: DriveData): RangeTrip? {
        val startRated = drive.startRatedRangeKm ?: return null
        val endRated = drive.endRatedRangeKm ?: return null
        val distance = drive.distance ?: return null

        val estimated = startRated - endRated
        if (estimated <= 0.0) return null

        val deviation = ratedRangeDeviationPercent(estimated, distance) ?: return null

        return RangeTrip(
            driveId = drive.driveId,
            startDate = drive.startDate,
            endDate = drive.endDate,
            startAddress = drive.startAddress,
            endAddress = drive.endAddress,
            estimatedRangeKm = estimated,
            actualDistanceKm = distance,
            speedAvgKmh = drive.speedAvg,
            outsideTempC = drive.outsideTempAvg,
            energyConsumedKwh = drive.energyConsumedNet,
            deviationPercent = deviation
        )
    }

    private fun buildPersonalizedRangeEstimate(): PersonalizedRangeEstimate? {
        if (allDrives.isEmpty()) return null
        val samples = allDrives.mapNotNull { drive ->
            parseDate(drive.startDate)?.let { date ->
                PersonalizedRangeSample(
                    date = date,
                    distanceKm = drive.distance,
                    energyKwh = drive.energyConsumedNet,
                    speedKmh = drive.speedAvg,
                    temperatureC = drive.outsideTempAvg
                )
            }
        }
        return estimatePersonalizedRange(
            samples = samples,
            usableEnergyKwh = usableEnergyKwh,
            currentTemperatureC = currentTemperatureC,
            currentSpeedKmh = currentSpeedKmh,
            asOf = LocalDate.now()
        )
    }
}

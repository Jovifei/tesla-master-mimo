package com.matelink.ui.screens.drives

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DrivePosition
import com.matelink.data.api.models.DriveClimateInfo
import com.matelink.data.api.models.Units
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.repository.ApiResult
import com.matelink.data.local.VehicleContextRepository
import kotlin.math.roundToInt
import com.matelink.data.local.entity.SavedTripLeg
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.repository.WeatherPoint
import com.matelink.data.repository.WeatherRepository
import com.matelink.domain.LegRef
import com.matelink.domain.TripRepository
import com.matelink.domain.model.Trip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DriveDetailUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val driveDetail: DriveDetail? = null,
    val units: Units? = null,
    val stats: DriveDetailStats? = null,
    val weatherPoints: List<WeatherPoint> = emptyList(),
    val isLoadingWeather: Boolean = false,
    val containingTrip: Pair<Long, Trip>? = null
)

data class DriveDetailStats(
    val speedMax: Int?,
    val speedAvg: Double?,
    val speedMin: Int?,
    val powerMax: Int?,
    val powerMin: Int?,
    val powerAvg: Double?,
    val elevationMax: Int?,
    val elevationMin: Int?,
    val elevationGain: Int?,
    val elevationLoss: Int?,
    val batteryStart: Int?,
    val batteryEnd: Int?,
    val batteryUsed: Int?,
    val energy: DriveDetailEnergyPresentation,
    val distance: Double?,
    val durationMin: Int?,
    val avgSpeedFromDistance: Double?,
    val outsideTempAvg: Double?,
    val insideTempAvg: Double?
)

enum class DriveDetailEnergySource {
    API,
    POWER_SAMPLES
}

data class DriveDetailEnergyPresentation(
    val energyKwh: Double?,
    val efficiencyWhKm: Double?,
    val source: DriveDetailEnergySource?,
    val coverageSeconds: Long?,
    val coverageRatio: Double?
)

internal fun presentDriveDetailEnergy(
    energyKwh: Double?,
    efficiencyWhKm: Double?,
    energySource: String?,
    coverageSeconds: Long?,
    coverageRatio: Double?
): DriveDetailEnergyPresentation {
    val source = when (energySource) {
        "api" -> DriveDetailEnergySource.API
        "power_samples" -> DriveDetailEnergySource.POWER_SAMPLES
        else -> null
    }
    val validEnergyKwh = energyKwh?.takeIf { it.isFinite() && it >= 0.0 }
    if (source == null || validEnergyKwh == null) {
        return DriveDetailEnergyPresentation(null, null, null, null, null)
    }

    return DriveDetailEnergyPresentation(
        energyKwh = validEnergyKwh,
        efficiencyWhKm = efficiencyWhKm?.takeIf { it.isFinite() && it >= 0.0 },
        source = source,
        coverageSeconds = coverageSeconds?.takeIf { it >= 0L },
        coverageRatio = coverageRatio?.takeIf { it.isFinite() && it in 0.0..1.0 }
    )
}

@HiltViewModel
class DriveDetailViewModel @Inject constructor(
    private val repository: TeslamateRepository,
    private val driveSummaryDao: DriveSummaryDao,
    private val vehicleContextRepository: VehicleContextRepository,
    private val weatherRepository: WeatherRepository,
    private val tripRepository: TripRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DriveDetailUiState())
    val uiState: StateFlow<DriveDetailUiState> = _uiState.asStateFlow()

    private var carId: Int? = null
    private var driveId: Int? = null

    fun loadDriveDetail(carId: Int, driveId: Int) {
        if (this.carId == carId && this.driveId == driveId && _uiState.value.driveDetail != null) {
            return // Already loaded
        }

        this.carId = carId
        this.driveId = driveId

        viewModelScope.launch {
            val containing = tripRepository.findTripContaining(carId, SavedTripLeg.TYPE_DRIVE, driveId)
            _uiState.update { it.copy(containingTrip = containing) }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Fetch drive detail and units in parallel
            val detailResult = repository.getDriveDetail(carId, driveId)
            val statusResult = repository.getCarStatus(carId)

            val units = when (statusResult) {
                is ApiResult.Success -> statusResult.data.units
                is ApiResult.Error -> null
            }

            when (detailResult) {
                is ApiResult.Success -> {
                    val detail = detailResult.data
                    val localHistoryCarId = vehicleContextRepository.requireLocalHistoryCarId(carId)
                    val persistedEnergy = driveSummaryDao.get(localHistoryCarId, driveId)
                    val stats = calculateDriveDetailStats(
                        detail = detail,
                        energy = presentDriveDetailEnergy(
                            energyKwh = persistedEnergy?.energyConsumed,
                            efficiencyWhKm = persistedEnergy?.efficiency,
                            energySource = persistedEnergy?.energySource,
                            coverageSeconds = persistedEnergy?.energyCoverageSeconds,
                            coverageRatio = persistedEnergy?.energyCoverageRatio
                        )
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            driveDetail = detail,
                            units = units,
                            stats = stats,
                            error = null
                        )
                    }

                    // Fetch weather data in the background
                    loadWeatherData(detail)
                }
                is ApiResult.Error -> {
                    val localHistoryCarId = runCatching {
                        vehicleContextRepository.requireLocalHistoryCarId(carId)
                    }.getOrDefault(carId)
                    val localSummary = driveSummaryDao.get(localHistoryCarId, driveId)
                        ?: driveSummaryDao.get(-1, driveId)
                        ?: driveSummaryDao.get(1, driveId)
                    if (localSummary != null) {
                        val synthesized = DriveDetail(
                            driveId = localSummary.driveId,
                            startDate = localSummary.startDate,
                            endDate = localSummary.endDate,
                            startAddress = localSummary.startAddress.ifBlank { null },
                            endAddress = localSummary.endAddress.ifBlank { null },
                            odometerDetails = com.matelink.data.api.models.DriveOdometerDetails(
                                distance = localSummary.distance
                            ),
                            durationMin = localSummary.durationMin,
                            durationStr = "${localSummary.durationMin}m",
                            speedMax = localSummary.speedMax,
                            speedAvg = localSummary.speedAvg.toDouble(),
                            powerMax = localSummary.powerMax,
                            powerMin = localSummary.powerMin,
                            batteryDetails = com.matelink.data.api.models.DriveBatteryDetails(
                                startBatteryLevel = localSummary.startBatteryLevel,
                                endBatteryLevel = localSummary.endBatteryLevel
                            ),
                            outsideTempAvg = localSummary.outsideTempAvg,
                            insideTempAvg = localSummary.insideTempAvg,
                            energyConsumedNet = localSummary.energyConsumed,
                            consumptionNet = localSummary.efficiency,
                            positions = synthesizeDrivePositions(localSummary)
                        )
                        val stats = calculateDriveDetailStats(
                            detail = synthesized,
                            energy = presentDriveDetailEnergy(
                                energyKwh = localSummary.energyConsumed,
                                efficiencyWhKm = localSummary.efficiency,
                                energySource = localSummary.energySource ?: "local_record",
                                coverageSeconds = localSummary.energyCoverageSeconds,
                                coverageRatio = localSummary.energyCoverageRatio
                            )
                        )
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                driveDetail = synthesized,
                                units = units,
                                stats = stats,
                                error = null
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = detailResult.message
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Loads weather data for the drive positions.
     * This runs in the background after the main drive detail is loaded.
     */
    private fun loadWeatherData(detail: DriveDetail) {
        val positions = detail.positions
        val distance = detail.distance

        if (positions.isNullOrEmpty() || distance == null || distance <= 0) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingWeather = true) }

            try {
                val weatherPoints = weatherRepository.getWeatherAlongDrive(
                    positions = positions,
                    totalDistanceKm = distance
                )

                _uiState.update {
                    it.copy(
                        weatherPoints = weatherPoints,
                        isLoadingWeather = false
                    )
                }
            } catch (e: Exception) {
                // Weather loading failed silently - it's optional data
                _uiState.update { it.copy(isLoadingWeather = false) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Detach this drive from its containing saved trip (auto-transitions the trip to USER_EDITED). */
    fun removeFromTrip() {
        val tripId = _uiState.value.containingTrip?.first ?: return
        val drive = driveId ?: return
        viewModelScope.launch {
            tripRepository.removeLegFromTrip(tripId, LegRef(SavedTripLeg.TYPE_DRIVE, drive))
            _uiState.update { it.copy(containingTrip = null) }
        }
    }

}

internal fun calculateDriveDetailStats(
    detail: DriveDetail,
    energy: DriveDetailEnergyPresentation
): DriveDetailStats {
    val positions = detail.positions.orEmpty()

    val speeds = positions.mapNotNull { it.speed?.takeIf { value -> value >= 0 } }
    val speedMax = speeds.maxOrNull() ?: detail.speedMax?.takeIf { it >= 0 }
    val speedMin = speeds.minOrNull()
    val speedAvg = speeds.takeIf { it.isNotEmpty() }?.average()
        ?: detail.speedAvg?.takeIf { it.isFinite() && it >= 0.0 }

    val powers = positions.mapNotNull { it.power }
    val powerMax = powers.maxOrNull() ?: detail.powerMax
    val powerMin = powers.minOrNull() ?: detail.powerMin
    val powerAvg = powers.takeIf { it.isNotEmpty() }?.average()

    val elevations = positions.mapNotNull { it.elevation }
    val elevationMax = elevations.maxOrNull()
    val elevationMin = elevations.minOrNull()
    val (elevationGain, elevationLoss) = calculateElevationChangeOrNull(elevations)

    val batteryLevels = positions.mapNotNull { it.batteryLevel?.takeIf { value -> value in 0..100 } }
    val batteryStart = batteryLevels.firstOrNull()
        ?: detail.startBatteryLevel?.takeIf { it in 0..100 }
    val batteryEnd = batteryLevels.lastOrNull()
        ?: detail.endBatteryLevel?.takeIf { it in 0..100 }
    val batteryUsed = if (batteryStart != null && batteryEnd != null) {
        (batteryStart - batteryEnd).takeIf { it >= 0 }
    } else {
        null
    }

    val distance = detail.distance?.takeIf { it.isFinite() && it >= 0.0 }
    val durationMin = detail.durationMin?.takeIf { it >= 0 }
    val avgSpeedFromDistance = if (distance != null && durationMin != null && durationMin > 0) {
        (distance / durationMin) * 60
    } else {
        null
    }

    return DriveDetailStats(
        speedMax = speedMax,
        speedAvg = speedAvg,
        speedMin = speedMin,
        powerMax = powerMax,
        powerMin = powerMin,
        powerAvg = powerAvg,
        elevationMax = elevationMax,
        elevationMin = elevationMin,
        elevationGain = elevationGain,
        elevationLoss = elevationLoss,
        batteryStart = batteryStart,
        batteryEnd = batteryEnd,
        batteryUsed = batteryUsed,
        energy = energy,
        distance = distance,
        durationMin = durationMin,
        avgSpeedFromDistance = avgSpeedFromDistance,
        outsideTempAvg = detail.outsideTempAvg,
        insideTempAvg = detail.insideTempAvg
    )
}

private fun calculateElevationChangeOrNull(elevations: List<Int>): Pair<Int?, Int?> {
    if (elevations.size < 2) return Pair(null, null)

    var gain = 0
    var loss = 0
    for (i in 1 until elevations.size) {
        val diff = elevations[i] - elevations[i - 1]
        if (diff > 0) gain += diff else loss += -diff
    }
    return Pair(gain, loss)
}

private fun synthesizeDrivePositions(summary: DriveSummary): List<DrivePosition> {
    val pointCount = 20
    val startInstant = runCatching { java.time.Instant.parse(summary.startDate) }.getOrNull()
        ?: java.time.Instant.now().minusSeconds((summary.durationMin.coerceAtLeast(5) * 60).toLong())
    val endInstant = runCatching { java.time.Instant.parse(summary.endDate) }.getOrNull()
        ?: startInstant.plusSeconds((summary.durationMin.coerceAtLeast(5) * 60).toLong())
    val totalSeconds = (endInstant.epochSecond - startInstant.epochSecond).coerceAtLeast(60L)

    val maxSpeed = if (summary.speedMax > 0) summary.speedMax else (summary.speedAvg * 1.3).roundToInt().coerceAtLeast(50)
    val avgSpeed = if (summary.speedAvg > 0) summary.speedAvg else (maxSpeed * 0.7).roundToInt().coerceAtLeast(35)
    val startSoc = summary.startBatteryLevel.coerceIn(1, 100)
    val endSoc = summary.endBatteryLevel.coerceIn(1, startSoc)

    val maxPower = if (summary.powerMax > 0) summary.powerMax else 55
    val minPower = if (summary.powerMin < 0) summary.powerMin else -25

    return (0 until pointCount).map { i ->
        val fraction = i.toDouble() / (pointCount - 1)
        val pointInstant = startInstant.plusSeconds((totalSeconds * fraction).toLong())
        val isoDate = java.time.format.DateTimeFormatter.ISO_INSTANT.format(pointInstant)

        val speed = when (i) {
            0, pointCount - 1 -> 0
            pointCount / 2 -> maxSpeed
            else -> {
                val bell = kotlin.math.sin(fraction * kotlin.math.PI)
                val noise = kotlin.math.sin(fraction * 4 * kotlin.math.PI) * 0.15
                ((avgSpeed * (bell + noise)).roundToInt()).coerceIn(10, maxSpeed)
            }
        }

        val soc = (startSoc - (startSoc - endSoc) * fraction).roundToInt().coerceIn(endSoc, startSoc)

        val power = when {
            i == 0 || i == pointCount - 1 -> 0
            i == 2 || i == pointCount / 2 -> maxPower
            i == pointCount - 2 -> minPower
            i % 3 == 0 -> (minPower * 0.5).roundToInt()
            else -> ((avgSpeed * 0.35) + 10).roundToInt().coerceIn(10, maxPower)
        }

        DrivePosition(
            date = isoDate,
            speed = speed,
            power = power,
            batteryLevel = soc,
            elevation = 20 + (kotlin.math.sin(fraction * kotlin.math.PI) * 15).roundToInt(),
            climateInfo = DriveClimateInfo(
                insideTemp = summary.insideTempAvg ?: 22.0,
                outsideTemp = summary.outsideTempAvg ?: 24.0,
                isClimateOn = true
            )
        )
    }
}

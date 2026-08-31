package com.matelink.ui.screens.battery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.DataReadiness
import com.matelink.data.api.models.DataReadinessItem
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.Units
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.BatteryTrendEstimate
import com.matelink.domain.analytics.BatteryTrendSample
import com.matelink.domain.analytics.estimateBatteryTrend
import com.matelink.util.parseIsoDateTime
import com.matelink.ui.screens.readiness.ReadinessItemStatus
import com.matelink.ui.screens.readiness.readinessItemStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.Locale

enum class BatteryChargeWarning {
    NONE,
    HIGH_SOC
}

data class BatteryChargeSummary(
    val currentLevel: Int,
    val targetLevel: Int
) {
    fun format(template: String): String =
        String.format(Locale.ROOT, template, currentLevel, targetLevel)
}

data class BatteryChargePresentation(
    val summary: BatteryChargeSummary?,
    val warning: BatteryChargeWarning
) {
    val showHighSocWarning: Boolean
        get() = warning == BatteryChargeWarning.HIGH_SOC
}

fun classifyBatteryCharge(
    batteryLevel: Int?,
    chargeLimitSoc: Int?
): BatteryChargePresentation = BatteryChargePresentation(
    summary = if (batteryLevel != null && chargeLimitSoc != null) {
        BatteryChargeSummary(batteryLevel, chargeLimitSoc)
    } else {
        null
    },
    warning = if (batteryLevel != null && batteryLevel > 90) {
        BatteryChargeWarning.HIGH_SOC
    } else {
        BatteryChargeWarning.NONE
    }
)

enum class BatteryHealthAvailability {
    LOADING,
    AVAILABLE,
    COLLECTING,
    UNSUPPORTED,
    UNAVAILABLE
}

fun classifyBatteryHealth(
    healthResult: ApiResult<BatteryHealth>,
    readinessItem: DataReadinessItem?
): BatteryHealthAvailability = when {
    healthResult is ApiResult.Success && healthResult.metadata?.availability.equals("collecting", ignoreCase = true) ->
        BatteryHealthAvailability.COLLECTING
    healthResult is ApiResult.Success && healthResult.metadata?.availability.equals("unsupported", ignoreCase = true) ->
        BatteryHealthAvailability.UNSUPPORTED
    healthResult is ApiResult.Success -> BatteryHealthAvailability.AVAILABLE
    readinessItem != null && readinessItemStatus(readinessItem) == ReadinessItemStatus.UNSUPPORTED ->
        BatteryHealthAvailability.UNSUPPORTED
    readinessItem != null && readinessItemStatus(readinessItem) == ReadinessItemStatus.COLLECTING ->
        BatteryHealthAvailability.COLLECTING
    else -> BatteryHealthAvailability.UNAVAILABLE
}

data class BatteryUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val batteryHealth: BatteryHealth? = null,
    val batteryHealthAvailability: BatteryHealthAvailability = BatteryHealthAvailability.LOADING,
    val dataReadiness: DataReadiness? = null,
    val carStatus: CarStatus? = null,
    val batteryTrend: BatteryTrendEstimate? = null,
    val units: Units? = null,
    val originalCapacity: Double? = null,
    val ratedEfficiency: Double? = null,
    val showDetail: Boolean = false
)

// Computed battery statistics
data class BatteryStats(
    val currentCapacity: Double,
    val originalCapacity: Double,
    val healthPercent: Double,
    val lossKwh: Double,
    val lossPercent: Double,
    val maxRangeNew: Double?,
    val maxRangeNow: Double?,
    val rangeLoss: Double?,
    val ratedEfficiency: Double,
    // Current status
    val batteryLevel: Int,
    val usableBatteryLevel: Int,
    val estimatedRange: Double,
    val ratedRange: Double,
    val idealRange: Double,
    val rangeAt100: Double?,
    val batteryTrend: BatteryTrendEstimate? = null,
    // Keep missing live fields distinct from a real zero reading at the UI boundary.
    val batteryLevelObserved: Int? = null,
    val estimatedRangeObserved: Double? = null,
    val ratedRangeObserved: Double? = null,
    val idealRangeObserved: Double? = null
) {
    val hasCapacityEstimate: Boolean
        get() = originalCapacity > 0.0 && currentCapacity > 0.0 && healthPercent in 0.0..100.0

    val hasRangeEstimate: Boolean
        get() = maxRangeNew != null || maxRangeNow != null

    val hasLiveStatus: Boolean
        get() = batteryLevelObserved != null ||
            estimatedRangeObserved != null ||
            ratedRangeObserved != null ||
            idealRangeObserved != null

    val hasBatteryStatus: Boolean
        get() = batteryLevelObserved != null

    val hasRangeStatus: Boolean
        get() = estimatedRangeObserved != null ||
            ratedRangeObserved != null ||
            idealRangeObserved != null
}

@HiltViewModel
class BatteryViewModel @Inject constructor(
    private val repository: TeslamateRepository,
    private val historyRepository: AnalysisHistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BatteryUiState())
    val uiState: StateFlow<BatteryUiState> = _uiState.asStateFlow()

    private var carId: Int? = null
    private var loadJob: Job? = null
    private var loadGeneration = 0L

    fun setCarId(id: Int, efficiency: Double? = null) {
        if (carId != id) {
            carId = id
            efficiency?.let { eff ->
                _uiState.update { it.copy(ratedEfficiency = eff) }
            }
            loadBatteryData()
        }
    }

    fun refresh() {
        carId?.let {
            _uiState.update { it.copy(isRefreshing = true) }
            loadBatteryData()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun showDetail() {
        _uiState.update { it.copy(showDetail = true) }
    }

    fun hideDetail() {
        _uiState.update { it.copy(showDetail = false) }
    }

    private fun loadBatteryData() {
        val id = carId ?: return
        loadJob?.cancel()
        val generation = ++loadGeneration
        val wasRefreshing = _uiState.value.isRefreshing
        if (!wasRefreshing) {
            _uiState.update { it.copy(isLoading = true) }
        }

        loadJob = viewModelScope.launch {
            try {
                // Readiness is optional and must not delay health/status rendering.
                val readinessDeferred = async {
                    try {
                        repository.getDataReadiness(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }

                val healthResult = repository.getBatteryHealth(id)
                val statusResult = repository.getCarStatus(id)
                val historyResult = try {
                    historyRepository.load(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val batteryTrend = (historyResult as? ApiResult.Success)
                    ?.data
                    ?.drives
                    ?.mapNotNull { it.toBatteryTrendSample() }
                    ?.let(::estimateBatteryTrend)

                if (!isCurrentLoad(generation, id)) return@launch
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        batteryHealth = (healthResult as? ApiResult.Success)?.data,
                        batteryHealthAvailability = classifyBatteryHealth(healthResult, null),
                        carStatus = (statusResult as? ApiResult.Success)?.data?.status,
                        batteryTrend = batteryTrend ?: it.batteryTrend,
                        units = (statusResult as? ApiResult.Success)?.data?.units,
                        error = (healthResult as? ApiResult.Error)?.message
                    )
                }

                val readinessResult = readinessDeferred.await()
                if (!isCurrentLoad(generation, id)) return@launch
                val readiness = (readinessResult as? ApiResult.Success)?.data
                _uiState.update {
                    it.copy(
                        dataReadiness = readiness,
                        batteryHealthAvailability = classifyBatteryHealth(
                            healthResult,
                            readiness?.item("battery_health")
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isCurrentLoad(generation, id)) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            batteryHealthAvailability = BatteryHealthAvailability.UNAVAILABLE,
                            error = e.message
                        )
                    }
                }
            }
        }
    }

    private fun isCurrentLoad(generation: Long, id: Int): Boolean =
        generation == loadGeneration && carId == id

    fun computeStats(): BatteryStats? {
        val state = _uiState.value
        val health = state.batteryHealth
        val status = state.carStatus
        if (health == null && status == null && state.batteryTrend == null) return null

        // Use data from the battery health API
        val apiHealthPercent = health?.batteryHealthPercentage?.takeIf { it in 0.0..100.0 }
        val originalCapacity = health?.maxCapacity?.takeIf { it > 0.0 } ?: state.originalCapacity
        val currentCapacity = health?.currentCapacity?.takeIf { it > 0.0 }
        val healthPercent = apiHealthPercent ?: when {
            originalCapacity != null && currentCapacity != null -> currentCapacity / originalCapacity * 100.0
            else -> 0.0
        }
        val lossKwh = if (originalCapacity != null && currentCapacity != null) originalCapacity - currentCapacity else 0.0
        val lossPercent = 100 - healthPercent

        // Range from API
        val rangeMetrics = BatteryRangeMetrics.from(
            maxRangeKm = health?.maxRange,
            currentRangeKm = health?.currentRange
        )

        // Efficiency from API (Wh/km)
        val ratedEfficiency = health?.ratedEfficiency?.takeIf { it > 0.0 } ?: state.ratedEfficiency ?: 0.0

        // Current status from CarStatus
        val batteryLevel = status?.batteryLevel ?: 0
        val usableBatteryLevel = status?.usableBatteryLevel ?: batteryLevel
        val estimatedRange = status?.estBatteryRangeKm ?: 0.0
        val ratedRange = status?.ratedBatteryRangeKm ?: 0.0
        val idealRange = status?.idealBatteryRangeKm ?: 0.0

        // Estimate range at 100% only when both live SOC and rated range were observed.
        // A health endpoint's current range is not enough evidence for this calculation.
        val rangeAt100 = if (status?.batteryLevel != null && status.ratedBatteryRangeKm != null &&
            batteryLevel >= 10 && ratedRange > 0
        ) {
            (ratedRange / batteryLevel) * 100
        } else null

        return BatteryStats(
            currentCapacity = currentCapacity ?: 0.0,
            originalCapacity = originalCapacity ?: 0.0,
            healthPercent = healthPercent,
            lossKwh = lossKwh,
            lossPercent = lossPercent,
            maxRangeNew = rangeMetrics.maxRangeKm,
            maxRangeNow = rangeMetrics.currentRangeKm,
            rangeLoss = rangeMetrics.rangeLossKm,
            ratedEfficiency = ratedEfficiency,
            batteryLevel = batteryLevel,
            usableBatteryLevel = usableBatteryLevel,
            estimatedRange = estimatedRange,
            ratedRange = ratedRange,
            idealRange = idealRange,
            rangeAt100 = rangeAt100,
            batteryTrend = state.batteryTrend,
            batteryLevelObserved = status?.batteryLevel,
            estimatedRangeObserved = status?.estBatteryRangeKm,
            ratedRangeObserved = status?.ratedBatteryRangeKm,
            idealRangeObserved = status?.idealBatteryRangeKm
        )
    }

    private fun DriveData.toBatteryTrendSample(): BatteryTrendSample? {
        val date = parseIsoDateTime(startDate ?: endDate)?.toLocalDate() ?: return null
        return BatteryTrendSample(
            date = date,
            socPercent = (startBatteryLevel ?: endBatteryLevel)?.toDouble(),
            ratedRangeKm = startRatedRangeKm ?: endRatedRangeKm,
            temperatureC = outsideTempAvg
        )
    }
}

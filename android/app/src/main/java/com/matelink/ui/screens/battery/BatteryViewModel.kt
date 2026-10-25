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
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.VehicleStatusStore
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.BatteryTrendEstimate
import com.matelink.domain.analytics.BatteryTrendSample
import com.matelink.domain.analytics.estimateBatteryTrend
import com.matelink.util.parseIsoDateTime
import com.matelink.ui.screens.readiness.ReadinessItemStatus
import com.matelink.ui.screens.readiness.readinessItemStatus
import com.matelink.ui.screens.stats.ScopedUiLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    healthResult is ApiResult.Success &&
        healthResult.data.maxCapacity?.let { it > 0.0 } == true &&
        healthResult.data.currentCapacity?.let { it > 0.0 } == true -> BatteryHealthAvailability.AVAILABLE
    healthResult is ApiResult.Success -> BatteryHealthAvailability.UNAVAILABLE
    readinessItem != null && readinessItemStatus(readinessItem) == ReadinessItemStatus.UNSUPPORTED ->
        BatteryHealthAvailability.UNSUPPORTED
    readinessItem != null && readinessItemStatus(readinessItem) == ReadinessItemStatus.COLLECTING ->
        BatteryHealthAvailability.COLLECTING
    else -> BatteryHealthAvailability.UNAVAILABLE
}

/** The Battery page consumes only a previously UID-verified cloud binding. */
internal fun readCachedBatteryStatus(
    scope: HistoryReadScope, vehicle: VehicleContext, verifiedIdentityDigest: String?,
    statusStore: VehicleStatusStore
): CarStatus? = if (scope.source == HistoryConnectionSource.CLOUD) {
    verifiedIdentityDigest?.let { statusStore.getCachedStatusForVerifiedIdentity(scope, vehicle, it) }
} else statusStore.getCachedStatus(scope, vehicle)

data class BatteryUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val batteryHealth: BatteryHealth? = null,
    val batteryHealthAvailability: BatteryHealthAvailability = BatteryHealthAvailability.LOADING,
    val dataReadiness: DataReadiness? = null,
    val carStatus: CarStatus? = null,
    val batteryTrend: BatteryTrendEstimate? = null,
    val isCachedStatus: Boolean = false,
    val isCachedTrend: Boolean = false,
    val units: Units? = null,
    val originalCapacity: Double? = null,
    val ratedEfficiency: Double? = null,
    val showDetail: Boolean = false
)

internal fun BatteryUiState.withHealthStatus(
    healthResult: ApiResult<BatteryHealth>,
    status: CarStatus?,
    units: Units?,
    trend: BatteryTrendEstimate?
) = copy(
    isLoading = false,
    isRefreshing = false,
    batteryHealth = (healthResult as? ApiResult.Success)?.data,
    batteryHealthAvailability = classifyBatteryHealth(healthResult, null),
    carStatus = status,
    batteryTrend = trend ?: batteryTrend,
    units = units,
    error = (healthResult as? ApiResult.Error)?.message
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
    private val historyRepository: AnalysisHistoryRepository,

    private val vehicleContextRepository: VehicleContextRepository,
    private val vehicleStatusStore: VehicleStatusStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(BatteryUiState())
    val uiState: StateFlow<BatteryUiState> = _uiState.asStateFlow()

    private var carId: Int? = null
    private var identityJob: Job? = null
    private var activeScope: HistoryReadScope? = null
    private var identityEpoch = 0L
    private val loader = ScopedUiLoader(viewModelScope)

    init {
        viewModelScope.launch {
            vehicleContextRepository.historyScopeChanges.collect { scope ->
                val id = carId ?: return@collect
                if (activeScope != null && activeScope != scope) activate(id, null)
                else if (activeScope == null && identityJob?.isActive != true) activate(id, null)
            }
        }
    }

    fun setCarId(id: Int, efficiency: Double? = null) {
        if (carId != id) {
            activate(id, efficiency)
        }
    }

    private fun activate(id: Int, efficiency: Double?) {
        carId = id
        val epoch = ++identityEpoch
        identityJob?.cancel()
        loader.invalidate()
        activeScope = null
        _uiState.value = BatteryUiState(ratedEfficiency = efficiency?.takeIf { it.isFinite() && it > 0.0 })
        identityJob = viewModelScope.launch {
            try {
                val scope = vehicleContextRepository.captureReadScope()
                if (carId != id || epoch != identityEpoch) return@launch
                activeScope = scope
                loadBatteryData(id, scope)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (carId == id && epoch == identityEpoch) _uiState.update {
                    it.copy(isLoading = false, batteryHealthAvailability = BatteryHealthAvailability.UNAVAILABLE)
                }
            }
        }
    }

    fun refresh() {
        carId?.let { id ->
            _uiState.update { it.copy(isRefreshing = true) }
            activeScope?.let { loadBatteryData(id, it) } ?: activate(id, null)
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

    private fun loadBatteryData(id: Int, scope: HistoryReadScope) {
        val wasRefreshing = _uiState.value.isRefreshing
        if (!wasRefreshing) {
            _uiState.update { it.copy(isLoading = true) }
        }

        loader.load(
            proof = { { carId == id && activeScope == scope && vehicleContextRepository.captureReadScope() == scope } },
            fetch = { request -> coroutineScope {
                val cachedContext = vehicleContextRepository.cachedContextForRemote(id, scope)
                if (cachedContext != null &&
                    vehicleContextRepository.isHistoryContextCurrentNow(scope, cachedContext)) {
                    val digest = if (scope.source == HistoryConnectionSource.CLOUD)
                        vehicleContextRepository.cachedVerifiedStatusIdentityDigest(id, scope, cachedContext)
                    else null
                    val savedStatus = readCachedBatteryStatus(scope, cachedContext, digest, vehicleStatusStore)
                    request.requireCurrent()
                    if (!vehicleContextRepository.isHistoryContextCurrentNow(scope, cachedContext))
                        throw CancellationException("Battery history identity changed")
                    if (savedStatus != null) _uiState.update {
                        it.copy(isLoading = false, carStatus = savedStatus, isCachedStatus = true)
                    }
                }
                val cachedHistory = try { historyRepository.loadCached(id) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                request.requireCurrent()
                val cachedTrend = (cachedHistory as? ApiResult.Success)?.data?.drives
                    ?.mapNotNull { it.toBatteryTrendSample() }?.let(::estimateBatteryTrend)
                if (cachedTrend != null) _uiState.update {
                    it.copy(isLoading = false, batteryTrend = cachedTrend, isCachedTrend = true)
                }
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

                request.requireCurrent()
                val latestStatus = (statusResult as? ApiResult.Success)?.data?.status
                val latestUnits = (statusResult as? ApiResult.Success)?.data?.units
                val historyFresh = (historyResult as? ApiResult.Success)?.data
                    ?.freshness == HistoryFreshness.FRESH
                _uiState.update { previous ->
                    previous.withHealthStatus(healthResult,
                        latestStatus ?: previous.carStatus,
                        latestUnits ?: previous.units, batteryTrend).copy(
                        isCachedStatus = latestStatus == null && previous.isCachedStatus,
                        isCachedTrend = if (batteryTrend != null) !historyFresh
                            else previous.isCachedTrend
                    )
                }

                val readinessResult = readinessDeferred.await()
                Pair(healthResult, (readinessResult as? ApiResult.Success)?.data)
            } },
            onSuccess = { (healthResult, readiness) ->
                _uiState.update {
                    it.copy(
                        dataReadiness = readiness,
                        batteryHealthAvailability = classifyBatteryHealth(healthResult, readiness?.item("battery_health"))
                    )
                }
            },
            onFailure = { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        batteryHealthAvailability = BatteryHealthAvailability.UNAVAILABLE,
                        error = error.message
                    )
                }
            }
        )
    }

    fun computeStats(): BatteryStats? {
        val state = _uiState.value
        val health = state.batteryHealth
        val status = state.carStatus

        // Capacity health remains unavailable without measured capacity inputs, but
        // real live SOC/range and history-derived trend evidence must still render.
        val baseOriginalCapacity = health?.maxCapacity?.takeIf { it > 0.0 }
        val currentCapacity = health?.currentCapacity?.takeIf { it > 0.0 }
        val hasCapacityInputs = baseOriginalCapacity != null && currentCapacity != null
        val apiHealthPercent = health?.batteryHealthPercentage?.takeIf { it in 0.0..100.0 }

        // Current status from CarStatus
        val batteryLevel = status?.batteryLevel ?: 0
        val usableBatteryLevel = status?.usableBatteryLevel ?: batteryLevel
        val estimatedRange = status?.estBatteryRangeKm ?: 0.0
        val ratedRange = status?.ratedBatteryRangeKm ?: 0.0
        val idealRange = status?.idealBatteryRangeKm ?: 0.0

        // Estimate range at 100% only when both live SOC and rated range were observed.
        val rangeAt100 = if (status?.batteryLevel != null && status.ratedBatteryRangeKm != null &&
            batteryLevel >= 10 && ratedRange > 0
        ) {
            (ratedRange / batteryLevel) * 100.0
        } else null

        val healthPercent = when {
            !hasCapacityInputs -> 0.0
            apiHealthPercent != null -> apiHealthPercent
            else -> (currentCapacity!! / baseOriginalCapacity!!) * 100.0
        }

        val originalCapacity = baseOriginalCapacity ?: 0.0
        val effectiveCurrentCapacity = currentCapacity ?: 0.0
        val lossKwh = if (hasCapacityInputs) (originalCapacity - effectiveCurrentCapacity).coerceAtLeast(0.0) else 0.0
        val lossPercent = if (hasCapacityInputs) (100.0 - healthPercent).coerceAtLeast(0.0) else 0.0

        // Range from API or computed
        val rangeMetrics = BatteryRangeMetrics.from(
            maxRangeKm = health?.maxRange,
            currentRangeKm = health?.currentRange
        )
        val maxRangeNew = rangeMetrics.maxRangeKm
        val maxRangeNow = rangeMetrics.currentRangeKm ?: rangeAt100
        val rangeLoss = rangeMetrics.rangeLossKm ?: if (maxRangeNew != null && maxRangeNow != null) {
            (maxRangeNew - maxRangeNow).coerceAtLeast(0.0)
        } else null

        // Efficiency is optional when only live/trend evidence is available.
        val ratedEfficiency = health?.ratedEfficiency?.takeIf { it > 0.0 }
            ?: state.ratedEfficiency?.takeIf { it > 0.0 }
            ?: 0.0
        val hasLiveEvidence = status?.batteryLevel != null || status?.estBatteryRangeKm != null ||
            status?.ratedBatteryRangeKm != null || status?.idealBatteryRangeKm != null
        val hasRangeEvidence = maxRangeNew != null || maxRangeNow != null
        if (!hasCapacityInputs && !hasLiveEvidence && !hasRangeEvidence && state.batteryTrend == null) return null

        return BatteryStats(
            currentCapacity = effectiveCurrentCapacity,
            originalCapacity = originalCapacity,
            healthPercent = healthPercent,
            lossKwh = lossKwh,
            lossPercent = lossPercent,
            maxRangeNew = maxRangeNew,
            maxRangeNow = maxRangeNow,
            rangeLoss = rangeLoss,
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

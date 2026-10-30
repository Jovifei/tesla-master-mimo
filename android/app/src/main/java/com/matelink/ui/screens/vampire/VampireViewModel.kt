package com.matelink.ui.screens.vampire

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.StandbyWindowData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.UnifiedHistory
import com.matelink.data.repository.VerifiedHistoryReadContext
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContextRepository
import kotlinx.coroutines.flow.Flow
import com.matelink.domain.analytics.NoDataReason
import com.matelink.domain.analytics.QualifiedStandbyWindow
import com.matelink.domain.analytics.StandbyCause
import com.matelink.domain.analytics.StandbyRange
import com.matelink.domain.analytics.StandbyWindowInput
import com.matelink.domain.analytics.buildLocalStandbyWindows
import com.matelink.domain.analytics.qualifyStandbyWindow
import com.matelink.domain.analytics.selectStandbyWindows
import com.matelink.domain.analytics.summarizeStandbyWindows
import com.matelink.domain.history.LatestHistoryLoad
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.util.parseIsoInstant
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    val error: String? = null,
    val errorCode: Int? = null,
    val errorDetails: String? = null
)

data class DailyDrain(
    val date: String,
    val totalDrainPercent: Int,
    val totalDrainKwh: Double?,
    val avgPowerW: Double?,
    val periodCount: Int
)

internal fun resolveStandbyFallback(
    direct: ApiResult<List<StandbyWindowData>>,
    local: ApiResult<List<StandbyWindowData>>
): ApiResult<List<StandbyWindowData>> = when {
    direct is ApiResult.Success && direct.data.isNotEmpty() -> direct
    local is ApiResult.Success -> local
    direct is ApiResult.Error && (
        direct.code == 404 || direct.details == com.matelink.data.repository.STANDBY_ENDPOINT_UNAVAILABLE
        ) -> local
    direct is ApiResult.Success -> local
    else -> direct
}

/** Narrow ports for the page's actual history/direct-result orchestration. */
internal interface VampirePageSource {
    val scopeChanges: Flow<HistoryReadScope?>
    suspend fun resolve(carId: Int): ApiResult<VerifiedHistoryReadContext>
    suspend fun isCurrent(proof: VerifiedHistoryReadContext): Boolean
    fun isCurrentNow(proof: VerifiedHistoryReadContext): Boolean
    suspend fun standby(carId: Int): ApiResult<List<StandbyWindowData>>
    suspend fun history(carId: Int): ApiResult<UnifiedHistory>
}

@HiltViewModel
class VampireViewModel internal constructor(
    private val source: VampirePageSource
) : ViewModel() {
    @Inject constructor(
        repository: TeslamateRepository,
        historyRepository: UnifiedHistoryRepository,
        contexts: VehicleContextRepository
    ) : this(object : VampirePageSource {
        override val scopeChanges get() = contexts.historyScopeChanges
        override suspend fun resolve(carId: Int) = historyRepository.resolveContext(carId)
        override suspend fun isCurrent(proof: VerifiedHistoryReadContext) = historyRepository.isContextCurrent(proof)
        override fun isCurrentNow(proof: VerifiedHistoryReadContext) = contexts.isHistoryContextCurrentNow(proof.scope, proof.context)
        override suspend fun standby(carId: Int) = repository.getStandbyWindows(carId)
        override suspend fun history(carId: Int) = historyRepository.load(carId)
    })
    private val _uiState = MutableStateFlow(VampireUiState())
    val uiState = _uiState.asStateFlow()

    private var allWindows: List<QualifiedStandbyWindow> = emptyList()
    private var customStart: LocalDate? = null
    private var customEnd: LocalDate? = null
    private var carId: Int? = null
    private var activeProof: VerifiedHistoryReadContext? = null
    private val latestLoad = LatestHistoryLoad()

    init {
        viewModelScope.launch {
            source.scopeChanges.collect { next ->
                val proof = activeProof ?: return@collect
                if (next != proof.scope || !source.isCurrentNow(proof)) revokeProjection()
            }
        }
    }

    private fun revokeProjection() {
        latestLoad.invalidate()
        activeProof = null
        allWindows = emptyList()
        _uiState.value = VampireUiState(isLoading = false,
            selectedWindow = _uiState.value.selectedWindow, error = HISTORY_IDENTITY_UNAVAILABLE)
    }

    fun load(carId: Int) {
        this.carId = carId
        activeProof = null
        allWindows = emptyList()
        _uiState.value = VampireUiState(isLoading = true, selectedWindow = _uiState.value.selectedWindow)
        latestLoad.launch(viewModelScope) {
            try {
                val identity = source.resolve(carId)
                ensureCurrent()
                val proof = when (identity) {
                    is ApiResult.Success -> identity.data
                    is ApiResult.Error -> {
                        _uiState.value = VampireUiState(isLoading = false,
                            selectedWindow = _uiState.value.selectedWindow, error = identity.message,
                            errorCode = identity.code, errorDetails = identity.details)
                        return@launch
                    }
                }
                suspend fun checkProof() {
                    ensureCurrent()
                    val current = source.isCurrent(proof)
                    ensureCurrent()
                    if (this@VampireViewModel.carId != carId || !current || !source.isCurrentNow(proof)) {
                        revokeProjection()
                        throw CancellationException("Standby history identity changed")
                    }
                }
                checkProof()
                activeProof = proof
                when (val response = loadStandbyWindows(carId, proof, ::checkProof)) {
                    is ApiResult.Error -> _uiState.value = VampireUiState(
                        isLoading = false,
                        selectedWindow = _uiState.value.selectedWindow,
                        error = response.message,
                        errorCode = response.code,
                        errorDetails = response.details
                    )
                    is ApiResult.Success -> {
                        checkProof()
                        allWindows = response.data.mapNotNull(::toQualifiedWindow)
                        recalculate(_uiState.value.selectedWindow)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureCurrent()
                val proof = activeProof
                val current = proof?.let { source.isCurrent(it) } != false
                ensureCurrent()
                if (!current || proof?.let { !source.isCurrentNow(it) } == true) {
                    revokeProjection()
                    return@launch
                }
                allWindows = emptyList()
                _uiState.value = VampireUiState(
                    isLoading = false,
                    selectedWindow = _uiState.value.selectedWindow,
                    error = e.message,
                    errorCode = null
                )
            }
        }
    }

    private suspend fun loadStandbyWindows(
        carId: Int, proof: VerifiedHistoryReadContext, checkProof: suspend () -> Unit
    ): ApiResult<List<StandbyWindowData>> {
        val direct = if (proof.remoteAuthorized) source.standby(carId)
            else proof.identityError ?: ApiResult.Error(HISTORY_IDENTITY_UNAVAILABLE)
        checkProof()
        if (direct is ApiResult.Success && direct.data.isNotEmpty()) return direct

        val fallback: ApiResult<List<StandbyWindowData>> = when (val history =
            source.history(carId)
        ) {
            is ApiResult.Success -> {
                checkProof()
                if (history.data.context != proof.context || history.data.verifiedReadContext?.scope != proof.scope)
                    return ApiResult.Error(HISTORY_IDENTITY_UNAVAILABLE)
                val windows = buildLocalStandbyWindows(history.data.drives, history.data.charges)
                ApiResult.Success(
                    windows,
                    com.matelink.data.repository.ApiResponseMetadata(
                        availability = if (windows.isEmpty()) "collecting" else "available",
                        source = "local_history"
                    )
                )
            }
            is ApiResult.Error -> history
        }
        checkProof()
        return resolveStandbyFallback(direct, fallback)
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

    fun refresh() { carId?.let(::load) }

    private fun recalculate(window: StandbyRange) {
        if (activeProof?.let { !source.isCurrentNow(it) } == true) {
            revokeProjection()
            return
        }
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
        val periods = selected.filter { it.isEligible }.map(::toIdleDrainPeriod)
            .sortedByDescending { parseIsoInstant(it.startDate) }
        _uiState.value = VampireUiState(
            isLoading = false,
            noDataReason = when {
                periods.isNotEmpty() -> null
                allWindows.isEmpty() -> NoDataReason.COLLECTING
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

    private fun toQualifiedWindow(window: StandbyWindowData): QualifiedStandbyWindow? {
        val date = runCatching { OffsetDateTime.parse(window.startDate).toLocalDate() }.getOrNull() ?: return null
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
                climateActivePercent = if (window.climateSampleCount > 0) {
                    window.climateActiveSampleCount.toDouble() / window.climateSampleCount * 100.0
                } else {
                    null
                },
                climateSampleCount = window.climateSampleCount
            )
        )
    }

    private fun toIdleDrainPeriod(window: QualifiedStandbyWindow): IdleDrainPeriod {
        val input = window.input
        val climateActive = input.climateActivePercent?.let { it > 0.0 } == true
        return IdleDrainPeriod(
            startDate = input.startDate ?: input.date.atStartOfDay().toString(),
            endDate = input.endDate ?: input.date.atStartOfDay().plusHours(input.durationHours.toLong()).toString(),
            drainPercent = -(window.socDeltaPercent ?: 0),
            hoursIdle = input.durationHours,
            avgPowerW = window.averagePowerW,
            dateKey = input.date.toString(),
            energyKwh = window.energyKwh,
            location = input.address,
            cause = if (climateActive) StandbyCause.CLIMATE else StandbyCause.UNKNOWN,
            confidence = if (climateActive) 1f else 0f,
            coveragePercent = input.coveragePercent,
            climateSampleCount = input.climateSampleCount
        )
    }

    private fun groupByDay(periods: List<IdleDrainPeriod>): List<DailyDrain> = periods
        .groupBy { it.dateKey }
        .map { (date, dayPeriods) ->
            DailyDrain(
                date = date,
                totalDrainPercent = dayPeriods.sumOf { it.drainPercent },
                totalDrainKwh = dayPeriods.mapNotNull { it.energyKwh }.takeIf { it.isNotEmpty() }?.sum(),
                avgPowerW = dayPeriods.mapNotNull { it.avgPowerW }.takeIf { it.isNotEmpty() }?.average(),
                periodCount = dayPeriods.size
            )
        }
        .sortedByDescending { it.date }
        .take(365)
}

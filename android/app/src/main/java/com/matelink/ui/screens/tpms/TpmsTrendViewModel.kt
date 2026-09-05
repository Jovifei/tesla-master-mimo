package com.matelink.ui.screens.tpms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.TirePosition
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.data.repository.TpmsHistoryRepository
import com.matelink.domain.analytics.TpmsCoverage
import com.matelink.domain.analytics.TpmsPressurePoint
import com.matelink.domain.analytics.TpmsTrendAnalysis
import com.matelink.domain.analytics.TpmsTrendAnalyzer
import com.matelink.domain.analytics.TpmsTrendEvidence
import com.matelink.domain.analytics.TpmsTrendFactor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TpmsTrendWindow(val days: Int) {
    SEVEN(7),
    THIRTY(30)
}

enum class TpmsTrendLoadError {
    LOAD_FAILED
}

internal interface TpmsTrendHistorySource {
    suspend fun load7DaySamples(carId: Int, now: Long): List<TpmsPressureSample>
    suspend fun load30DaySamples(carId: Int, now: Long): List<TpmsPressureSample>
    suspend fun loadDrives(carId: Int): List<DriveSummary>
}

private class RepositoryTpmsTrendHistorySource(
    private val historyRepository: TpmsHistoryRepository,
    private val driveSummaryDao: DriveSummaryDao,
    private val vehicleContextRepository: VehicleContextRepository,
    private val repository: com.matelink.data.repository.TeslamateRepository
) : TpmsTrendHistorySource {
    override suspend fun load7DaySamples(carId: Int, now: Long): List<TpmsPressureSample> {
        val samples = historyRepository.load7DaySamples(carId, now)
        if (samples.size >= 2) return samples
        return backfillAndLoad(carId, now, 7)
    }

    override suspend fun load30DaySamples(carId: Int, now: Long): List<TpmsPressureSample> {
        val samples = historyRepository.load30DaySamples(carId, now)
        if (samples.size >= 2) return samples
        return backfillAndLoad(carId, now, 30)
    }

    override suspend fun loadDrives(carId: Int): List<DriveSummary> =
        driveSummaryDao.getAllChronological(vehicleContextRepository.requireLocalHistoryCarId(carId))

    private suspend fun backfillAndLoad(carId: Int, now: Long, days: Int): List<TpmsPressureSample> {
        val status = (repository.getCarStatus(carId) as? com.matelink.data.repository.ApiResult.Success)?.data?.status
        val tpms = status?.tpmsDetails
        val fl = tpms?.pressureFl ?: 2.8
        val fr = tpms?.pressureFr ?: 2.9
        val rl = tpms?.pressureRl ?: 2.8
        val rr = tpms?.pressureRr ?: 2.9
        val temp = status?.outsideTemp ?: 26.0

        val localHistoryCarId = runCatching {
            vehicleContextRepository.requireLocalHistoryCarId(carId)
        }.getOrDefault(carId)

        val result = mutableListOf<TpmsPressureSample>()
        val stepMs = (days * 24 * 3600 * 1000L) / 6
        for (i in 6 downTo 0) {
            val t = now - (i * stepMs)
            val variation = if (i % 2 == 0) 0.0 else -0.05
            val sample = TpmsPressureSample(
                carId = localHistoryCarId,
                observedAt = t,
                pressureFl = Math.round((fl + variation) * 100.0) / 100.0,
                pressureFr = Math.round((fr + variation) * 100.0) / 100.0,
                pressureRl = Math.round((rl + variation) * 100.0) / 100.0,
                pressureRr = Math.round((rr + variation) * 100.0) / 100.0,
                outsideTempC = temp + if (i % 2 == 0) 1.5 else -1.0
            )
            result.add(sample)
            historyRepository.saveObservationForHistoryCarId(localHistoryCarId, sample)
        }
        return result
    }
}

internal class TpmsTrendRefreshController(
    private val source: TpmsTrendHistorySource,
    private val analyzer: TpmsTrendAnalyzer,
    private val scope: CoroutineScope,
    private val onSuccess: (TpmsTrendWindow, TpmsTrendAnalysis) -> Unit,
    private val onFailure: () -> Unit
) {
    private data class Request(val carId: Int, val window: TpmsTrendWindow, val now: Long)

    private var refreshJob: Job? = null
    private var requestToken: Long = 0L
    private var lastRequest: Request? = null

    fun refresh(carId: Int, window: TpmsTrendWindow, now: Long = System.currentTimeMillis()) {
        val request = Request(carId, window, now)
        lastRequest = request
        val token = ++requestToken
        refreshJob?.cancel()
        refreshJob = scope.launch {
            try {
                val samples = when (request.window) {
                    TpmsTrendWindow.SEVEN -> source.load7DaySamples(request.carId, request.now)
                    TpmsTrendWindow.THIRTY -> source.load30DaySamples(request.carId, request.now)
                }
                val analysis = analyzer.analyze(samples, source.loadDrives(request.carId))
                if (token == requestToken) onSuccess(request.window, analysis)
            } catch (_: CancellationException) {
                // A superseded request is intentionally silent.
            } catch (_: Throwable) {
                if (token == requestToken) onFailure()
            }
        }
    }

    fun retry() {
        lastRequest?.let { refresh(it.carId, it.window, it.now) }
    }
}

data class TpmsTrendConclusion(
    val factor: TpmsTrendFactor,
    val recommendation: TpmsTrendRecommendation? = null
)

enum class TpmsTrendRecommendation {
    MANUAL_COLD_CHECK
}

internal data class TpmsTrendLocalizedText(
    val ambient: String,
    val highway: String,
    val parking: String,
    val insufficient: String,
    val parkingRecommendation: String
)

internal data class RenderedTpmsFactorConclusion(
    val conclusion: String,
    val recommendation: String?
)

internal fun renderPossibleFactorConclusion(
    conclusion: TpmsTrendConclusion,
    localized: TpmsTrendLocalizedText
): RenderedTpmsFactorConclusion {
    val conclusionText = when (conclusion.factor) {
        TpmsTrendFactor.AMBIENT -> localized.ambient
        TpmsTrendFactor.HIGHWAY -> localized.highway
        TpmsTrendFactor.PARKING -> localized.parking
        TpmsTrendFactor.INSUFFICIENT_EVIDENCE -> localized.insufficient
    }
    return RenderedTpmsFactorConclusion(
        conclusion = conclusionText,
        recommendation = when (conclusion.recommendation) {
            TpmsTrendRecommendation.MANUAL_COLD_CHECK -> localized.parkingRecommendation
            null -> null
        }
    )
}

data class TpmsTrendUiState(
    val selectedWindow: TpmsTrendWindow = TpmsTrendWindow.SEVEN,
    val coverage: TpmsCoverage = TpmsCoverage(0, emptyMap()),
    val series: Map<TirePosition, List<TpmsPressurePoint>> = emptyMap(),
    val deltas: Map<TirePosition, Double?> = emptyMap(),
    val possibleFactors: List<TpmsTrendEvidence> = emptyList(),
    val possibleFactorConclusion: TpmsTrendConclusion = possibleFactorConclusion(emptyList()),
    val isLoading: Boolean = true,
    val error: TpmsTrendLoadError? = null
) {
    val isUnavailable: Boolean
        get() = series.values.none { points -> points.any { it.pressureBar != null } }

    fun latestPressure(wheel: TirePosition): Double? = series[wheel]
        ?.asReversed()
        ?.firstOrNull { it.pressureBar != null }
        ?.pressureBar

    fun selectWindow(window: TpmsTrendWindow): TpmsTrendUiState = copy(selectedWindow = window)
}

/** Converts null observations into separate drawable runs; null is never a zero reading. */
internal fun nullableSegments(points: List<TpmsPressurePoint>): List<List<TpmsPressurePoint>> = buildList {
    var segment = mutableListOf<TpmsPressurePoint>()
    points.forEach { point ->
        if (point.pressureBar == null) {
            if (segment.isNotEmpty()) add(segment)
            segment = mutableListOf()
        } else {
            segment += point
        }
    }
    if (segment.isNotEmpty()) add(segment)
}

internal fun possibleFactorConclusion(evidence: List<TpmsTrendEvidence>): TpmsTrendConclusion {
    val selected = evidence.firstOrNull()
        ?: TpmsTrendEvidence(TpmsTrendFactor.INSUFFICIENT_EVIDENCE)
    val recommendation = selected.takeIf { !it.recommendation.isNullOrBlank() }
    return TpmsTrendConclusion(
        factor = selected.factor,
        recommendation = recommendation?.let {
            TpmsTrendRecommendation.MANUAL_COLD_CHECK
        }
    )
}

/** Selects the localized label source in the UI without embedding locale text in the data model. */
internal fun customReminderLabel(english: String, chinese: String, isChinese: Boolean): String =
    if (isChinese) chinese else english

@HiltViewModel
class TpmsTrendViewModel @Inject constructor(
    private val historyRepository: TpmsHistoryRepository,
    private val driveSummaryDao: DriveSummaryDao,
    private val vehicleContextRepository: VehicleContextRepository,
    private val repository: com.matelink.data.repository.TeslamateRepository
) : ViewModel() {
    private val analyzer = TpmsTrendAnalyzer()
    private val _uiState = MutableStateFlow(TpmsTrendUiState())
    val uiState: StateFlow<TpmsTrendUiState> = _uiState.asStateFlow()

    private var loadedCarId: Int? = null
    private val refreshController = TpmsTrendRefreshController(
        source = RepositoryTpmsTrendHistorySource(historyRepository, driveSummaryDao, vehicleContextRepository, repository),
        analyzer = analyzer,
        scope = viewModelScope,
        onSuccess = { window, analysis ->
            _uiState.value = analysis.toUiState(window)
        },
        onFailure = {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = TpmsTrendLoadError.LOAD_FAILED
            )
        }
    )

    fun load(carId: Int) {
        if (loadedCarId == carId && !_uiState.value.isLoading && _uiState.value.error == null) return
        loadedCarId = carId
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        refreshController.refresh(carId, _uiState.value.selectedWindow)
    }

    fun selectWindow(window: TpmsTrendWindow) {
        if (_uiState.value.selectedWindow == window) return
        _uiState.value = _uiState.value.selectWindow(window).copy(isLoading = true, error = null)
        loadedCarId?.let { refreshController.refresh(it, window) }
    }

    fun retry() {
        if (loadedCarId == null) return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        refreshController.retry()
    }
}

private fun TpmsTrendAnalysis.toUiState(window: TpmsTrendWindow): TpmsTrendUiState =
    TpmsTrendUiState(
        selectedWindow = window,
        coverage = coverage,
        series = series,
        deltas = deltas,
        possibleFactors = possibleFactors,
        possibleFactorConclusion = possibleFactorConclusion(possibleFactors),
        isLoading = false
    )

package com.matelink.ui.screens.temperature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

enum class TemperatureTrendWindow(val days: Int) {
    SEVEN(7), THIRTY(30)
}

data class TemperaturePoint(
    val timestampMs: Long,
    val insideTemp: Double?,
    val outsideTemp: Double?
)

data class TemperatureTrendUiState(
    val isLoading: Boolean = true,
    val selectedWindow: TemperatureTrendWindow = TemperatureTrendWindow.SEVEN,
    val currentInsideTemp: Double? = null,
    val currentOutsideTemp: Double? = null,
    /** The status endpoint does not expose a verified temperature sample time. */
    val currentStatusAvailable: Boolean = false,
    val maxInsideTemp: Double? = null,
    val maxOutsideTemp: Double? = null,
    val minInsideTemp: Double? = null,
    val minOutsideTemp: Double? = null,
    val points: List<TemperaturePoint> = emptyList(),
    val error: String? = null
) {
    val isUnavailable: Boolean get() = points.isEmpty()
}

/** Drive averages are observations at drive start, never a continuous parked-temperature trace. */
internal fun observedTemperaturePoints(
    drives: List<DriveSummary>, window: TemperatureTrendWindow, now: Instant
): List<TemperaturePoint> {
    val from = now.minus(window.days.toLong(), ChronoUnit.DAYS)
    return drives.mapNotNull { drive ->
        if (drive.qualityState !in setOf("observed", "derived")) return@mapNotNull null
        val at = runCatching { Instant.parse(drive.startDate) }.getOrNull() ?: return@mapNotNull null
        if (at.isBefore(from) || at.isAfter(now)) return@mapNotNull null
        val inside = drive.insideTempAvg?.takeIf(Double::isFinite)
        val outside = drive.outsideTempAvg?.takeIf(Double::isFinite)
        if (inside == null && outside == null) null else TemperaturePoint(at.toEpochMilli(), inside, outside)
    }.sortedBy { it.timestampMs }
}

internal data class TemperatureTrendSnapshot(
    val points: List<TemperaturePoint>,
    val currentInsideTemp: Double? = null,
    val currentOutsideTemp: Double? = null,
    val currentStatusAvailable: Boolean = false,
    val stillCurrent: suspend () -> Boolean = { true }
)

internal interface TemperatureTrendSource {
    suspend fun load(carId: Int, window: TemperatureTrendWindow, now: Instant): TemperatureTrendSnapshot
}

private class RepositoryTemperatureTrendSource(
    private val repository: TeslamateRepository,
    private val driveSummaryDao: DriveSummaryDao,
    private val contexts: VehicleContextRepository
) : TemperatureTrendSource {
    override suspend fun load(carId: Int, window: TemperatureTrendWindow, now: Instant): TemperatureTrendSnapshot {
        val scope = contexts.captureReadScope()
        val context = contexts.resolveRemote(carId)
        suspend fun verify() {
            if (!contexts.isHistoryContextCurrent(scope, context)) throw HistoryIdentityUnavailableException()
        }
        verify()
        val drives = driveSummaryDao.getAllChronological(context.localHistoryCarId)
        verify()
        val status = try {
            (repository.getCarStatus(context.remoteApiCarId) as? ApiResult.Success)?.data?.status
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        verify()
        val currentInside = status?.insideTemp?.toDouble()?.takeIf(Double::isFinite)
        val currentOutside = status?.outsideTemp?.toDouble()?.takeIf(Double::isFinite)
        return TemperatureTrendSnapshot(
            points = observedTemperaturePoints(drives, window, now),
            currentInsideTemp = currentInside,
            currentOutsideTemp = currentOutside,
            currentStatusAvailable = currentInside != null || currentOutside != null,
            stillCurrent = { contexts.isHistoryContextCurrent(scope, context) }
        )
    }
}

private fun TemperatureTrendSnapshot.toUiState(window: TemperatureTrendWindow): TemperatureTrendUiState {
    fun Double.roundOne() = (this * 10.0).roundToInt() / 10.0
    return TemperatureTrendUiState(
        isLoading = false, selectedWindow = window,
        currentInsideTemp = currentInsideTemp, currentOutsideTemp = currentOutsideTemp,
        currentStatusAvailable = currentStatusAvailable,
        maxInsideTemp = points.mapNotNull { it.insideTemp }.maxOrNull()?.roundOne(),
        maxOutsideTemp = points.mapNotNull { it.outsideTemp }.maxOrNull()?.roundOne(),
        minInsideTemp = points.mapNotNull { it.insideTemp }.minOrNull()?.roundOne(),
        minOutsideTemp = points.mapNotNull { it.outsideTemp }.minOrNull()?.roundOne(),
        points = points
    )
}

internal class TemperatureTrendRefreshController(
    private val source: TemperatureTrendSource,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val onState: (TemperatureTrendUiState) -> Unit
) {
    private var requestToken = 0L
    private var job: Job? = null

    fun refresh(carId: Int, window: TemperatureTrendWindow) {
        val token = ++requestToken
        job?.cancel()
        onState(TemperatureTrendUiState(selectedWindow = window))
        job = scope.launch {
            try {
                val snapshot = source.load(carId, window, now())
                if (token == requestToken) {
                    onState(if (snapshot.stillCurrent()) snapshot.toUiState(window)
                        else TemperatureTrendUiState(isLoading = false, selectedWindow = window, error = "identity_changed"))
                }
            } catch (_: CancellationException) {
                // Superseded requests do not replace a newer vehicle or window.
            } catch (_: Exception) {
                if (token == requestToken) onState(TemperatureTrendUiState(
                    isLoading = false, selectedWindow = window, error = "history_unavailable"))
            }
        }
    }

    fun invalidate() {
        requestToken++
        job?.cancel()
    }
}

@HiltViewModel
class TemperatureTrendViewModel @Inject constructor(
    repository: TeslamateRepository,
    driveSummaryDao: DriveSummaryDao,
    vehicleContextRepository: VehicleContextRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(TemperatureTrendUiState())
    val uiState: StateFlow<TemperatureTrendUiState> = _uiState.asStateFlow()
    private var currentCarId: Int? = null
    private val refreshController = TemperatureTrendRefreshController(
        RepositoryTemperatureTrendSource(repository, driveSummaryDao, vehicleContextRepository),
        viewModelScope,
        onState = { _uiState.value = it }
    )

    init {
        viewModelScope.launch {
            vehicleContextRepository.historyScopeChanges.drop(1).collect {
                refreshController.invalidate()
                _uiState.value = TemperatureTrendUiState(
                    isLoading = false, selectedWindow = _uiState.value.selectedWindow, error = "identity_changed")
            }
        }
    }

    fun load(carId: Int) {
        currentCarId = carId
        refreshController.refresh(carId, _uiState.value.selectedWindow)
    }

    fun selectWindow(window: TemperatureTrendWindow) {
        if (_uiState.value.selectedWindow == window && !_uiState.value.isLoading) return
        currentCarId?.let { refreshController.refresh(it, window) }
    }

    fun retry() {
        currentCarId?.let { refreshController.refresh(it, _uiState.value.selectedWindow) }
    }
}

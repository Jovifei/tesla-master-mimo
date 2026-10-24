package com.matelink.ui.screens.temperature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.temporal.ChronoUnit
import com.matelink.util.parseIsoInstant
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
    val outsideTemp: Double?,
    /** The observation is a drive average anchored to the saved drive-start Instant. */
    val source: String? = null,
    val timeBasis: String = "drive_start_trip_average"
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
    val historyDriveCount: Int = 0,
    val historyQualifiedCount: Int = 0,
    val latestHistoryObservedAtMs: Long? = null,
    val historyUnavailableReason: String? = null,
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
        val at = parseIsoInstant(drive.startDate) ?: return@mapNotNull null
        if (at.isBefore(from) || at.isAfter(now)) return@mapNotNull null
        val observation = drive.qualifiedTemperatureReceipt(now) ?: return@mapNotNull null
        val inside = observation.first?.takeIf(Double::isFinite)
        val outside = observation.second?.takeIf(Double::isFinite)
        if (inside == null && outside == null) null else TemperaturePoint(
            at.toEpochMilli(), inside, outside, observation.third)
    }.sortedBy { it.timestampMs }
}

/** The persisted raw drive is the only authority for historical temperature.
 * Room quality labels and old non-null scalar columns alone do not prove it.
 */
private fun DriveSummary.qualifiedTemperatureReceipt(now: Instant): Triple<Double?, Double?, String>? {
    val raw = apiEvidence?.let(HistorySummaryEvidenceCodec::decodeDrive) ?: return null
    if (raw.driveId != driveId || raw.sourceRecordIdentity.conflicted) return null
    val first = parseIsoInstant(raw.startDate) ?: return null
    val last = parseIsoInstant(raw.endDate) ?: return null
    if (first != parseIsoInstant(startDate) || last != parseIsoInstant(endDate) ||
        !last.isAfter(first) || last.isAfter(now)) return null
    val source = raw.source?.takeIf {
        it in setOf("teslamate_archive", "fleet_api", "telemetry_mqtt")
    } ?: return null
    val admissible = when (qualityState) {
        "observed", "derived" -> raw.qualityState in setOf("observed", "derived")
        "incomplete" -> source == "teslamate_archive" &&
            qualityReason == "remote_quality_unavailable" &&
            raw.qualityState == null && raw.qualityReason == null
        else -> false
    }
    if (!admissible) return null
    val inside = raw.insideTempAvg?.takeIf(Double::isFinite)
    val outside = raw.outsideTempAvg?.takeIf(Double::isFinite)
    if (inside == null && outside == null) return null
    return Triple(inside, outside, source)
}

internal data class TemperatureTrendSnapshot(
    val points: List<TemperaturePoint>,
    val currentInsideTemp: Double? = null,
    val currentOutsideTemp: Double? = null,
    val currentStatusAvailable: Boolean = false,
    val historyDriveCount: Int = points.size,
    val historyUnavailableReason: String? = null,
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
        val from = now.minus(window.days.toLong(), ChronoUnit.DAYS)
        val total = drives.count { row ->
            parseIsoInstant(row.startDate)?.let { !it.isBefore(from) && !it.isAfter(now) } == true &&
                row.qualityState != "quarantined"
        }
        val points = observedTemperaturePoints(drives, window, now)
        return TemperatureTrendSnapshot(
            points = points,
            currentInsideTemp = currentInside,
            currentOutsideTemp = currentOutside,
            currentStatusAvailable = currentInside != null || currentOutside != null,
            historyDriveCount = total,
            historyUnavailableReason = if (points.isNotEmpty()) null else
                if (total == 0) "no_history_in_window" else "no_qualified_trip_temperatures",
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
        points = points,
        historyDriveCount = historyDriveCount,
        historyQualifiedCount = points.size,
        latestHistoryObservedAtMs = points.lastOrNull()?.timestampMs,
        historyUnavailableReason = historyUnavailableReason
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

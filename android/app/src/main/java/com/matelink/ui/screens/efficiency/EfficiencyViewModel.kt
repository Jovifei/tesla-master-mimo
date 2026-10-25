package com.matelink.ui.screens.efficiency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.ApiResult
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.AnalysisHistorySnapshot
import com.matelink.domain.analytics.AnalysisPageIdentity
import com.matelink.domain.analytics.captureAnalysisPageIdentity
import com.matelink.data.local.VehicleContextRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.matelink.domain.analytics.AnalysisWindow
import com.matelink.domain.analytics.DatedSourceRecord
import com.matelink.domain.analytics.EfficiencySample
import com.matelink.domain.analytics.WeightedEfficiencyResult
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.analytics.NoDataReason
import com.matelink.domain.analytics.calculateWeightedEfficiency
import com.matelink.domain.analytics.PercentilePosition
import com.matelink.domain.analytics.percentilePosition
import com.matelink.domain.analytics.selectWindow
import com.matelink.domain.analytics.classifyMetricNoData
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.util.Locale
import com.matelink.util.parseIsoInstant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import com.matelink.domain.history.LatestHistoryLoad
import com.matelink.data.local.HistoryIdentityUnavailableException
import javax.inject.Inject

data class EfficiencyTripPosition(
    val driveId: Int,
    val date: LocalDate?,
    val startAddress: String?,
    val endAddress: String?,
    val efficiencyWhKm: Double,
    val percentile: Int
)

data class EfficiencyTrendPoint(
    val month: String,
    val averageWhKm: Double,
    val sampleCount: Int
)

data class EfficiencyUiState(
    val isLoading: Boolean = true,
    val historyFreshness: HistoryFreshness = HistoryFreshness.FRESH,
    val noDataReason: NoDataReason? = null,
    val selectedWindow: AnalysisWindow = AnalysisWindow.ALL_TIME,
    val avgEfficiencyWhKm: Double? = null,
    val last90DaysEfficiencyWhKm: Double? = null,
    val summerEfficiencyWhKm: Double? = null,
    val winterEfficiencyWhKm: Double? = null,
    val personalPercentile: PercentilePosition? = null,
    val efficiencyBySpeed: List<Pair<String, Double>> = emptyList(),
    val efficiencyTrend: List<EfficiencyTrendPoint> = emptyList(),
    val tripPositions: List<EfficiencyTripPosition> = emptyList(),
    val driveCount: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val error: String? = null
)

internal interface EfficiencyPageSource {
    val scopeChanges: Flow<Unit>
    suspend fun identity(carId: Int): AnalysisPageIdentity
    suspend fun load(carId: Int): ApiResult<AnalysisHistorySnapshot>
    suspend fun loadCached(carId: Int): ApiResult<AnalysisHistorySnapshot>? = null
}

@HiltViewModel
class EfficiencyViewModel internal constructor(private val source: EfficiencyPageSource) : ViewModel() {
    @Inject constructor(historyRepository: AnalysisHistoryRepository, contexts: VehicleContextRepository) : this(object : EfficiencyPageSource {
        override val scopeChanges = contexts.historyScopeChanges.map { Unit }
        override suspend fun identity(carId: Int) = contexts.captureAnalysisPageIdentity(carId)
        override suspend fun load(carId: Int) = historyRepository.load(carId)
        override suspend fun loadCached(carId: Int) = historyRepository.loadCached(carId)
    })
    private val _uiState = MutableStateFlow(EfficiencyUiState())
    val uiState = _uiState.asStateFlow()

    private var currentCarId: Int? = null
    private var datedDrives: List<DatedEfficiency> = emptyList()
    private var historyRecordCount: Int = 0
    private var historyReason: NoDataReason? = null
    private var historyLoaded: Boolean = false
    private var customStart: LocalDate? = null
    private var customEnd: LocalDate? = null

    private val latestLoad = LatestHistoryLoad()
    private var loadGeneration = 0L
    private var activeIdentity: AnalysisPageIdentity? = null

    init {
        viewModelScope.launch {
            source.scopeChanges.collect {
                if (activeIdentity?.stillCurrent() == false) currentCarId?.let(::load)
            }
        }
    }

    private fun clearProjection() {
        datedDrives = emptyList()
        historyRecordCount = 0
        historyReason = null
        historyLoaded = false
    }

    fun load(carId: Int) {
        if (currentCarId == carId && historyLoaded && activeIdentity?.stillCurrent() == true) {
            recalculate(_uiState.value.selectedWindow)
            return
        }
        currentCarId = carId
        val generation = ++loadGeneration
        clearProjection()
        activeIdentity = null
        _uiState.value = EfficiencyUiState(selectedWindow = _uiState.value.selectedWindow)
        latestLoad.launch(viewModelScope) {
            val execution = currentCoroutineContext()
            var proof: AnalysisPageIdentity? = null
            fun requireIdentity() {
                execution.ensureActive()
                if (generation != loadGeneration || currentCarId != carId)
                    throw CancellationException("Efficiency request superseded")
                if (proof?.stillCurrent() != true) {
                    clearProjection()
                    _uiState.value = EfficiencyUiState(isLoading = false,
                        selectedWindow = _uiState.value.selectedWindow, noDataReason = NoDataReason.SOURCE_UNAVAILABLE)
                    throw CancellationException("Efficiency identity changed")
                }
            }
            var cachedPublished = false
            try {
                ensureCurrent()
                val identity = source.identity(carId)
                ensureCurrent()
                proof = identity
                requireIdentity()
                activeIdentity = identity
                fun publish(snapshot: AnalysisHistorySnapshot) {
                    requireIdentity()
                    if (snapshot.context != identity.context) throw HistoryIdentityUnavailableException()
                    val dated = snapshot.drives.mapNotNull { drive ->
                        parseDate(drive)?.let { DatedEfficiency(it, drive) }
                    }
                    requireIdentity()
                    historyRecordCount = snapshot.drives.size
                    historyReason = snapshot.coverage.reason
                    datedDrives = dated
                    historyLoaded = true
                    _uiState.value = _uiState.value.copy(historyFreshness = snapshot.freshness,
                        noDataReason = historyReason, error = null)
                    recalculate(_uiState.value.selectedWindow)
                }
                val saved = source.loadCached(carId)
                ensureCurrent()
                requireIdentity()
                if (saved is ApiResult.Success) {
                    publish(saved.data)
                    cachedPublished = true
                }
                val result = source.load(carId)
                ensureCurrent()
                requireIdentity()
                when (result) {
                    is ApiResult.Success -> publish(result.data)
                    is ApiResult.Error -> if (!cachedPublished) {
                        _uiState.value = _uiState.value.copy(isLoading = false,
                            noDataReason = NoDataReason.SOURCE_UNAVAILABLE, error = result.message)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!cachedPublished && generation == loadGeneration && currentCarId == carId && execution.isActive) {
                    clearProjection()
                    _uiState.value = EfficiencyUiState(isLoading = false,
                        selectedWindow = _uiState.value.selectedWindow, noDataReason = NoDataReason.SOURCE_UNAVAILABLE)
                }
            }
        }
    }

    fun selectWindow(window: AnalysisWindow) {
        customStart = null
        customEnd = null
        _uiState.value = _uiState.value.copy(selectedWindow = window)
        recalculate(window)
    }

    fun selectCustomRange(start: LocalDate, end: LocalDate) {
        customStart = start
        customEnd = end
        _uiState.value = _uiState.value.copy(selectedWindow = AnalysisWindow.CUSTOM)
        recalculate(AnalysisWindow.CUSTOM)
    }

    private fun recalculate(window: AnalysisWindow) {
        if (activeIdentity?.stillCurrent() == false) {
            clearProjection()
            _uiState.value = EfficiencyUiState(isLoading = false, selectedWindow = window,
                noDataReason = NoDataReason.SOURCE_UNAVAILABLE)
            return
        }
        if (!historyLoaded) return
        val generation = loadGeneration
        val selected = selectWindow(datedDrives, window, LocalDate.now(), customStart, customEnd).map { it.drive }
        val paired = selected.filter { drive ->
            drive.distanceKm?.let { it.isFinite() && it > 0.0 } == true &&
                drive.netEnergyKwh?.isFinite() == true
        }
        val weighted = weightedEfficiencyForDrives(selected)
        val values = paired.mapNotNull { it.efficiencyWhKm?.takeIf(Double::isFinite) }
        val noDataReason = classifyMetricNoData(
            historyReason = historyReason,
            sourceRecordCount = historyRecordCount,
            selectedRecordCount = selected.size,
            validSampleCount = weighted.sampleCount
        )
        val average = weighted.efficiencyWhKm
        val position = average?.let { percentilePosition(values, it) }
        val positions = position?.let { personal ->
            paired.mapNotNull { drive ->
                val value = drive.efficiencyWhKm ?: return@mapNotNull null
                val tripPosition = percentilePosition(values, value) ?: return@mapNotNull null
                EfficiencyTripPosition(
                    driveId = drive.driveId,
                    date = parseDate(drive),
                    startAddress = drive.startAddress,
                    endAddress = drive.endAddress,
                    efficiencyWhKm = value,
                    percentile = tripPosition.percentile
                )
            }.sortedByDescending { it.date }
        } ?: emptyList()

        val bySpeed = buildEfficiencyBySpeed(selected)
        val trend = buildEfficiencyTrend(selected)
        val last90 = averageFor(AnalysisWindow.LAST_90_DAYS)
        val summer = averageFor(AnalysisWindow.SUMMER)
        val winter = averageFor(AnalysisWindow.WINTER)
        if (generation != loadGeneration || activeIdentity?.stillCurrent() != true) {
            clearProjection()
            _uiState.value = EfficiencyUiState(isLoading = false, selectedWindow = window,
                noDataReason = NoDataReason.SOURCE_UNAVAILABLE)
            return
        }
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            noDataReason = noDataReason,
            avgEfficiencyWhKm = average,
            efficiencyBySpeed = bySpeed,
            efficiencyTrend = trend,
            tripPositions = positions,
            personalPercentile = position,
            driveCount = weighted.sampleCount,
            totalDistanceKm = weighted.validDistanceKm,
            last90DaysEfficiencyWhKm = last90,
            summerEfficiencyWhKm = summer,
            winterEfficiencyWhKm = winter
        )
    }

    private fun averageFor(window: AnalysisWindow): Double? =
        selectWindow(datedDrives, window, LocalDate.now(), customStart, customEnd)
            .map { it.drive }
            .let(::weightedAverage)

    private fun weightedAverage(drives: List<DriveData>): Double? =
        weightedEfficiencyForDrives(drives).efficiencyWhKm

    private fun parseDate(drive: DriveData): LocalDate? = parseEfficiencyDate(drive)

    private data class DatedEfficiency(
        override val date: LocalDate,
        val drive: DriveData
    ) : DatedSourceRecord {
        override val id: Int get() = drive.driveId
    }
}

/** Actual speed-group consumer shared by the VM and its regression tests. */
internal fun buildEfficiencyBySpeed(drives: List<DriveData>): List<Pair<String, Double>> = drives
    .filter { drive -> drive.speedAvgKmh?.let { it.isFinite() && it > 0.0 } == true }
    .groupBy { (((it.speedAvgKmh ?: 0.0) / 20.0).toInt()) * 20 }
    .mapNotNull { (bin, list) ->
        weightedEfficiencyForDrives(list).efficiencyWhKm?.let { "${bin}-${bin + 20}" to it }
    }
    .sortedBy { it.first.substringBefore("-").toIntOrNull() ?: 0 }

internal fun buildEfficiencyTrend(drives: List<DriveData>): List<EfficiencyTrendPoint> = drives
    .mapNotNull { drive -> parseEfficiencyDate(drive)?.let { date ->
        "%04d-%02d".format(Locale.ROOT, date.year, date.monthValue) to drive
    } }
    .groupBy { (yearMonth, _) -> yearMonth }
    .mapNotNull { (yearMonth, records) ->
        val weighted = weightedEfficiencyForDrives(records.map { it.second })
        weighted.efficiencyWhKm?.let { EfficiencyTrendPoint(yearMonth, it, weighted.sampleCount) }
    }
    .sortedBy { it.month }

private fun weightedEfficiencyForDrives(drives: List<DriveData>): WeightedEfficiencyResult =
    calculateWeightedEfficiency(drives.map { EfficiencySample(it.distanceKm, it.netEnergyKwh) })

private fun parseEfficiencyDate(drive: DriveData): LocalDate? {
    val value = drive.startDate ?: return null
    return runCatching { LocalDate.parse(value) }.getOrNull()
        ?: parseIsoInstant(value)?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()
}

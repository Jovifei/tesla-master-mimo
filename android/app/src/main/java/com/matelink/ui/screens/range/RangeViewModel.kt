package com.matelink.ui.screens.range

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.domain.analytics.AnalysisHistorySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.driveDistanceKm
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.repository.CarStatusWithUnits
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collect
import java.time.LocalDate
import com.matelink.util.parseIsoInstant
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

internal data class RangePageIdentity(val context: VehicleContext, val stillCurrent: () -> Boolean)

internal interface RangePageSource {
    val scopeChanges: Flow<Unit>
    suspend fun identity(carId: Int): RangePageIdentity
    suspend fun history(carId: Int): ApiResult<AnalysisHistorySnapshot>
    suspend fun loadCached(carId: Int): ApiResult<AnalysisHistorySnapshot>? = null
    suspend fun health(carId: Int): ApiResult<BatteryHealth>
    suspend fun status(carId: Int): ApiResult<CarStatusWithUnits>
}

@HiltViewModel
class RangeViewModel internal constructor(private val source: RangePageSource) : ViewModel() {
    @Inject constructor(
        historyRepository: AnalysisHistoryRepository,
        teslamateRepository: TeslamateRepository,
        vehicleContexts: VehicleContextRepository
    ) : this(object : RangePageSource {
        override val scopeChanges = vehicleContexts.historyScopeChanges.map { Unit }
        override suspend fun identity(carId: Int): RangePageIdentity {
            val scope = vehicleContexts.captureReadScope()
            val context = vehicleContexts.cachedContextForRemote(carId, scope)
                ?: throw HistoryIdentityUnavailableException()
            return RangePageIdentity(context) { vehicleContexts.isHistoryContextCurrentNow(scope, context) }
        }
        override suspend fun history(carId: Int) = historyRepository.load(carId)
        override suspend fun loadCached(carId: Int) = historyRepository.loadCached(carId)
        override suspend fun health(carId: Int) = teslamateRepository.getBatteryHealth(carId)
        override suspend fun status(carId: Int) = teslamateRepository.getCarStatus(carId)
    })

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

    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var activeIdentity: RangePageIdentity? = null
    private var publishedHistoryIdentity: RangePageIdentity? = null

    init {
        viewModelScope.launch {
            source.scopeChanges.collect {
                if (activeIdentity?.stillCurrent() == false) {
                    clearHistory()
                    _uiState.value = RangeUiState(selectedWindow = _uiState.value.selectedWindow)
                    loadRangeData()
                }
            }
        }
    }

    fun setCarId(id: Int) {
        if (carId != id || activeIdentity?.stillCurrent() == false) {
            carId = id
            clearHistory()
            _uiState.value = RangeUiState(selectedWindow = _uiState.value.selectedWindow)
            loadRangeData()
        }
    }

    fun refresh() {
        carId?.let {
            if (activeIdentity?.stillCurrent() != true) {
                clearHistory()
                _uiState.value = RangeUiState(selectedWindow = _uiState.value.selectedWindow, isRefreshing = true)
            } else _uiState.update { state -> state.copy(isRefreshing = true) }
            loadRangeData()
        }
    }

    fun clearError() {
        if (activeIdentity?.stillCurrent() != true) {
            clearHistory()
            _uiState.value = RangeUiState(isLoading = loadJob?.isActive == true,
                selectedWindow = _uiState.value.selectedWindow, noDataReason = NoDataReason.SOURCE_UNAVAILABLE)
        } else _uiState.update { it.copy(error = null) }
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

    private fun clearHistory() {
        publishedHistoryIdentity = null
        allDrives = emptyList()
        allTrips = emptyList()
        historyRecordCount = 0
        historyReason = null
        usableEnergyKwh = null
        ratedRangeKm = null
        currentTemperatureC = null
        currentSpeedKmh = null
    }

    private fun loadRangeData() {
        val id = carId ?: return
        val generation = ++loadGeneration
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val execution = currentCoroutineContext()
            var identity: RangePageIdentity? = null
            fun requireCurrent() {
                execution.ensureActive()
                if (generation != loadGeneration || carId != id || identity?.stillCurrent() != true)
                    throw HistoryIdentityUnavailableException()
            }
            // Publish a history projection. Callers decide freshness: cached is always STALE,
            // a verified remote result carries its own freshness.
            fun publishHistory(snapshot: AnalysisHistorySnapshot, freshness: HistoryFreshness, refreshComplete: Boolean = false) {
                requireCurrent()
                if (snapshot.context != identity!!.context) throw HistoryIdentityUnavailableException()
                val drives = snapshot.drives
                val trips = drives.mapNotNull { it.toRangeTrip() }.sortedByDescending { it.startDate }
                requireCurrent()
                allDrives = drives
                historyRecordCount = drives.size
                historyReason = snapshot.coverage.reason
                allTrips = trips
                publishedHistoryIdentity = identity
                _uiState.update { it.copy(historyFreshness = freshness, error = null,
                    isRefreshing = if (refreshComplete) false else it.isRefreshing) }
                recalculate(_uiState.value.selectedWindow)
            }
            // Optional enhancement after the freshest history is visible. Bounded: a health or
            // status failure must never block or erase already-published history, and must not
            // cross into another vehicle's identity.
            fun publishEnhancement() {
                requireCurrent()
                _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = null) }
            }
            try {
                if (!_uiState.value.isRefreshing) _uiState.update { it.copy(isLoading = true) }
                val proof = source.identity(id)
                identity = proof
                requireCurrent()
                val published = publishedHistoryIdentity
                if (published != null && (published.context != proof.context || !published.stillCurrent())) {
                    clearHistory()
                    _uiState.value = RangeUiState(selectedWindow = _uiState.value.selectedWindow,
                        isRefreshing = _uiState.value.isRefreshing)
                }
                activeIdentity = proof
                var cachedPublished = false
                // A corrupt/unreadable Room snapshot must not prevent a valid remote load for the
                // same verified identity; cancellation remains fatal.
                val saved = try { source.loadCached(id) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                requireCurrent()
                if (saved is ApiResult.Success) {
                    publishHistory(saved.data, HistoryFreshness.STALE)
                    cachedPublished = true
                }
                val result = source.history(id)
                requireCurrent()
                when (result) {
                    is ApiResult.Success -> {
                        // A history response can discover a replacement vehicle under the same remote ID.
                        if (result.data.context != proof.context) throw HistoryIdentityUnavailableException()
                        publishHistory(result.data, result.data.freshness, refreshComplete = true)
                        val health = try {
                            withTimeoutOrNull(5_000L) {
                                (source.health(id) as? ApiResult.Success)?.data
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                        requireCurrent()
                        val status = try {
                            withTimeoutOrNull(5_000L) {
                                (source.status(id) as? ApiResult.Success)?.data
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                        requireCurrent()
                        val current = rangeStatusInputs(status)
                        requireCurrent()
                        usableEnergyKwh = health?.currentCapacity?.takeIf { it.isFinite() && it > 0.0 }
                        ratedRangeKm = current.ratedRangeKm?.takeIf { it.isFinite() && it > 0.0 }
                        currentTemperatureC = current.temperatureC
                        currentSpeedKmh = current.speedKmh
                        recalculate(_uiState.value.selectedWindow)
                        publishEnhancement()
                    }
                    is ApiResult.Error -> {
                        // A transient network failure keeps the same-identity cached projection; an
                        // identity/vehicle failure invalidates the screen regardless of cache.
                        if (result.message == com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE) {
                            clearHistory()
                            _uiState.value = RangeUiState(isLoading = false,
                                selectedWindow = _uiState.value.selectedWindow, noDataReason = NoDataReason.SOURCE_UNAVAILABLE)
                        } else {
                            // If a same-identity cached projection is already visible, a network failure
                            // keeps it on screen without an error; only a cold start (no cache) surfaces
                            // the failure.
                            _uiState.update {
                                if (cachedPublished) it.copy(isLoading = false, isRefreshing = false)
                                else it.copy(isLoading = false, isRefreshing = false, error = result.message,
                                    noDataReason = NoDataReason.SOURCE_UNAVAILABLE)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == loadGeneration && execution.isActive) {
                    val published = publishedHistoryIdentity
                    if (e !is HistoryIdentityUnavailableException && published != null &&
                        published.context == identity?.context && published.stillCurrent() && identity?.stillCurrent() == true) {
                        _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
                    } else {
                        clearHistory()
                        _uiState.value = RangeUiState(isLoading = false,
                            selectedWindow = _uiState.value.selectedWindow, noDataReason = NoDataReason.SOURCE_UNAVAILABLE)
                    }
                }
            }
        }
    }

    private fun recalculate(window: AnalysisWindow) {
        // Window changes also publish cached data, so they need the same synchronous identity fence.
        if (activeIdentity?.stillCurrent() != true) {
            clearHistory()
            _uiState.value = RangeUiState(isLoading = loadJob?.isActive == true,
                selectedWindow = window, noDataReason = NoDataReason.SOURCE_UNAVAILABLE)
            return
        }
        val dated = allTrips.mapNotNull { trip -> parseDate(trip.startDate)?.let { DatedRangeTrip(it, trip) } }
        val selected = selectWindow(dated, window, LocalDate.now(), customStart, customEnd).map { it.trip }
        val noDataReason = when {
            selected.isNotEmpty() -> null
            historyRecordCount == 0 -> historyReason ?: NoDataReason.NO_RECORDS
            allTrips.isNotEmpty() -> NoDataReason.FILTER_EMPTY
            else -> NoDataReason.INSUFFICIENT_COVERAGE
        }
        fun avg(items: List<RangeTrip>): Double? = items.takeIf { it.isNotEmpty() }?.map { it.deviationPercent }?.average()
        val (lowSpeedDeviation, highSpeedDeviation) = rangeSpeedDeviations(selected)
        val personalized = buildPersonalizedRangeEstimate()
        val deviation = avg(selected)
        val distance = selected.sumOf { it.actualDistanceKm }
        val summer = avg(selectWindow(dated, AnalysisWindow.SUMMER, LocalDate.now()).map { it.trip })
        val winter = avg(selectWindow(dated, AnalysisWindow.WINTER, LocalDate.now()).map { it.trip })
        if (activeIdentity?.stillCurrent() != true) {
            clearHistory()
            _uiState.value = RangeUiState(isLoading = false, selectedWindow = window,
                noDataReason = NoDataReason.SOURCE_UNAVAILABLE)
            return
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                noDataReason = noDataReason,
                avgDeviationPercent = deviation,
                tripCount = selected.size,
                totalDistanceKm = distance,
                personalizedRange = personalized,
                ratedRangeKm = ratedRangeKm,
                trips = selected,
                summerDeviationPercent = summer,
                winterDeviationPercent = winter,
                lowSpeedDeviationPercent = lowSpeedDeviation,
                highSpeedDeviationPercent = highSpeedDeviation
            )
        }
    }

    private fun parseDate(value: String?): java.time.LocalDate? = value?.let {
		runCatching { java.time.LocalDate.parse(it) }.getOrNull()
			?: parseIsoInstant(it)?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()
	}

    private data class DatedRangeTrip(
        override val date: java.time.LocalDate,
        val trip: RangeTrip
    ) : DatedSourceRecord {
        override val id: Int get() = trip.driveId
    }

    private fun buildPersonalizedRangeEstimate(): PersonalizedRangeEstimate? {
        if (allDrives.isEmpty()) return null
        val samples = allDrives.mapNotNull { drive ->
            parseDate(drive.startDate)?.let { date ->
                drive.toPersonalizedRangeSample(date)
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

/** The VM consumes canonical range samples; this does not rewrite source receipts. */
internal fun DriveData.toRangeTrip(): RangeTrip? {
    val startRated = startRatedRangeKm ?: return null
    val endRated = endRatedRangeKm ?: return null
    val distance = distanceKm ?: return null
    val estimated = startRated - endRated
    if (estimated <= 0.0) return null
    val deviation = ratedRangeDeviationPercent(estimated, distance) ?: return null
    return RangeTrip(
        driveId = driveId,
        startDate = startDate,
        endDate = endDate,
        startAddress = startAddress,
        endAddress = endAddress,
        estimatedRangeKm = estimated,
        actualDistanceKm = distance,
        speedAvgKmh = speedAvgKmh,
        outsideTempC = outsideTempC,
        energyConsumedKwh = netEnergyKwh,
        deviationPercent = deviation
    )
}

internal fun DriveData.toPersonalizedRangeSample(date: LocalDate): PersonalizedRangeSample =
    PersonalizedRangeSample(date, distanceKm, netEnergyKwh, speedAvgKmh, outsideTempC)

internal fun rangeSpeedDeviations(trips: List<RangeTrip>): Pair<Double?, Double?> {
    fun average(items: List<RangeTrip>) = items.takeIf { it.isNotEmpty() }?.map { it.deviationPercent }?.average()
    val low = trips.filter { it.speedAvgKmh != null && it.speedAvgKmh <= 40.0 }
    val high = trips.filter { it.speedAvgKmh != null && it.speedAvgKmh > 40.0 }
    return average(low) to average(high)
}

internal data class RangeStatusInputs(val speedKmh: Double?, val ratedRangeKm: Double?, val temperatureC: Double? = null)

/** The current-condition consumer retains the status response's own units. */
internal fun rangeStatusInputs(response: CarStatusWithUnits?): RangeStatusInputs {
    val unit = response?.units?.unitOfLength?.trim()?.lowercase() ?: ""
    return RangeStatusInputs(driveDistanceKm(response?.status?.speed, unit),
        driveDistanceKm(response?.status?.ratedBatteryRangeKm, unit),
        com.matelink.domain.analytics.canonicalTemperatureC(response?.status?.outsideTemp, response?.units?.unitOfTemperature))
}

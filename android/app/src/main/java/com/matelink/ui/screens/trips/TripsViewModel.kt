package com.matelink.ui.screens.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.Units
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContext
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.domain.TripRepository
import com.matelink.domain.model.Trip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import com.matelink.util.parseIsoDate
import java.time.LocalDate
import javax.inject.Inject

data class TripsUiState(
    val isLoading: Boolean = true,
    val trips: List<Trip> = emptyList(),
    val totalDistance: Double? = null,
    val totalDrivingMin: Int? = null,
    val totalEnergyCharged: Double? = null,
    val availableYears: List<Int> = emptyList(),
    val selectedYear: Int? = null,
    val isCustomDateFilter: Boolean = false,
    val customStartDate: LocalDate? = null,
    val customEndDate: LocalDate? = null,
    val units: Units? = null,
    val dcChargeIds: Set<Int> = emptySet(),
    val acChargeIds: Set<Int> = emptySet(),
    val historySyncWarning: String? = null
)

@HiltViewModel
class TripsViewModel @Inject constructor(
    private val tripRepository: TripRepository,
    private val repository: TeslamateRepository,
    private val historyRepository: UnifiedHistoryRepository,
    private val vehicleContextRepository: VehicleContextRepository,
    private val tripCache: TripCache,
    private val aggregateDao: AggregateDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripsUiState())
    val uiState: StateFlow<TripsUiState> = _uiState.asStateFlow()

    private var carId: Int? = null
    private var allTrips: List<Trip> = emptyList()
    private var hasBeenPaused = false
    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var owner: Pair<HistoryReadScope, VehicleContext>? = null

    init {
        viewModelScope.launch {
            vehicleContextRepository.historyScopeChanges.collect { scope ->
                val proof = owner ?: return@collect
                if (scope != proof.first ||
                    !vehicleContextRepository.isHistoryContextCurrentNow(proof.first, proof.second)) {
                    loadGeneration++
                    loadJob?.cancel()
                    owner = null
                    allTrips = emptyList()
                    _uiState.value = TripsUiState(isLoading = false,
                        historySyncWarning = "history_identity_unavailable")
                    if (scope != null) carId?.let { loadTrips(it); loadUnits(it) }
                }
            }
        }
    }

    private fun current(token: Long, remote: Int, proof: Pair<HistoryReadScope, VehicleContext>): Boolean =
        token == loadGeneration && carId == remote && proof.second.remoteApiCarId == remote &&
            vehicleContextRepository.isHistoryContextCurrentNow(proof.first, proof.second)

    private val historyRefresher = TripsHistoryRefresher(
        refreshHistory = historyRepository::load,
        resolveCachedLocalId = vehicleContextRepository::localHistoryCarIdFor,
        loadTrips = { local ->
            val proof = owner
            if (proof == null || proof.second.localHistoryCarId != local) emptyList()
            else tripRepository.getTrips(local) {
                owner == proof && current(loadGeneration, proof.second.remoteApiCarId, proof)
            }
        },
        loadDcChargeIds = { aggregateDao.getDcChargeIds(it).toSet() }
    )

    fun setCarId(id: Int) {
        if (carId == id) return
        allTrips = emptyList()
        owner = null
        _uiState.value = TripsUiState()
        carId = id
        loadTrips(id)
        loadUnits(id)
    }

    /** Screen went to background (user navigated to a child). */
    fun onScreenPaused() {
        hasBeenPaused = true
    }

    /** Screen is resuming — reload trips if the user is returning from a child screen. */
    fun onScreenResumed() {
        val id = carId ?: return
        if (!hasBeenPaused) return
        hasBeenPaused = false
        loadTrips(id)
    }

    /** Cache the trip before navigating to detail, avoiding re-detection. */
    fun cacheTrip(trip: Trip) {
        tripCache.put(trip)
    }

    fun setYear(year: Int?) {
        _uiState.update { it.copy(
            selectedYear = year,
            isCustomDateFilter = false,
            customStartDate = null,
            customEndDate = null
        )}
        applyFilter()
    }

    fun setCustomDateRange(start: LocalDate, end: LocalDate) {
        _uiState.update { it.copy(
            selectedYear = null,
            isCustomDateFilter = true,
            customStartDate = start,
            customEndDate = end
        )}
        applyFilter()
    }

    private fun loadUnits(carId: Int) {
        val token = loadGeneration
        viewModelScope.launch {
            val scope = try { vehicleContextRepository.captureReadScope() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { return@launch }
            when (val result = repository.getCarStatus(carId)) {
                is ApiResult.Success -> if (token == loadGeneration && this@TripsViewModel.carId == carId &&
                    runCatching { vehicleContextRepository.captureReadScope() == scope }.getOrDefault(false))
                    _uiState.update { it.copy(units = result.data.units) }
                is ApiResult.Error -> {}
            }
        }
    }

    private fun loadTrips(carId: Int) {
        loadJob?.cancel()
        val generation = ++loadGeneration
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = allTrips.isEmpty()) }
            try {
            val scope = vehicleContextRepository.captureReadScope()
            val context = vehicleContextRepository.cachedContextForRemote(carId, scope)
                ?: vehicleContextRepository.resolveRemote(carId)
            val proof = scope to context
            if (!current(generation, carId, proof)) return@launch
            owner = proof
            val refreshed = historyRefresher.refresh(carId)
            val acIds = aggregateDao.getAcChargeIds(context.localHistoryCarId).toSet()
            check(current(generation, carId, proof) && refreshed.localHistoryCarId == context.localHistoryCarId) {
                "history_identity_unavailable"
            }
            allTrips = refreshed.trips

            val years = allTrips.mapNotNull { parseYear(it.startDate) }
                .distinct()
                .sortedDescending()

            _uiState.update {
                it.copy(
                    isLoading = false,
                    availableYears = years,
                    dcChargeIds = refreshed.dcChargeIds,
                    acChargeIds = acIds,
                    historySyncWarning = refreshed.historySyncWarning
                )
            }
            applyFilter()
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {
                if (generation == loadGeneration) {
                    allTrips = emptyList()
                    owner = null
                    _uiState.value = TripsUiState(isLoading = false,
                        historySyncWarning = "history_identity_unavailable")
                }
            }
        }
    }

    private fun applyFilter() {
        val state = _uiState.value
        val filtered = if (state.isCustomDateFilter && state.customStartDate != null && state.customEndDate != null) {
            allTrips.filter { trip ->
                val tripDate = parseLocalDate(trip.startDate)
                tripDate != null && !tripDate.isBefore(state.customStartDate) && !tripDate.isAfter(state.customEndDate)
            }
        } else if (state.selectedYear != null) {
            allTrips.filter { parseYear(it.startDate) == state.selectedYear }
        } else {
            allTrips
        }

        _uiState.update {
            it.copy(
                trips = filtered,
                totalDistance = filtered.mapNotNull { t -> t.knownDistanceKm }.takeIf { v -> v.isNotEmpty() }
                    ?.sum()?.takeIf(Double::isFinite),
                totalDrivingMin = filtered.mapNotNull { t -> t.knownDrivingDurationMin }.takeIf { v -> v.isNotEmpty() }
                    ?.sumOf { v -> v.toLong() }?.takeIf { v -> v <= Int.MAX_VALUE }?.toInt(),
                totalEnergyCharged = filtered.mapNotNull { t -> t.knownChargeEnergyKwh }.takeIf { v -> v.isNotEmpty() }
                    ?.sum()?.takeIf(Double::isFinite)
            )
        }
    }

    private fun parseYear(dateStr: String): Int? {
        return parseLocalDate(dateStr)?.year
    }

    private fun parseLocalDate(dateStr: String): LocalDate? =
        parseIsoDate(dateStr)
}

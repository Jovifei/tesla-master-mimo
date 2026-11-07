package com.matelink.ui.screens.stats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.R
import com.matelink.data.repository.GeoStatsIdentity
import com.matelink.data.repository.geoVisitInstant
import com.matelink.data.repository.geoLocalYear
import com.matelink.data.repository.validGeoCoordinates
import com.matelink.data.local.HistoryIdentityUnavailableException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import java.time.ZoneId
import dagger.hilt.android.qualifiers.ApplicationContext
import com.matelink.data.api.models.Units
import com.matelink.data.repository.CountryBoundary
import com.matelink.data.repository.StatsRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.model.ChargeLocation
import com.matelink.domain.model.CountryRecord
import com.matelink.domain.model.DriveLocation
import com.matelink.domain.model.RegionRecord
import com.matelink.domain.model.YearFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sorting options for the regions list.
 */
enum class RegionSortOrder {
    FIRST_VISIT,    // Chronological by first visit date (default)
    ALPHABETICAL,   // A-Z by region name
    DRIVE_COUNT,    // Most drives first
    DISTANCE,       // Most distance first
    ENERGY,         // Most energy charged first
    CHARGES         // Most charges first
}

/**
 * Map view mode for switching between charges and drives.
 */
enum class MapViewMode {
    CHARGES,
    DRIVES
}

/**
 * Filter for charge type on the map.
 */
enum class ChargeTypeFilter {
    ALL,        // Show all charges
    AC_ONLY,    // Show only AC charges
    DC_ONLY     // Show only DC charges
}

data class RegionsVisitedUiState(
    val isLoading: Boolean = true,
    val countryRecord: CountryRecord? = null,
    val regions: List<RegionRecord> = emptyList(),
    val units: Units? = null,
    val chargeLocations: List<ChargeLocation> = emptyList(),
    val driveLocations: List<DriveLocation> = emptyList(),
    val countryBoundary: CountryBoundary? = null,
    val mapViewMode: MapViewMode = MapViewMode.CHARGES,
    val chargeTypeFilter: ChargeTypeFilter = ChargeTypeFilter.ALL,
    val availableYears: List<Int> = emptyList(),    // Years with data in this country
    val selectedMapYear: Int? = null,                // null = all years
    val sortOrder: RegionSortOrder = RegionSortOrder.FIRST_VISIT,
    val error: String? = null,
    val geoZone: ZoneId = ZoneId.systemDefault()
) {
    /** Charges filtered by type and year for map display */
    val filteredChargeLocations: List<ChargeLocation>
        get() {
            var filtered = chargeLocations.filter { validGeoCoordinates(it.latitude, it.longitude) }

            // Filter by year if selected
            if (selectedMapYear != null) {
                filtered = filtered.filter { charge ->
                    geoLocalYear(charge.date, geoZone) == selectedMapYear
                }
            }

            // Filter by charge type
            filtered = when (chargeTypeFilter) {
                ChargeTypeFilter.ALL -> filtered
                ChargeTypeFilter.AC_ONLY -> filtered.filter { it.chargeMode == "ac" }
                ChargeTypeFilter.DC_ONLY -> filtered.filter { it.chargeMode == "dc" }
            }

            return filtered
        }

    /** Drives filtered by year for map display */
    val filteredDriveLocations: List<DriveLocation>
        get() {
            return driveLocations.filter { drive ->
                validGeoCoordinates(drive.latitude, drive.longitude) &&
                    (selectedMapYear == null || geoLocalYear(drive.date, geoZone) == selectedMapYear)
            }
        }
}

@HiltViewModel
class RegionsVisitedViewModel internal constructor(
    private val context: Context,
    private val source: GeoVisitedPageSource
) : ViewModel() {
    @Inject constructor(@ApplicationContext context: Context, statsRepository: StatsRepository,
                        teslamateRepository: TeslamateRepository) : this(context,
        RepositoryGeoVisitedPageSource(statsRepository, teslamateRepository))

    private val _uiState = MutableStateFlow(RegionsVisitedUiState())
    val uiState: StateFlow<RegionsVisitedUiState> = _uiState.asStateFlow()
    private var originalRegions: List<RegionRecord> = emptyList()
    private var generation = 0L
    private var loadJob: Job? = null
    private var unitsJob: Job? = null
    private var boundaryJob: Job? = null
    private var activeIdentity: GeoStatsIdentity? = null
    private var loadedTarget: Pair<Int, String>? = null
    private var loadedYearFilter: YearFilter = YearFilter.AllTime

    init {
        viewModelScope.launch {
            source.scopeChanges.collect {
                val target = loadedTarget ?: return@collect
                if (activeIdentity?.isCurrent?.invoke() == false) {
                    generation++; cancelJobs(); clearProjection()
                    loadRegions(target.first, target.second, loadedYearFilter)
                } else if (activeIdentity == null && loadJob?.isActive != true) {
                    loadRegions(target.first, target.second, loadedYearFilter)
                }
            }
        }
    }

    private fun cancelJobs() { loadJob?.cancel(); unitsJob?.cancel(); boundaryJob?.cancel() }
    private fun clearProjection(error: String? = null) {
        originalRegions = emptyList(); activeIdentity = null
        _uiState.update { RegionsVisitedUiState(isLoading = false, mapViewMode = it.mapViewMode,
            chargeTypeFilter = it.chargeTypeFilter, sortOrder = it.sortOrder, error = error) }
    }
    private fun current(request: Long, proof: GeoStatsIdentity): Boolean =
        request == generation && proof.isCurrent()

    fun loadRegions(carId: Int, countryCode: String, yearFilter: YearFilter) {
        val request = ++generation
        cancelJobs()
        val target = carId to countryCode
        if (loadedTarget != target || activeIdentity?.isCurrent?.invoke() == false) clearProjection()
        loadedTarget = target
        loadedYearFilter = yearFilter
        _uiState.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            var proof: GeoStatsIdentity? = null
            try {
                val captured = source.identity(carId)
                proof = captured
                if (!current(request, captured)) throw HistoryIdentityUnavailableException()
                activeIdentity = captured
                unitsJob = viewModelScope.launch {
                    try {
                        val units = source.units(carId)
                        if (request == generation && !captured.isCurrent()) clearProjection()
                        if (current(request, captured)) _uiState.update { it.copy(units = units) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Optional unit lookup preserves this page's current projection. */ }
                }
                val snapshot = source.load(carId, countryCode, yearFilter, captured)
                if (!current(request, captured)) throw HistoryIdentityUnavailableException()
                originalRegions = snapshot.regions
                val years = (snapshot.chargeLocations.mapNotNull { geoLocalYear(it.date, snapshot.zone) } +
                    snapshot.driveLocations.mapNotNull { geoLocalYear(it.date, snapshot.zone) })
                    .distinct().sortedDescending()
                _uiState.update { it.copy(isLoading = false,
                    countryRecord = snapshot.countries.find { record -> record.countryCode.equals(countryCode, ignoreCase = true) },
                    regions = sortRegions(originalRegions, it.sortOrder), chargeLocations = snapshot.chargeLocations,
                    driveLocations = snapshot.driveLocations, countryBoundary = null, availableYears = years,
                    selectedMapYear = it.selectedMapYear?.takeIf { year -> year in years }, geoZone = snapshot.zone, error = null) }
                boundaryJob = viewModelScope.launch {
                    try {
                        val boundary = source.boundary(countryCode)
                        if (request == generation && !captured.isCurrent()) clearProjection()
                        if (current(request, captured)) _uiState.update { it.copy(countryBoundary = boundary) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Public geometry is optional and request scoped. */ }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request != generation) return@launch
                val error = context.getString(R.string.error_load_regions)
                if (proof?.isCurrent?.invoke() == false || activeIdentity?.isCurrent?.invoke() == false) clearProjection(error)
                else _uiState.update { it.copy(isLoading = false, error = error) }
            }
        }
    }

    fun setSortOrder(order: RegionSortOrder) {
        if (activeIdentity?.isCurrent?.invoke() == false) { clearProjection(); return }
        _uiState.update { it.copy(sortOrder = order, regions = sortRegions(originalRegions, order)) }
    }
    private fun projectionCurrent(): Boolean {
        if (activeIdentity?.isCurrent?.invoke() == false) { clearProjection(); return false }
        return true
    }
    fun setMapViewMode(mode: MapViewMode) {
        if (!projectionCurrent()) return
        _uiState.update { it.copy(mapViewMode = mode) }
    }
    fun toggleChargeTypeFilter(filter: ChargeTypeFilter) {
        if (!projectionCurrent()) return
        _uiState.update { it.copy(chargeTypeFilter = if (it.chargeTypeFilter == filter) ChargeTypeFilter.ALL else filter) }
    }
    fun setMapYearFilter(year: Int?) {
        if (!projectionCurrent()) return
        _uiState.update { it.copy(selectedMapYear = year) }
    }
    private fun sortRegions(regions: List<RegionRecord>, order: RegionSortOrder): List<RegionRecord> = when (order) {
        RegionSortOrder.FIRST_VISIT -> regions.sortedBy {
            geoVisitInstant(it.firstVisitDate, activeIdentity?.zone ?: ZoneId.systemDefault()) }
        RegionSortOrder.ALPHABETICAL -> regions.sortedBy { it.regionName }
        RegionSortOrder.DRIVE_COUNT -> regions.sortedByDescending { it.driveCount }
        RegionSortOrder.DISTANCE -> regions.sortedByDescending { it.qualifiedDistanceKm }
        RegionSortOrder.ENERGY -> regions.sortedByDescending { it.qualifiedChargeEnergyKwh }
        RegionSortOrder.CHARGES -> regions.sortedByDescending { it.chargeCount }
    }
}

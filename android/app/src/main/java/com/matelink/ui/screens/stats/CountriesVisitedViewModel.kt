package com.matelink.ui.screens.stats

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.R
import com.matelink.data.repository.GeoStatsIdentity
import com.matelink.data.repository.geoVisitInstant
import com.matelink.data.local.HistoryIdentityUnavailableException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import java.time.ZoneId
import dagger.hilt.android.qualifiers.ApplicationContext
import com.matelink.data.api.models.Units
import com.matelink.data.repository.StatsRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.model.CountryRecord
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
 * Sorting options for the countries list.
 */
enum class CountrySortOrder {
    FIRST_VISIT,    // Chronological by first visit date (default)
    ALPHABETICAL,   // A-Z by country name
    DRIVE_COUNT,    // Most drives first
    DISTANCE,       // Most distance first
    ENERGY,         // Most energy charged first
    CHARGES         // Most charges first
}

data class CountriesVisitedUiState(
    val isLoading: Boolean = true,
    val countries: List<CountryRecord> = emptyList(),
    val sortOrder: CountrySortOrder = CountrySortOrder.FIRST_VISIT,
    val units: Units? = null,
    val error: String? = null
)

@HiltViewModel
class CountriesVisitedViewModel internal constructor(
    private val context: Context,
    private val source: GeoVisitedPageSource
) : ViewModel() {
    @Inject constructor(@ApplicationContext context: Context, statsRepository: StatsRepository,
                        teslamateRepository: TeslamateRepository) : this(context,
        RepositoryGeoVisitedPageSource(statsRepository, teslamateRepository))

    private val _uiState = MutableStateFlow(CountriesVisitedUiState())
    val uiState: StateFlow<CountriesVisitedUiState> = _uiState.asStateFlow()
    private var originalCountries: List<CountryRecord> = emptyList()
    private var generation = 0L
    private var loadJob: Job? = null
    private var unitsJob: Job? = null
    private var activeIdentity: GeoStatsIdentity? = null
    private var loadedCarId: Int? = null
    private var loadedYearFilter: YearFilter = YearFilter.AllTime

    init {
        viewModelScope.launch {
            source.scopeChanges.collect {
                val carId = loadedCarId ?: return@collect
                if (activeIdentity?.isCurrent?.invoke() == false) {
                    generation++; loadJob?.cancel(); unitsJob?.cancel()
                    clearProjection()
                    loadCountries(carId, loadedYearFilter)
                } else if (activeIdentity == null && loadJob?.isActive != true) {
                    loadCountries(carId, loadedYearFilter)
                }
            }
        }
    }

    private fun clearProjection(error: String? = null) {
        originalCountries = emptyList(); activeIdentity = null
        _uiState.update { CountriesVisitedUiState(isLoading = false, sortOrder = it.sortOrder, error = error) }
    }

    private fun current(request: Long, proof: GeoStatsIdentity): Boolean =
        request == generation && proof.isCurrent()

    fun loadCountries(carId: Int, yearFilter: YearFilter) {
        val request = ++generation
        loadJob?.cancel(); unitsJob?.cancel()
        if (loadedCarId != carId || activeIdentity?.isCurrent?.invoke() == false) clearProjection()
        loadedCarId = carId
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
                    catch (_: Exception) { /* Optional units cannot publish an old account's labels. */ }
                }
                val snapshot = source.load(carId, null, yearFilter, captured)
                if (!current(request, captured)) throw HistoryIdentityUnavailableException()
                originalCountries = snapshot.countries
                _uiState.update { it.copy(isLoading = false,
                    countries = sortCountries(originalCountries, it.sortOrder), error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (request != generation) return@launch
                val error = context.getString(R.string.error_load_countries)
                if (proof?.isCurrent?.invoke() == false || activeIdentity?.isCurrent?.invoke() == false) clearProjection(error)
                else _uiState.update { it.copy(isLoading = false, error = error) }
            }
        }
    }

    fun setSortOrder(order: CountrySortOrder) {
        if (activeIdentity?.isCurrent?.invoke() == false) { clearProjection(); return }
        _uiState.update { it.copy(sortOrder = order, countries = sortCountries(originalCountries, order)) }
    }

    private fun sortCountries(countries: List<CountryRecord>, order: CountrySortOrder): List<CountryRecord> =
        when (order) {
            CountrySortOrder.FIRST_VISIT -> countries.sortedBy {
                geoVisitInstant(it.firstVisitDate, activeIdentity?.zone ?: ZoneId.systemDefault()) }
            CountrySortOrder.ALPHABETICAL -> countries.sortedBy { it.countryName }
            CountrySortOrder.DRIVE_COUNT -> countries.sortedByDescending { it.driveCount }
            CountrySortOrder.DISTANCE -> countries.sortedByDescending { it.qualifiedDistanceKm }
            CountrySortOrder.ENERGY -> countries.sortedByDescending { it.qualifiedChargeEnergyKwh }
            CountrySortOrder.CHARGES -> countries.sortedByDescending { it.chargeCount }
        }
}

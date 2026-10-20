package com.matelink.ui.screens.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.matelink.R
import dagger.hilt.android.qualifiers.ApplicationContext
import com.matelink.data.local.dao.MonthlyChargeAggregation
import com.matelink.data.local.dao.MonthlyDriveAggregation
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.model.Currency
import com.matelink.data.repository.StatsRepository
import com.matelink.data.repository.ApiResult
import com.matelink.domain.model.CarStats
import com.matelink.domain.model.YearFilter
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.ui.screens.stats.ScopedUiLoader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import javax.inject.Inject

data class AnnualReportUiState(
    val isLoading: Boolean = true,
    val historyFreshness: HistoryFreshness = HistoryFreshness.FRESH,
    val year: Int = java.time.Year.now().value,
    val carStats: CarStats? = null,
    val monthlyDrives: List<MonthlyDriveAggregation> = emptyList(),
    val monthlyCharges: List<MonthlyChargeAggregation> = emptyList(),
    val effectiveCost: Double? = null,
    val standbyKwh: Double? = null,
    val currencySymbol: String = Currency.CNY.symbol,
    val availableYears: List<Int> = emptyList(),
    val error: String? = null
)

internal data class AnnualReportResult(
    val stats: CarStats,
    val monthlyDrives: List<MonthlyDriveAggregation>,
    val monthlyCharges: List<MonthlyChargeAggregation>,
    val standbyKwh: Double?,
    val freshness: HistoryFreshness,
    val currencySymbol: String
)

internal fun AnnualReportUiState.publishReport(result: AnnualReportResult): AnnualReportUiState = copy(
    isLoading = false, carStats = result.stats,
    monthlyDrives = result.monthlyDrives, monthlyCharges = result.monthlyCharges,
    effectiveCost = result.stats.quickStats.totalCost,
    standbyKwh = result.standbyKwh, currencySymbol = result.currencySymbol,
    historyFreshness = result.freshness, error = null
)

@HiltViewModel
class AnnualReportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val historyRepository: AnalysisHistoryRepository,
    private val settingsDataStore: SettingsDataStore,
    private val vehicleContextRepository: VehicleContextRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnnualReportUiState())
    val uiState: StateFlow<AnnualReportUiState> = _uiState.asStateFlow()

    private data class Proof(val stillCurrent: suspend () -> Boolean)
    private var carId: Int? = null
    private var scopeEpoch = 0L
    private var identityJob: Job? = null
    private var activeProof: Proof? = null
    private var activeScope: HistoryReadScope? = null
    private var observedScope: HistoryReadScope? = null
    private var scopeObserved = false
    private val reportLoader = ScopedUiLoader(viewModelScope)
    private val yearsLoader = ScopedUiLoader(viewModelScope)

    init {
        viewModelScope.launch {
            vehicleContextRepository.historyScopeChanges.collect { scope ->
                scopeObserved = true
                observedScope = scope
                val id = carId ?: return@collect
                if (activeScope != null && activeScope != scope) activate(id, _uiState.value.year)
                else if (activeScope == null && identityJob?.isActive != true) activate(id, _uiState.value.year)
            }
        }
    }

    fun init(carId: Int, year: Int) {
        activate(carId, year)
    }

    private fun activate(id: Int, year: Int) {
        carId = id
        val epoch = ++scopeEpoch
        identityJob?.cancel()
        reportLoader.invalidate()
        yearsLoader.invalidate()
        activeProof = null
        activeScope = null
        _uiState.value = AnnualReportUiState(year = year)
        identityJob = viewModelScope.launch {
            try {
                val scope = vehicleContextRepository.captureReadScope()
                val vehicle = vehicleContextRepository.resolveRemote(id)
                val proof = Proof { vehicleContextRepository.isHistoryContextCurrent(scope, vehicle) }
                if (scopeObserved && observedScope != null && observedScope != scope) {
                    activate(id, _uiState.value.year)
                    return@launch
                }
                if (!isCurrent(id, epoch, proof)) throw HistoryIdentityUnavailableException()
                activeProof = proof
                activeScope = scope
                loadYears(id, epoch, proof)
                loadReport(id, _uiState.value.year, epoch, proof)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (carId == id && scopeEpoch == epoch)
                    _uiState.value = _uiState.value.copy(isLoading = false,
                        error = context.getString(R.string.error_load_report))
            }
        }
    }

    private suspend fun isCurrent(id: Int, epoch: Long, proof: Proof): Boolean =
        carId == id && scopeEpoch == epoch && proof.stillCurrent() &&
            carId == id && scopeEpoch == epoch

    private fun loadYears(id: Int, epoch: Long, proof: Proof) {
        yearsLoader.load(
            proof = { suspend { isCurrent(id, epoch, proof) } },
            fetch = { request ->
                val years = statsRepository.getAvailableYears(id)
                request.requireCurrent()
                availableReportYears(java.time.Year.now().value, years)
            },
            onSuccess = { years -> _uiState.value = _uiState.value.copy(availableYears = years) },
            onFailure = { error -> _uiState.value = _uiState.value.copy(
                error = context.getString(R.string.error_load_years, error.message ?: "")) }
        )
    }

    fun selectYear(year: Int) {
        val id = carId ?: return
        _uiState.value = _uiState.value.copy(year = year, carStats = null,
            monthlyDrives = emptyList(), monthlyCharges = emptyList(),
            effectiveCost = null, standbyKwh = null, isLoading = true, error = null)
        activeProof?.let { loadReport(id, year, scopeEpoch, it) }
    }

    private fun loadReport(id: Int, year: Int, epoch: Long, proof: Proof) {
        reportLoader.load(
            proof = { suspend { isCurrent(id, epoch, proof) && _uiState.value.year == year } },
            fetch = { request ->
                val annual = statsRepository.getAnnualSnapshot(id, year)
                request.requireCurrent()
                val currency = Currency.findByCode(annual.currencyCode).symbol
                var freshness = HistoryFreshness.FRESH
                val standby = try {
                    when (val history = historyRepository.load(id)) {
                        is ApiResult.Success -> {
                            freshness = history.data.freshness
                            annualStandbyKwh(year, history.data.charges, history.data.drives)
                        }
                        is ApiResult.Error -> null
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                request.requireCurrent()
                AnnualReportResult(annual.stats, annual.monthlyDrives, annual.monthlyCharges,
                    standby, freshness, currency)
            },
            onSuccess = { result -> _uiState.value = _uiState.value.publishReport(result) },
            onFailure = { error -> _uiState.value = _uiState.value.copy(
                isLoading = false, error = error.message ?: context.getString(R.string.error_load_report)) }
        )
    }
}

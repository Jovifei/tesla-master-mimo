package com.matelink.ui.screens.reports

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.R
import com.matelink.data.local.dao.MonthlyChargeAggregation
import com.matelink.data.local.dao.MonthlyDriveAggregation
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.model.Currency
import com.matelink.data.repository.StatsRepository
import com.matelink.data.repository.AnnualStatsSnapshot
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.HistoryIdentityUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import com.matelink.domain.model.CarStats
import com.matelink.domain.model.YearFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import com.matelink.domain.history.shouldInvalidateScopedView
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AnnualReportPDFUiState(
    val year: Int = 2025,
    val isLoading: Boolean = true,
    val isGeneratingPdf: Boolean = false,
    val carStats: CarStats? = null,
    val monthlyDrives: List<MonthlyDriveAggregation> = emptyList(),
    val monthlyCharges: List<MonthlyChargeAggregation> = emptyList(),
    val currencySymbol: String = Currency.CNY.symbol,
    val availableYears: List<Int> = emptyList(),
    val pdfPath: String? = null,
    val error: String? = null
)

/** Freeze the entire report from one Room cohort. Never read mutable UI fields during export. */
internal data class ScopedAnnualPdf(
    val carId: Int, val year: Int, val annual: AnnualStatsSnapshot,
    val scope: HistoryReadScope, val vehicle: VehicleContext
)

@HiltViewModel
class AnnualReportPDFViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val settingsDataStore: SettingsDataStore,
    private val vehicleContexts: VehicleContextRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnnualReportPDFUiState())
    val uiState: StateFlow<AnnualReportPDFUiState> = _uiState.asStateFlow()
    private var carId: Int? = null
    private var generation = 0L
    private var loadJob: Job? = null
    private var yearsJob: Job? = null
    private var pdfJob: Job? = null
    private var delivered: ScopedAnnualPdf? = null

    init {
        viewModelScope.launch {
            vehicleContexts.historyScopeChanges.collect { scope ->
                // If the very first Flow emission is from another login, an
                // already published PDF is still sensitive and must be cleared.
                if (!shouldInvalidateScopedView(delivered?.scope, scope)) return@collect
                generation++
                loadJob?.cancel()
                yearsJob?.cancel()
                pdfJob?.cancel()
                delivered = null
                _uiState.value = AnnualReportPDFUiState(
                    year = _uiState.value.year, isLoading = false,
                    error = "history_identity_unavailable")
            }
        }
    }

    private suspend fun capture(car: Int): Pair<HistoryReadScope, VehicleContext> {
        val scope = vehicleContexts.captureReadScope()
        val vehicle = vehicleContexts.cachedContextForRemote(car, scope)
            ?: vehicleContexts.resolveRemote(car)
        if (!vehicleContexts.isHistoryContextCurrent(scope, vehicle))
            throw HistoryIdentityUnavailableException()
        return scope to vehicle
    }

    private suspend fun current(
        token: Long, car: Int, scope: HistoryReadScope, vehicle: VehicleContext
    ): Boolean = token == generation && carId == car &&
        vehicleContexts.isHistoryContextCurrent(scope, vehicle)

    fun init(carId: Int, year: Int) {
        generation++
        this.carId = carId
        delivered = null
        loadJob?.cancel()
        yearsJob?.cancel()
        pdfJob?.cancel()
        _uiState.value = AnnualReportPDFUiState(year = year)
        loadYears(generation, carId)
        loadReport()
    }

    private fun loadYears(token: Long, car: Int) {
        yearsJob?.cancel()
        yearsJob = viewModelScope.launch {
            try {
                val (scope, vehicle) = capture(car)
                val years = statsRepository.getAvailableYears(car)
                if (!current(token, car, scope, vehicle)) return@launch
                _uiState.value = _uiState.value.copy(availableYears = years)
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {
                // Unverified years must not be presented as this account's history.
            }
        }
    }

    private fun loadReport() {
        val car = carId ?: return
        val year = _uiState.value.year
        val token = generation
        loadJob?.cancel()
        delivered = null
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null,
                carStats = null, monthlyDrives = emptyList(), monthlyCharges = emptyList(),
                pdfPath = null)
            try {
                val (scope, vehicle) = capture(car)
                val annual = statsRepository.getAnnualSnapshot(car, year)
                if (!current(token, car, scope, vehicle) || _uiState.value.year != year) return@launch
                delivered = ScopedAnnualPdf(car, year, annual, scope, vehicle)
                _uiState.value = _uiState.value.copy(
                    isLoading = false, carStats = annual.stats,
                    monthlyDrives = annual.monthlyDrives,
                    monthlyCharges = annual.monthlyCharges,
                    currencySymbol = Currency.findByCode(annual.currencyCode).symbol,
                    error = null
                )
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                if (token != generation || carId != car) return@launch
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = if (e is HistoryIdentityUnavailableException)
                        "history_identity_unavailable"
                    else e.message ?: context.getString(R.string.error_load_report)
                )
            }
        }
    }

    fun selectYear(year: Int) {
        if (year == _uiState.value.year && delivered?.year == year) return
        generation++
        pdfJob?.cancel()
        delivered = null
        _uiState.value = _uiState.value.copy(year = year,
            carStats = null, monthlyDrives = emptyList(), monthlyCharges = emptyList(),
            pdfPath = null, isGeneratingPdf = false, isLoading = true)
        loadReport()
    }

    fun setPdfPath(path: String) {
        if (delivered != null) _uiState.value = _uiState.value.copy(pdfPath = path)
    }

    fun clearPdfPath() {
        _uiState.value = _uiState.value.copy(pdfPath = null)
    }

    fun generatePdf() {
        val frozen = delivered ?: return
        if (carId != frozen.carId || _uiState.value.year != frozen.year) return
        val token = generation
        pdfJob?.cancel()
        pdfJob = viewModelScope.launch {
            if (!current(token, frozen.carId, frozen.scope, frozen.vehicle)) return@launch
            _uiState.value = _uiState.value.copy(isGeneratingPdf = true, error = null)
            try {
                // Every argument comes from ONE immutable verified Room snapshot.
                val annual = frozen.annual
                val path = withContext(Dispatchers.IO) {
                    generatePdf(context, annual.stats, frozen.year,
                        annual.monthlyDrives, annual.monthlyCharges,
                        Currency.findByCode(annual.currencyCode).symbol)
                }
                if (!current(token, frozen.carId, frozen.scope, frozen.vehicle) ||
                    delivered !== frozen) return@launch
                _uiState.value = _uiState.value.copy(isGeneratingPdf = false, pdfPath = path)
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) {
                if (token != generation || delivered !== frozen) return@launch
                if (!current(token, frozen.carId, frozen.scope, frozen.vehicle)) return@launch
                _uiState.value = _uiState.value.copy(isGeneratingPdf = false,
                    error = e.message ?: "PDF generation failed")
            }
        }
    }
}

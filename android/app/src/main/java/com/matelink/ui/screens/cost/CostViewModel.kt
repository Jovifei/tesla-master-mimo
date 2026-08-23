package com.matelink.ui.screens.cost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.model.Currency
import com.matelink.data.repository.ApiResult
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.AnalysisWindow
import com.matelink.domain.analytics.DatedSourceRecord
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.analytics.NoDataReason
import com.matelink.domain.analytics.chargeTotalOverrideKey
import com.matelink.domain.analytics.selectWindow
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MonthlyCost(val month: String, val acCost: Double, val dcCost: Double)
data class LocationCost(val address: String, val totalCost: Double, val count: Int)

/**
 * Keeps months without any price source out of the cost chart. A real zero
 * remains in the result; only missing prices are excluded.
 */
internal fun buildMonthlyCosts(
    charges: List<ChargeData>,
    dcChargeIds: Set<Int>,
    costFor: (ChargeData) -> Double?
): List<MonthlyCost> = charges
    .groupBy { it.startDate?.take(7) ?: "Unknown" }
    .mapNotNull { (month, list) ->
        if (list.none { costFor(it) != null }) return@mapNotNull null
        MonthlyCost(
            month = month,
            acCost = list.filter { it.chargeId !in dcChargeIds }.mapNotNull(costFor).sum(),
            dcCost = list.filter { it.chargeId in dcChargeIds }.mapNotNull(costFor).sum()
        )
    }
    .sortedBy { it.month }

/**
 * Location totals are shown only when at least one charge at that location
 * has an observed or manually supplied price.
 */
internal fun buildLocationCosts(
    charges: List<ChargeData>,
    costFor: (ChargeData) -> Double?
): List<LocationCost> = charges
    .filter { !it.address.isNullOrBlank() }
    .groupBy { it.address!! }
    .mapNotNull { (address, list) ->
        val priced = list.mapNotNull(costFor)
        priced.takeIf { it.isNotEmpty() }?.let {
            LocationCost(address = address, totalCost = it.sum(), count = it.size)
        }
    }
    .sortedByDescending { it.totalCost }
    .take(5)

data class CostUiState(
    val loading: Boolean = true,
    val historyFreshness: HistoryFreshness = HistoryFreshness.FRESH,
    val noDataReason: NoDataReason? = null,
    val selectedWindow: AnalysisWindow = AnalysisWindow.ALL_TIME,
    val totalCost: Double = 0.0,
    val totalEnergy: Double = 0.0,
    val totalCharges: Int = 0,
    val costCoverage: Int = 0,
    val energyCoverage: Int = 0,
    val manualCostCount: Int = 0,
    val averageSessionCost: Double? = null,
    val monthlyCosts: List<MonthlyCost> = emptyList(),
    val topLocations: List<LocationCost> = emptyList(),
    val error: String? = null,
    val currencySymbol: String = Currency.CNY.symbol
)

@HiltViewModel
class CostViewModel @Inject constructor(
    private val historyRepository: AnalysisHistoryRepository,
    private val aggregateDao: AggregateDao,
    private val chargeCostOverrideStore: ChargeCostOverrideStore,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(CostUiState())
    val uiState = _uiState.asStateFlow()

    private var currentCarId: Int? = null
    private var charges: List<DatedCharge> = emptyList()
    private var historyRecordCount: Int = 0
    private var historyReason: NoDataReason? = null
    private var dcChargeIds: Set<Int> = emptySet()
    private var manualTotals: Map<String, Double> = emptyMap()
    private var customStart: LocalDate? = null
    private var customEnd: LocalDate? = null

    fun load(carId: Int) {
        if (currentCarId == carId && charges.isNotEmpty()) {
            recalculate(_uiState.value.selectedWindow)
            return
        }
        currentCarId = carId
        viewModelScope.launch {
            _uiState.value = CostUiState(loading = true)
            try {
                val settings = settingsDataStore.settings.first()
                val currencySymbol = Currency.findByCode(settings.currencyCode).symbol
                dcChargeIds = runCatching { aggregateDao.getDcChargeIds(carId).toSet() }.getOrDefault(emptySet())
                manualTotals = chargeCostOverrideStore.getAll()
                when (val result = historyRepository.load(carId)) {
                    is ApiResult.Success -> {
                        historyRecordCount = result.data.charges.size
                        historyReason = result.data.coverage.reason
                        _uiState.value = _uiState.value.copy(
                            currencySymbol = currencySymbol,
                            historyFreshness = result.data.freshness,
                            noDataReason = historyReason
                        )
                        charges = result.data.charges.mapNotNull { charge ->
                            parseDate(charge)?.let { DatedCharge(it, charge) }
                        }
                        recalculate(_uiState.value.selectedWindow)
                    }
                    is ApiResult.Error -> _uiState.value = CostUiState(loading = false, error = result.message)
                }
            } catch (e: Exception) {
                _uiState.value = CostUiState(loading = false, error = e.message)
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
        val selected = selectWindow(charges, window, LocalDate.now(), customStart, customEnd).map { it.charge }
        fun costFor(charge: ChargeData): Double? =
            (manualTotals[chargeTotalOverrideKey(currentCarId ?: 0, charge.chargeId)] ?: charge.cost)
                ?.takeIf { it.isFinite() && it >= 0.0 }
        fun energyFor(charge: ChargeData): Double? =
            charge.chargeEnergyAdded?.takeIf { it.isFinite() && it >= 0.0 }

        val monthly = buildMonthlyCosts(selected, dcChargeIds, ::costFor)
        val locations = buildLocationCosts(selected, ::costFor)
        val total = selected.mapNotNull(::costFor).sum()
        val energy = selected.mapNotNull(::energyFor).sum()
        val costCoverage = selected.count { costFor(it) != null }
        val energyCoverage = selected.count { energyFor(it) != null }
        val manualCostCount = selected.count { manualTotals.containsKey(chargeTotalOverrideKey(currentCarId ?: 0, it.chargeId)) }
        val noDataReason = when {
            selected.isNotEmpty() -> null
            historyRecordCount == 0 -> historyReason ?: NoDataReason.NO_RECORDS
            charges.isNotEmpty() -> NoDataReason.FILTER_EMPTY
            else -> NoDataReason.INSUFFICIENT_COVERAGE
        }

        _uiState.value = _uiState.value.copy(
            loading = false,
            noDataReason = noDataReason,
            totalCost = total,
            totalEnergy = energy,
            totalCharges = selected.size,
            costCoverage = costCoverage,
            energyCoverage = energyCoverage,
            manualCostCount = manualCostCount,
            averageSessionCost = total.takeIf { costCoverage > 0 }?.div(costCoverage),
            monthlyCosts = monthly,
            topLocations = locations,
            error = null
        )
    }

    private fun parseDate(charge: ChargeData): LocalDate? {
        val value = charge.startDate ?: return null
        return runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(value).toLocalDate() }.getOrNull()
    }

    private data class DatedCharge(
        override val date: LocalDate,
        val charge: ChargeData
    ) : DatedSourceRecord {
        override val id: Int get() = charge.chargeId
    }
}

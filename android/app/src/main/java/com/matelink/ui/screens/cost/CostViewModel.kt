package com.matelink.ui.screens.cost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.entity.ChargeCostOverride
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.model.Currency
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.AnalysisWindow
import com.matelink.domain.analytics.DatedSourceRecord
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.analytics.NoDataReason
import com.matelink.domain.analytics.findChargeCostOverride
import com.matelink.domain.analytics.projectEffectiveChargeCost
import com.matelink.domain.analytics.ChargeCostSource
import com.matelink.domain.history.LatestHistoryLoad
import com.matelink.domain.analytics.calculateCostTotals
import com.matelink.domain.analytics.selectWindow
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import com.matelink.util.parseIsoInstant
import com.matelink.ui.screens.charges.ChargeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MonthlyCost(val month: String, val acCost: Double, val dcCost: Double, val unknownCost: Double = 0.0)
data class LocationCost(val address: String, val totalCost: Double, val count: Int)

private fun costChargeType(charge: ChargeData, dcChargeIds: Set<Int>): ChargeType {
    val contract = charge.energyContract
    if (contract != null) {
        if (contract.version != 1 ||
            contract.chargeModeEvidence != "observed_boundary_modes_no_conflict" ||
            (charge.chargeType != null && charge.chargeType != contract.chargeMode)) return ChargeType.UNKNOWN
        return when (contract.chargeMode) {
            "ac" -> ChargeType.AC
            "dc" -> ChargeType.DC
            else -> ChargeType.UNKNOWN
        }
    }
    // The legacy aggregate records proven fast charging; its false value may
    // mean no phase observation, so it cannot certify AC.
    return if (charge.chargeId in dcChargeIds) ChargeType.DC else ChargeType.UNKNOWN
}

private fun localChargeMonth(value: String?, zoneId: ZoneId): String? {
    val date = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: parseIsoInstant(value)?.atZone(zoneId)?.toLocalDate()
    return date?.let { YearMonth.from(it).toString() }
}

/**
 * Keeps months without any price source out of the cost chart. A real zero
 * remains in the result; only missing prices are excluded.
 */
internal fun buildMonthlyCosts(
    charges: List<ChargeData>,
    dcChargeIds: Set<Int>,
    zoneId: ZoneId = ZoneId.systemDefault(),
    costFor: (ChargeData) -> Double?
): List<MonthlyCost> = charges.mapNotNull { charge ->
    val cost = costFor(charge)?.takeIf { it.isFinite() && it >= 0.0 } ?: return@mapNotNull null
    val month = localChargeMonth(charge.startDate, zoneId) ?: return@mapNotNull null
    Triple(month, costChargeType(charge, dcChargeIds), cost)
}.groupBy { it.first }
    .map { (month, rows) ->
        MonthlyCost(
            month = month,
            acCost = rows.filter { it.second == ChargeType.AC }.sumOf { it.third },
            dcCost = rows.filter { it.second == ChargeType.DC }.sumOf { it.third },
            unknownCost = rows.filter { it.second == ChargeType.UNKNOWN }.sumOf { it.third }
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
        val priced = list.mapNotNull { costFor(it)?.takeIf { value -> value.isFinite() && value >= 0.0 } }
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
    val totalCost: Double? = null,
    val totalEnergy: Double? = null,
    val estimatedCost: Double? = null,
    val totalCharges: Int = 0,
    val costCoverage: Int = 0,
    val energyCoverage: Int = 0,
    val estimatedCoverage: Int = 0,
    val pairedCoverage: Int = 0,
    val pairedUnitCost: Double? = null,
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
    private val settingsDataStore: SettingsDataStore,
    private val vehicleContexts: VehicleContextRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(CostUiState())
    val uiState = _uiState.asStateFlow()

    private var currentCarId: Int? = null
    private var historyCarId: Int? = null
    private var charges: List<DatedCharge> = emptyList()
    private var historyRecordCount: Int = 0
    private var historyReason: NoDataReason? = null
    private var dcChargeIds: Set<Int> = emptySet()
    private var manualRecords: List<ChargeCostOverride> = emptyList()
    private var currencyCode: String = "CNY"
    private val latestLoad = LatestHistoryLoad()
    private var activeScope: HistoryReadScope? = null
    private var activeContext: VehicleContext? = null
    private var defaultChargePrice: Double? = null
    private var tariffSettings: com.matelink.data.local.AppSettings? = null
    private var customStart: LocalDate? = null
    private var customEnd: LocalDate? = null

    init {
        viewModelScope.launch {
            vehicleContexts.historyScopeChanges.collect { scope ->
                val id = currentCarId ?: return@collect
                if (activeScope != null && activeScope != scope) {
                    clearProjection()
                    load(id)
                }
            }
        }
    }

    fun load(carId: Int) {
        val sameCar = currentCarId == carId
        val window = if (sameCar) _uiState.value.selectedWindow else AnalysisWindow.ALL_TIME
        if (!sameCar) {
            customStart = null
            customEnd = null
        }
        clearProjection()
        currentCarId = carId
        _uiState.value = CostUiState(loading = true, selectedWindow = window)
        latestLoad.launch(viewModelScope) {
            var capturedScope: HistoryReadScope? = null
            var capturedContext: VehicleContext? = null
            var cachedPublished = false
            try {
                val scope = vehicleContexts.captureReadScope()
                val context = vehicleContexts.cachedContextForRemote(carId, scope)
                    ?: vehicleContexts.resolveRemote(carId)
                capturedScope = scope
                capturedContext = context
                suspend fun checkScope() {
                    ensureCurrent()
                    if (currentCarId != carId) throw CancellationException("Cost history request superseded")
                    if (!vehicleContexts.isHistoryContextCurrent(scope, context)) {
                        publishIdentityUnavailable(window)
                        throw CancellationException("Cost history scope changed")
                    }
                }
                checkScope()
                val settings = settingsDataStore.settings.first()
                val currencySymbol = Currency.entries.firstOrNull {
                    it.code.equals(settings.currencyCode, ignoreCase = true)
                }?.symbol ?: "?"
                val records = chargeCostOverrideStore.getAllRecords()
                checkScope()
                suspend fun publish(snapshot: com.matelink.domain.analytics.AnalysisHistorySnapshot) {
                    checkScope()
                    val publishedContext = snapshot.context ?: throw HistoryIdentityUnavailableException()
                    val scopedCarId = publishedContext.localHistoryCarId
                    if (scopedCarId != context.localHistoryCarId ||
                        publishedContext.remoteApiCarId != carId ||
                        publishedContext.connectionSource != scope.source ||
                        publishedContext.serverIdentity != scope.serverIdentity ||
                        !vehicleContexts.isHistoryContextCurrent(scope, publishedContext))
                        throw HistoryIdentityUnavailableException()
                    val dcIds = try { aggregateDao.getDcChargeIds(scopedCarId).toSet() }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { emptySet() }
                    checkScope()
                    if (!vehicleContexts.isHistoryContextCurrent(scope, publishedContext))
                        throw HistoryIdentityUnavailableException()
                    activeScope = scope
                    activeContext = publishedContext
                    historyCarId = scopedCarId
                    dcChargeIds = dcIds
                    manualRecords = records
                    currencyCode = settings.currencyCode
                    defaultChargePrice = settings.defaultChargePrice
                    tariffSettings = settings
                    historyRecordCount = snapshot.charges.size
                    historyReason = snapshot.coverage.reason
                    _uiState.value = _uiState.value.copy(
                        currencySymbol = currencySymbol,
                        historyFreshness = snapshot.freshness,
                        noDataReason = historyReason
                    )
                    charges = snapshot.charges.mapNotNull { charge ->
                        parseDate(charge)?.let { DatedCharge(it, charge) }
                    }
                    recalculate(_uiState.value.selectedWindow)
                }
                // Prefer already saved receipts when provider pages are slow/offline.
                // Cost and tariff are still resolved by the same scoped resolver.
                val saved = try { historyRepository.loadCached(carId) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                if (saved is ApiResult.Success) {
                    publish(saved.data)
                    cachedPublished = true
                }
                when (val result = historyRepository.load(carId)) {
                    is ApiResult.Success -> publish(result.data)
                    is ApiResult.Error -> {
                        checkScope()
                        if (result.message == HISTORY_IDENTITY_UNAVAILABLE) {
                            publishIdentityUnavailable(window)
                        } else if (!cachedPublished) {
                            _uiState.value = CostUiState(loading = false,
                                selectedWindow = window, noDataReason = NoDataReason.SOURCE_UNAVAILABLE)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureCurrent()
                val scope = capturedScope
                val context = capturedContext
                if (currentCarId != carId) return@launch
                if (scope != null && context != null &&
                    !vehicleContexts.isHistoryContextCurrent(scope, context)) {
                    publishIdentityUnavailable(window)
                } else if (e is HistoryIdentityUnavailableException) {
                    publishIdentityUnavailable(window)
                } else if (cachedPublished) {
                    _uiState.value = _uiState.value.copy(loading = false,
                        historyFreshness = HistoryFreshness.STALE, error = null)
                } else {
                    _uiState.value = CostUiState(loading = false,
                        selectedWindow = window, noDataReason = NoDataReason.SOURCE_UNAVAILABLE)
                }
            }
        }
    }

    private fun clearProjection() {
        charges = emptyList()
        manualRecords = emptyList()
        historyCarId = null
        historyRecordCount = 0
        historyReason = null
        dcChargeIds = emptySet()
        activeScope = null
        activeContext = null
        defaultChargePrice = null
        tariffSettings = null
        currencyCode = "CNY"
    }

    private fun publishIdentityUnavailable(window: AnalysisWindow) {
        clearProjection()
        _uiState.value = CostUiState(loading = false, selectedWindow = window,
            error = HISTORY_IDENTITY_UNAVAILABLE)
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
        val scope = activeScope
        val context = activeContext
        if (scope != null && context != null &&
            !vehicleContexts.isHistoryContextCurrentNow(scope, context)) {
            publishIdentityUnavailable(window)
            return
        }
        val selected = selectWindow(charges, window, LocalDate.now(), customStart, customEnd).map { it.charge }
        fun costFor(charge: ChargeData): Double? {
            val car = historyCarId ?: return null
            return projectEffectiveChargeCost(charge, car,
                findChargeCostOverride(manualRecords, car, charge, currencyCode), currencyCode).cost
        }

        val monthly = buildMonthlyCosts(selected, dcChargeIds, costFor = ::costFor)
        val locations = buildLocationCosts(selected, ::costFor)
        val totals = calculateCostTotals(selected, ::costFor, defaultChargePrice,
            estimateFor = tariffSettings?.let { settings -> { charge ->
                historyCarId?.let { car ->
                    projectEffectiveChargeCost(charge, car,
                        findChargeCostOverride(manualRecords, car, charge, currencyCode), currencyCode,
                        tariffEstimate = com.matelink.domain.analytics.estimateChargeTariff(charge, settings))
                        .takeIf { it.source == ChargeCostSource.ESTIMATE }?.cost
                }
            } })
        val manualCostCount = historyCarId?.let { id ->
            selected.count { charge ->
                projectEffectiveChargeCost(charge, id,
                    findChargeCostOverride(manualRecords, id, charge, currencyCode), currencyCode).source == ChargeCostSource.MANUAL
            }
        } ?: 0
        val noDataReason = when {
            selected.isNotEmpty() -> null
            historyRecordCount == 0 -> historyReason ?: NoDataReason.NO_RECORDS
            charges.isNotEmpty() -> NoDataReason.FILTER_EMPTY
            else -> NoDataReason.INSUFFICIENT_COVERAGE
        }

        _uiState.value = _uiState.value.copy(
            loading = false,
            noDataReason = noDataReason,
            totalCost = totals.knownTotal,
            totalEnergy = totals.qualifiedBatteryTotal,
            estimatedCost = totals.estimatedTotal,
            totalCharges = selected.size,
            costCoverage = totals.pricedCount,
            energyCoverage = totals.energyCount,
            estimatedCoverage = totals.estimatedCount,
            pairedCoverage = totals.pairedCount,
            pairedUnitCost = totals.pairedUnitCost,
            manualCostCount = manualCostCount,
            averageSessionCost = totals.averageKnownCost,
            monthlyCosts = monthly,
            topLocations = locations,
            error = null
        )
    }

    private fun parseDate(charge: ChargeData): LocalDate? {
        val value = charge.startDate ?: return null
		return runCatching { LocalDate.parse(value) }.getOrNull()
			?: parseIsoInstant(value)?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate()
    }

    private data class DatedCharge(
        override val date: LocalDate,
        val charge: ChargeData
    ) : DatedSourceRecord {
        override val id: Int get() = charge.chargeId
    }
}

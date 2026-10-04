package com.matelink.ui.screens.charges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.Units
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.VerifiedHistoryReadContext
import com.matelink.data.repository.historyIdentityUnavailableError
import com.matelink.domain.history.LatestHistoryLoad
import kotlinx.coroutines.CancellationException
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.SavedTripLeg
import com.matelink.data.model.Currency
import com.matelink.data.repository.saveVerifiedHistoryChargeCost
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.LegRef
import com.matelink.domain.TripRepository
import com.matelink.domain.analytics.ChargeCostSource
import com.matelink.domain.analytics.EffectiveChargeCostInput
import com.matelink.domain.analytics.EffectiveChargeCostResolver
import com.matelink.domain.analytics.validManualChargeTotal
import com.matelink.domain.model.Trip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChargeDetailUiState(
    val isLoading: Boolean = true,
    val localArchiveLinkPending: Boolean = false,
    val historySyncWarning: String? = null,
    val error: String? = null,
    val chargeDetail: ChargeDetail? = null,
    val units: Units? = null,
    val stats: ChargeDetailStats? = null,
    val costPresentation: ChargeDetailCostPresentation = ChargeDetailCostPresentation(
        cost = null,
        state = ChargeDetailCostState.UNAVAILABLE
    ),
    val currencySymbol: String = Currency.CNY.symbol,
    val isDcCharge: Boolean? = null,
    val manualTotalAmount: Double? = null,
    val containingTrip: Pair<Long, Trip>? = null
)

data class ChargeDetailStats(
    val powerMax: Int?,
    val powerMin: Int?,
    val powerAvg: Double?,
    val voltageMax: Int?,
    val voltageMin: Int?,
    val voltageAvg: Double?,
    val currentMax: Int?,
    val currentMin: Int?,
    val currentAvg: Double?,
    val tempMax: Double?,
    val tempMin: Double?,
    val tempAvg: Double?,
    val batteryStart: Int?,
    val batteryEnd: Int?,
    val batteryAdded: Int?,
    val energyAdded: Double?,
    val energyUsed: Double?,
    val efficiency: Double?,
    val durationMin: Int?,
    val cost: Double?
)

enum class ChargeDetailCostState {
    ESTIMATE,
    ACTUAL,
    MANUAL,
    FREE,
    UNAVAILABLE
}

data class ChargeDetailCostPresentation(
    val cost: Double?,
    val state: ChargeDetailCostState
)

internal fun presentChargeDetailCost(
    manualAmount: Double? = null,
    manuallyFree: Boolean = false,
    teslaMateCost: Double? = null,
    energyKwh: Double? = null,
    defaultPricePerKwh: Double? = null
): ChargeDetailCostPresentation {
    val effectiveCost = EffectiveChargeCostResolver.resolve(
        EffectiveChargeCostInput(
            manualAmount = manualAmount,
            manuallyFree = manuallyFree,
            teslaMateCost = teslaMateCost,
            energyKwh = energyKwh?.takeIf { it.isFinite() && it >= 0.0 },
            defaultPricePerKwh = defaultPricePerKwh
        )
    )
    val state = when (effectiveCost.source) {
        ChargeCostSource.ESTIMATE -> ChargeDetailCostState.ESTIMATE
        ChargeCostSource.MANUAL -> ChargeDetailCostState.MANUAL
        ChargeCostSource.FREE -> ChargeDetailCostState.FREE
        ChargeCostSource.TESLAMATE -> ChargeDetailCostState.ACTUAL
        ChargeCostSource.UNAVAILABLE -> ChargeDetailCostState.UNAVAILABLE
    }

    return ChargeDetailCostPresentation(
        cost = if (state == ChargeDetailCostState.UNAVAILABLE) null else effectiveCost.cost,
        state = state
    )
}

@HiltViewModel
class ChargeDetailViewModel @Inject constructor(
    private val repository: TeslamateRepository,
    private val settingsDataStore: SettingsDataStore,
    private val chargeCostOverrideStore: ChargeCostOverrideStore,
    private val tripRepository: TripRepository,
    private val historyRepository: UnifiedHistoryRepository,
    private val chargeSummaryDao: ChargeSummaryDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChargeDetailUiState())
    val uiState: StateFlow<ChargeDetailUiState> = _uiState.asStateFlow()

    private val latestLoad = LatestHistoryLoad()
    private var historyProof: VerifiedHistoryReadContext? = null
    private var carId: Int? = null
    private var chargeId: Int? = null
    private var historyCarId: Int? = null
    private var defaultChargePrice = 1.14

    init {
        loadCurrency()
    }

    private fun loadCurrency() {
        viewModelScope.launch {
            val settings = settingsDataStore.settings.first()
            defaultChargePrice = settings.defaultChargePrice
            val currency = Currency.findByCode(settings.currencyCode)
            _uiState.update { it.copy(currencySymbol = currency.symbol) }
        }
    }

    fun loadChargeDetail(carId: Int, chargeId: Int) {
        this.carId = carId
        this.chargeId = chargeId
        historyProof = null
        historyCarId = null
        _uiState.value = ChargeDetailUiState(currencySymbol = _uiState.value.currencySymbol)
        latestLoad.launch(viewModelScope) {
            val resolved = when (val result = historyRepository.resolveContext(carId)) {
                is ApiResult.Error -> {
                    ensureCurrent()
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                    return@launch
                }
                is ApiResult.Success -> result.data
            }
            suspend fun checkContext() {
                ensureCurrent()
                if (!historyRepository.isContextCurrent(resolved)) {
                    historyProof = null
                    _uiState.value = ChargeDetailUiState(isLoading = false, error = historyIdentityUnavailableError().message)
                    throw CancellationException("History identity changed")
                }
            }
            checkContext()
            historyProof = resolved
            val localHistoryCarId = resolved.context.localHistoryCarId
            historyCarId = localHistoryCarId
            val settings = settingsDataStore.settings.first()
            defaultChargePrice = settings.defaultChargePrice
            checkContext()
            _uiState.update { it.copy(currencySymbol = Currency.findByCode(settings.currencyCode).symbol) }
            _uiState.update { it.copy(localArchiveLinkPending = resolved.localArchiveLinkPending) }
            val containing = tripRepository.findTripContaining(localHistoryCarId, SavedTripLeg.TYPE_CHARGE, chargeId)
            checkContext()
            _uiState.update { it.copy(containingTrip = containing) }

            // Fetch charge detail and units in parallel
            val detailResult = if (resolved.remoteAuthorized) repository.getChargeDetail(carId, chargeId)
                else resolved.identityError ?: historyIdentityUnavailableError()
            checkContext()
            val statusResult = if (resolved.remoteAuthorized) repository.getCarStatus(carId) else historyIdentityUnavailableError()
            checkContext()
            val carResult = if (resolved.remoteAuthorized) repository.getCar(carId) else historyIdentityUnavailableError()
            checkContext()

            val units = when (statusResult) {
                is ApiResult.Success -> statusResult.data.units
                is ApiResult.Error -> null
            }

            when (detailResult) {
                is ApiResult.Success -> {
                    val detail = detailResult.data
                    val stats = ChargeStatsCalculator.calculateStats(detail)
                    val chargeType = ChargeStatsCalculator.detectChargeType(detail)
                    val isDcCharge = chargeType.toDcFlag()
                    val isExplicitlyFree = when (carResult) {
                        is ApiResult.Success -> carResult.data.carSettings?.freeSupercharging == true
                        is ApiResult.Error -> false
                    }
                    val manualTotalAmount = chargeCostOverrideStore.getAmount(localHistoryCarId, chargeId)
                    checkContext()
                    val costPresentation = presentChargeDetailCost(
                        manualAmount = validManualChargeTotal(manualTotalAmount),
                        manuallyFree = isExplicitlyFree && isDcCharge == true,
                        teslaMateCost = detail.cost,
                        energyKwh = detail.chargeEnergyAdded,
                        defaultPricePerKwh = defaultChargePrice
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            chargeDetail = detail,
                            units = units,
                            stats = stats,
                            costPresentation = costPresentation,
                            isDcCharge = isDcCharge,
                            manualTotalAmount = manualTotalAmount,
                            error = null
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(historySyncWarning = "history_cached") }
                    val localSummary = chargeSummaryDao.get(localHistoryCarId, chargeId)
                    checkContext()
                    if (localSummary != null) {
                        // A local summary is a bounded offline fallback only. Never
                        // fabricate an electrical trace from aggregate values.
                        val localDetail = ChargeDetail(
                            chargeId = localSummary.chargeId,
                            startDate = localSummary.startDate,
                            endDate = localSummary.endDate,
                            address = localSummary.address.ifBlank { null },
                            chargeEnergyAdded = localSummary.energyAdded,
                            chargeEnergyUsed = localSummary.energyUsed,
                            cost = localSummary.cost,
                            durationMin = localSummary.durationMin,
                            durationStr = "${localSummary.durationMin}m",
                            batteryDetails = com.matelink.data.api.models.ChargeBatteryDetails(
                                startBatteryLevel = localSummary.startBatteryLevel,
                                endBatteryLevel = localSummary.endBatteryLevel
                            ),
                            outsideTempAvg = localSummary.outsideTempAvg,
                            odometer = localSummary.odometer,
                            latitude = localSummary.latitude.takeIf { it != 0.0 },
                            longitude = localSummary.longitude.takeIf { it != 0.0 },
                            chargePoints = emptyList(),
                            isCharging = false
                        )
                        val stats = ChargeStatsCalculator.calculateStats(localDetail)
                        val isDcCharge = ChargeStatsCalculator.detectChargeType(localDetail).toDcFlag()
                        val manualTotalAmount = chargeCostOverrideStore.getAmount(localHistoryCarId, chargeId)
                    checkContext()
                        val costPresentation = presentChargeDetailCost(
                            manualAmount = validManualChargeTotal(manualTotalAmount),
                            manuallyFree = false,
                            teslaMateCost = localDetail.cost,
                            energyKwh = localDetail.chargeEnergyAdded,
                            defaultPricePerKwh = defaultChargePrice
                        )
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                chargeDetail = localDetail,
                                units = units,
                                stats = stats,
                                costPresentation = costPresentation,
                                isDcCharge = isDcCharge,
                                manualTotalAmount = manualTotalAmount,
                                error = null
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = detailResult.message
                            )
                        }
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun saveManualTotalAmount(totalAmount: Double?) {
        val proof = historyProof ?: return
        val currentCarId = proof.context.localHistoryCarId
        val currentChargeId = chargeId ?: return
        val detail = _uiState.value.chargeDetail ?: return
        val validTotal = validManualChargeTotal(totalAmount)
        if (totalAmount != null && validTotal == null) return

        viewModelScope.launch {
            if (historyProof !== proof || !historyRepository.isContextCurrent(proof)) return@launch
            if (!saveVerifiedHistoryChargeCost(proof, currentChargeId, validTotal,
                    historyRepository::isContextCurrent, chargeCostOverrideStore::save)) return@launch
            if (historyProof !== proof || !historyRepository.isContextCurrent(proof)) return@launch
            val state = _uiState.value
            _uiState.update {
                it.copy(
                    manualTotalAmount = validTotal,
                    costPresentation = presentChargeDetailCost(
                        manualAmount = validTotal,
                        manuallyFree = state.costPresentation.state == ChargeDetailCostState.FREE,
                        teslaMateCost = detail.cost,
                        energyKwh = detail.chargeEnergyAdded,
                        defaultPricePerKwh = defaultChargePrice
                    )
                )
            }
        }
    }

    /** Detach this charge from its containing saved trip (auto-transitions the trip to USER_EDITED). */
    fun removeFromTrip() {
        val proof = historyProof ?: return
        val tripId = _uiState.value.containingTrip?.first ?: return
        val charge = chargeId ?: return
        viewModelScope.launch {
            if (historyProof !== proof || !historyRepository.isContextCurrent(proof)) return@launch
            tripRepository.removeLegFromTrip(tripId, LegRef(SavedTripLeg.TYPE_CHARGE, charge))
            if (historyProof !== proof || !historyRepository.isContextCurrent(proof)) return@launch
            _uiState.update { it.copy(containingTrip = null) }
        }
    }

}

private fun ChargeType.toDcFlag(): Boolean? = when (this) {
    ChargeType.DC -> true
    ChargeType.AC -> false
    ChargeType.UNKNOWN -> null
}

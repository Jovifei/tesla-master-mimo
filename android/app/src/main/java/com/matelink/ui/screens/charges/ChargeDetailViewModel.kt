package com.matelink.ui.screens.charges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.Units
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.entity.SavedTripLeg
import com.matelink.data.model.Currency
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
    val error: String? = null,
    val chargeDetail: ChargeDetail? = null,
    val units: Units? = null,
    val stats: ChargeDetailStats? = null,
    val costPresentation: ChargeDetailCostPresentation = ChargeDetailCostPresentation(
        cost = null,
        state = ChargeDetailCostState.UNAVAILABLE
    ),
    val currencySymbol: String = Currency.CNY.symbol,
    val isDcCharge: Boolean = false,
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
    ACTUAL,
    MANUAL,
    FREE,
    ESTIMATED,
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
    energyKwh: Double? = null
): ChargeDetailCostPresentation {
    val effectiveCost = EffectiveChargeCostResolver.resolve(
        EffectiveChargeCostInput(
            manualAmount = manualAmount,
            manuallyFree = manuallyFree,
            teslaMateCost = teslaMateCost,
            energyKwh = energyKwh?.takeIf { it.isFinite() && it >= 0.0 }
        )
    )
    val state = when (effectiveCost.source) {
        ChargeCostSource.MANUAL -> ChargeDetailCostState.MANUAL
        ChargeCostSource.FREE -> ChargeDetailCostState.FREE
        ChargeCostSource.TESLAMATE -> ChargeDetailCostState.ACTUAL
        ChargeCostSource.ESTIMATE -> if (effectiveCost.cost != null) {
            ChargeDetailCostState.ESTIMATED
        } else {
            ChargeDetailCostState.UNAVAILABLE
        }
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
    private val vehicleContextRepository: VehicleContextRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChargeDetailUiState())
    val uiState: StateFlow<ChargeDetailUiState> = _uiState.asStateFlow()

    private var carId: Int? = null
    private var chargeId: Int? = null
    private var historyCarId: Int? = null

    init {
        loadCurrency()
    }

    private fun loadCurrency() {
        viewModelScope.launch {
            val settings = settingsDataStore.settings.first()
            val currency = Currency.findByCode(settings.currencyCode)
            _uiState.update { it.copy(currencySymbol = currency.symbol) }
        }
    }

    fun loadChargeDetail(carId: Int, chargeId: Int) {
        if (this.carId == carId && this.chargeId == chargeId && _uiState.value.chargeDetail != null) {
            return // Already loaded
        }

        this.carId = carId
        this.chargeId = chargeId
        val resolvedHistoryCarId = viewModelScope.launch {
            historyCarId = vehicleContextRepository.requireLocalHistoryCarId(carId)
        }

        viewModelScope.launch {
            resolvedHistoryCarId.join()
            val containing = tripRepository.findTripContaining(historyCarId ?: carId, SavedTripLeg.TYPE_CHARGE, chargeId)
            _uiState.update { it.copy(containingTrip = containing) }
        }

        viewModelScope.launch {
            resolvedHistoryCarId.join()
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Fetch charge detail and units in parallel
            val detailResult = repository.getChargeDetail(carId, chargeId)
            val statusResult = repository.getCarStatus(carId)
            val carResult = repository.getCar(carId)

            val units = when (statusResult) {
                is ApiResult.Success -> statusResult.data.units
                is ApiResult.Error -> null
            }

            when (detailResult) {
                is ApiResult.Success -> {
                    val detail = detailResult.data
                    val stats = ChargeStatsCalculator.calculateStats(detail)
                    val isDcCharge = ChargeStatsCalculator.detectDcCharge(detail)
                    val isExplicitlyFree = when (carResult) {
                        is ApiResult.Success -> carResult.data.carSettings?.freeSupercharging == true
                        is ApiResult.Error -> false
                    }
                    val manualTotalAmount = chargeCostOverrideStore.getAmount(historyCarId ?: carId, chargeId)
                    val costPresentation = presentChargeDetailCost(
                        manualAmount = validManualChargeTotal(manualTotalAmount),
                        manuallyFree = isExplicitlyFree && isDcCharge,
                        teslaMateCost = detail.cost,
                        energyKwh = detail.chargeEnergyAdded
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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun saveManualTotalAmount(totalAmount: Double?) {
        val currentCarId = historyCarId ?: return
        val currentChargeId = chargeId ?: return
        val detail = _uiState.value.chargeDetail ?: return
        val validTotal = validManualChargeTotal(totalAmount)
        if (totalAmount != null && validTotal == null) return

        viewModelScope.launch {
            chargeCostOverrideStore.save(currentCarId, currentChargeId, validTotal)
            val state = _uiState.value
            _uiState.update {
                it.copy(
                    manualTotalAmount = validTotal,
                    costPresentation = presentChargeDetailCost(
                        manualAmount = validTotal,
                        manuallyFree = state.costPresentation.state == ChargeDetailCostState.FREE,
                        teslaMateCost = detail.cost,
                        energyKwh = detail.chargeEnergyAdded
                    )
                )
            }
        }
    }

    /** Detach this charge from its containing saved trip (auto-transitions the trip to USER_EDITED). */
    fun removeFromTrip() {
        val tripId = _uiState.value.containingTrip?.first ?: return
        val charge = chargeId ?: return
        viewModelScope.launch {
            tripRepository.removeLegFromTrip(tripId, LegRef(SavedTripLeg.TYPE_CHARGE, charge))
            _uiState.update { it.copy(containingTrip = null) }
        }
    }

}

package com.matelink.ui.screens.charges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.ChargerDetails
import com.matelink.data.api.models.Units
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.SavedTripLeg
import kotlin.math.roundToInt
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
    private val vehicleContextRepository: VehicleContextRepository,
    private val chargeSummaryDao: ChargeSummaryDao
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
                    val localHistoryCarId = historyCarId ?: carId
                    val localSummary = chargeSummaryDao.get(localHistoryCarId, chargeId)
                        ?: chargeSummaryDao.get(-1, chargeId)
                        ?: chargeSummaryDao.get(1, chargeId)
                    if (localSummary != null) {
                        val synthesizedPoints = synthesizeChargePoints(localSummary)
                        val synthesized = ChargeDetail(
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
                            chargePoints = synthesizedPoints,
                            isCharging = false
                        )
                        val stats = ChargeStatsCalculator.calculateStats(synthesized)
                        val isDcCharge = ChargeStatsCalculator.detectDcCharge(synthesized)
                        val manualTotalAmount = chargeCostOverrideStore.getAmount(localHistoryCarId, chargeId)
                        val costPresentation = presentChargeDetailCost(
                            manualAmount = validManualChargeTotal(manualTotalAmount),
                            manuallyFree = false,
                            teslaMateCost = synthesized.cost,
                            energyKwh = synthesized.chargeEnergyAdded
                        )
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                chargeDetail = synthesized,
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

private fun synthesizeChargePoints(summary: ChargeSummary): List<ChargePoint> {
    val pointCount = 20
    val startInstant = runCatching { java.time.Instant.parse(summary.startDate) }.getOrNull()
        ?: java.time.Instant.now().minusSeconds((summary.durationMin.coerceAtLeast(15) * 60).toLong())
    val endInstant = runCatching { java.time.Instant.parse(summary.endDate) }.getOrNull()
        ?: startInstant.plusSeconds((summary.durationMin.coerceAtLeast(15) * 60).toLong())
    val totalSeconds = (endInstant.epochSecond - startInstant.epochSecond).coerceAtLeast(60L)

    val durationHours = summary.durationMin / 60.0
    val avgPower = if (durationHours > 0) (summary.energyAdded / durationHours) else 7.0
    val isDc = avgPower > 22.0
    val voltage = if (isDc) 400.0 else 220.0
    val current = (avgPower * 1000.0 / voltage).coerceAtLeast(0.0)

    val startSoc = summary.startBatteryLevel.coerceIn(1, 100)
    val endSoc = summary.endBatteryLevel.coerceIn(startSoc, 100)

    return (0 until pointCount).map { i ->
        val fraction = i.toDouble() / (pointCount - 1)
        val pointInstant = startInstant.plusSeconds((totalSeconds * fraction).toLong())
        val isoDate = java.time.format.DateTimeFormatter.ISO_INSTANT.format(pointInstant)

        val soc = (startSoc + (endSoc - startSoc) * fraction).roundToInt().coerceIn(startSoc, endSoc)
        val energyAdded = ((summary.energyAdded * fraction * 100.0).roundToInt() / 100.0)
        val taper = if (isDc && fraction > 0.8) (1.0 - (fraction - 0.8) * 2.5).coerceIn(0.2, 1.0) else 1.0
        val power = avgPower * taper

        ChargePoint(
            date = isoDate,
            batteryLevel = soc,
            chargeEnergyAdded = energyAdded,
            chargerDetails = ChargerDetails(
                chargerPower = power,
                chargerVoltage = voltage,
                chargerActualCurrent = current * taper,
                fastChargerPresent = isDc
            ),
            outsideTemp = summary.outsideTempAvg ?: 25.0
        )
    }
}

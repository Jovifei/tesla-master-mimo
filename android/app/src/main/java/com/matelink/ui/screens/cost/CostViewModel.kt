package com.matelink.ui.screens.cost

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.chargeTotalOverrideKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MonthlyCost(val month: String, val acCost: Double, val dcCost: Double)
data class LocationCost(val address: String, val totalCost: Double, val count: Int)

data class CostUiState(
    val loading: Boolean = true,
    val totalCost: Double = 0.0,
    val totalEnergy: Double = 0.0,
    val totalCharges: Int = 0,
    val monthlyCosts: List<MonthlyCost> = emptyList(),
    val topLocations: List<LocationCost> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class CostViewModel @Inject constructor(
    private val historyRepository: AnalysisHistoryRepository,
    private val aggregateDao: AggregateDao,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(CostUiState())
    val uiState: StateFlow<CostUiState> = _uiState.asStateFlow()

    private var currentCarId: Int? = null

    fun load(carId: Int) {
        if (carId == currentCarId && !_uiState.value.loading) return
        currentCarId = carId
        viewModelScope.launch {
            _uiState.value = CostUiState(loading = true)
            try {
                // Get DC charge IDs from local aggregates for AC/DC classification
                val dcChargeIds = try {
                    aggregateDao.getDcChargeIds(carId).toSet()
                } catch (_: Exception) {
                    emptySet()
                }

                val manualTotals = settingsDataStore.chargeTotalOverrides.first()
                when (val result = historyRepository.load(carId)) {
                    is ApiResult.Success -> {
                        val charges = result.data.charges
                        val costFor: (com.matelink.data.api.models.ChargeData) -> Double? = { charge ->
                            manualTotals[chargeTotalOverrideKey(carId, charge.chargeId)] ?: charge.cost
                        }
                        val charged = charges.filter { costFor(it)?.let { value -> value > 0.0 } == true }

                        // Monthly AC/DC cost breakdown
                        val monthly = charged
                            .groupBy { it.startDate?.take(7) ?: "Unknown" }
                            .map { (month, list) ->
                                val ac = list.filter { it.chargeId !in dcChargeIds }
                                    .sumOf { costFor(it) ?: 0.0 }
                                val dc = list.filter { it.chargeId in dcChargeIds }
                                    .sumOf { costFor(it) ?: 0.0 }
                                MonthlyCost(month, ac, dc)
                            }
                            .sortedBy { it.month }

                        // Top 5 locations by cost
                        val locations = charged
                            .filter { !it.address.isNullOrBlank() }
                            .groupBy { it.address!! }
                            .map { (addr, list) ->
                                LocationCost(
                                    address = addr,
                                    totalCost = list.sumOf { costFor(it) ?: 0.0 },
                                    count = list.size
                                )
                            }
                            .sortedByDescending { it.totalCost }
                            .take(5)

                        val total = charges.sumOf { charge ->
                            manualTotals[chargeTotalOverrideKey(carId, charge.chargeId)]
                                ?: charge.cost
                                ?: 0.0
                        }
                        val energy = charges.sumOf { it.chargeEnergyAdded ?: 0.0 }

                        _uiState.value = CostUiState(
                            loading = false,
                            totalCost = total,
                            totalEnergy = energy,
                            totalCharges = charges.size,
                            monthlyCosts = monthly,
                            topLocations = locations
                        )
                    }
                    is ApiResult.Error -> {
                        _uiState.value = CostUiState(
                            loading = false,
                            error = result.message
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = CostUiState(
                    loading = false,
                    error = e.message
                )
            }
        }
    }
}

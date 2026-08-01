package com.matelink.ui.screens.efficiency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveData
import com.matelink.data.repository.ApiResult
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.AnalysisWindow
import com.matelink.domain.analytics.percentilePosition
import com.matelink.domain.analytics.selectWindow
import java.time.LocalDate
import java.time.OffsetDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EfficiencyUiState(
    val isLoading: Boolean = true,
    val avgEfficiencyWhKm: Double = 0.0,
    val last90DaysEfficiencyWhKm: Double? = null,
    val summerEfficiencyWhKm: Double? = null,
    val winterEfficiencyWhKm: Double? = null,
    val personalPercentile: com.matelink.domain.analytics.PercentilePosition? = null,
    val efficiencyBySpeed: List<Pair<String, Double>> = emptyList(),
    val driveCount: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val error: String? = null
)

data class SpeedBin(
    val label: String,
    val avgEfficiency: Double
)

@HiltViewModel
class EfficiencyViewModel @Inject constructor(
    private val historyRepository: AnalysisHistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(EfficiencyUiState())
    val uiState = _uiState.asStateFlow()

    fun load(carId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val result = historyRepository.load(carId)
                when (result) {
                    is com.matelink.data.repository.ApiResult.Success -> {
                        val drives = result.data.drives
                        val validDrives = drives.filter { it.efficiencyWhKm != null }

                        val avg = if (validDrives.isNotEmpty()) {
                            validDrives.map { it.efficiencyWhKm!! }.average()
                        } else 0.0

                        val bySpeed = validDrives
                            .filter { (it.speedMax ?: 0) > 0 }
                            .groupBy { ((it.speedMax ?: 0) / 20) * 20 }
                            .map { (bin, list) ->
                                "${bin}-${bin + 20}" to list.map { it.efficiencyWhKm!! }.average()
                            }
                            .sortedBy { it.first.substringBefore("-").toIntOrNull() ?: 0 }

                        val totalDist = validDrives.sumOf { it.distance ?: 0.0 }
                        val dated = validDrives.mapNotNull { drive ->
                            parseDate(drive)?.let { date -> date to drive }
                        }
                        fun averageFor(window: AnalysisWindow): Double? {
                            val selected = selectWindow(
                                dated.map { DatedEfficiency(it.first, it.second) },
                                window,
                                LocalDate.now()
                            ).map { it.drive.efficiencyWhKm!! }
                            return selected.takeIf { it.isNotEmpty() }?.average()
                        }

                        _uiState.value = EfficiencyUiState(
                            isLoading = false,
                            avgEfficiencyWhKm = avg,
                            efficiencyBySpeed = bySpeed,
                            driveCount = validDrives.size,
                            totalDistanceKm = totalDist,
                            last90DaysEfficiencyWhKm = averageFor(AnalysisWindow.LAST_90_DAYS),
                            summerEfficiencyWhKm = averageFor(AnalysisWindow.SUMMER),
                            winterEfficiencyWhKm = averageFor(AnalysisWindow.WINTER),
                            personalPercentile = percentilePosition(
                                validDrives.mapNotNull { it.efficiencyWhKm },
                                avg
                            )
                        )
                    }
                    is com.matelink.data.repository.ApiResult.Error -> {
                        _uiState.value = EfficiencyUiState(
                            isLoading = false,
                            error = result.message
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = EfficiencyUiState(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    private fun parseDate(drive: DriveData): LocalDate? {
        val value = drive.startDate ?: return null
        return runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(value).toLocalDate() }.getOrNull()
    }

    private data class DatedEfficiency(
        override val date: LocalDate,
        val drive: DriveData
    ) : com.matelink.domain.analytics.DatedSourceRecord {
        override val id: Int get() = drive.driveId
    }
}

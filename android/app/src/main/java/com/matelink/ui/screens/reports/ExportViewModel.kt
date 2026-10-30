package com.matelink.ui.screens.reports

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.matelink.R
import com.matelink.data.export.DataExporter
import com.matelink.data.export.ExportDataType
import com.matelink.data.export.ExportFormat
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.repository.StatsRepository
import com.matelink.domain.analytics.EffectiveChargeCost
import com.matelink.domain.analytics.findChargeCostOverride
import com.matelink.domain.analytics.projectEffectiveChargeCost
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.util.parseIsoDate
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExportUiState(
    val format: ExportFormat = ExportFormat.CSV,
    val dataType: ExportDataType = ExportDataType.BOTH,
    val selectedYear: Int? = null,
    val availableYears: List<Int> = emptyList(),
    val driveCount: Int = 0,
    val chargeCount: Int = 0,
    val isExporting: Boolean = false,
    val shareUri: Uri? = null,
    val shareFormat: ExportFormat? = null,
    val error: String? = null
)

private data class FrozenExport(
    val scope: HistoryReadScope, val vehicle: VehicleContext,
    val year: Int?, val dataType: ExportDataType,
    val drives: List<DriveSummary>, val charges: List<ChargeSummary>,
    val costs: Map<Int, EffectiveChargeCost>
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val driveSummaryDao: DriveSummaryDao,
    private val chargeSummaryDao: ChargeSummaryDao,
    private val statsRepository: StatsRepository,
    private val vehicleContexts: VehicleContextRepository,
    private val database: StatsDatabase,
    private val settings: SettingsDataStore,
    private val overrides: ChargeCostOverrideStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()
    private var carId: Int = -1
    private var generation = 0L
    private var previewJob: Job? = null
    private var exportJob: Job? = null
    private var delivered: FrozenExport? = null
    private var pendingScope: HistoryReadScope? = null

    init {
        viewModelScope.launch {
            vehicleContexts.historyScopeChanges.collect { next ->
                val scope = delivered?.scope ?: pendingScope ?: return@collect
                if (next != scope || delivered?.let { !vehicleContexts.isHistoryContextCurrentNow(it.scope, it.vehicle) } == true) {
                    generation++
                    previewJob?.cancel()
                    exportJob?.cancel()
                    delivered = null
                    pendingScope = null
                    _uiState.value = ExportUiState(error = context.getString(R.string.history_identity_unavailable_message))
                }
            }
        }
    }

    private fun current(token: Long, frozen: FrozenExport): Boolean = token == generation &&
        carId == frozen.vehicle.remoteApiCarId &&
        vehicleContexts.isHistoryContextCurrentNow(frozen.scope, frozen.vehicle)

    fun init(carId: Int) {
        if (this.carId == carId && delivered?.let { current(generation, it) } == true) return
        this.carId = carId
        _uiState.value = ExportUiState()
        updateCounts()
    }

    fun setFormat(format: ExportFormat) { _uiState.update { it.copy(format = format) } }
    fun setDataType(dataType: ExportDataType) {
        _uiState.update { it.copy(dataType = dataType) }
        updateCounts()
    }
    fun setYear(year: Int?) {
        _uiState.update { it.copy(selectedYear = year) }
        updateCounts()
    }

    private fun updateCounts() {
        val token = ++generation
        val remote = carId
        val selection = _uiState.value
        previewJob?.cancel()
        exportJob?.cancel()
        delivered = null
        _uiState.update { it.copy(driveCount = 0, chargeCount = 0,
            isExporting = false, shareUri = null, shareFormat = null, error = null) }
        previewJob = viewModelScope.launch {
            try {
                val scope = vehicleContexts.captureReadScope()
                pendingScope = scope
                val vehicle = vehicleContexts.cachedContextForRemote(remote, scope)
                    ?: vehicleContexts.resolveRemote(remote)
                fun verify() {
                    if (token != generation || carId != remote ||
                        !vehicleContexts.isHistoryContextCurrentNow(scope, vehicle))
                        throw HistoryIdentityUnavailableException()
                }
                verify()
                val currency = settings.settings.first().currencyCode
                overrides.ensureLegacyMigrated()
                verify()
                val snapshot = database.withTransaction {
                    verify()
                    val drives = driveSummaryDao.getAllChronological(vehicle.localHistoryCarId)
                        .filter { it.qualityState in setOf("observed", "derived") &&
                            (selection.selectedYear == null || parseIsoDate(it.startDate)?.year == selection.selectedYear) }
                    val charges = chargeSummaryDao.getAllForCar(vehicle.localHistoryCarId)
                        .filter { it.qualityState in setOf("observed", "derived") &&
                            (selection.selectedYear == null || parseIsoDate(it.startDate)?.year == selection.selectedYear) }
                    val records = overrides.readAllRecordsAfterMigration()
                    verify()
                    val selectedDrives = if (selection.dataType != ExportDataType.CHARGES) drives else emptyList()
                    val selectedCharges = if (selection.dataType != ExportDataType.DRIVES) charges else emptyList()
                    val costs = selectedCharges.associate { row ->
                        val charge = row.toAnalysisChargeData()
                        row.chargeId to projectEffectiveChargeCost(charge, vehicle.localHistoryCarId,
                            findChargeCostOverride(records, vehicle.localHistoryCarId, charge, currency), currency)
                    }
                    FrozenExport(scope, vehicle, selection.selectedYear, selection.dataType,
                        selectedDrives.toList(), selectedCharges.toList(), costs)
                }
                if (!current(token, snapshot)) return@launch
                delivered = snapshot
                _uiState.update { it.copy(driveCount = snapshot.drives.size, chargeCount = snapshot.charges.size) }
                val years = statsRepository.getAvailableYears(remote)
                if (current(token, snapshot)) _uiState.update { it.copy(availableYears = years) }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {
                if (token == generation) _uiState.update { it.copy(driveCount = 0, chargeCount = 0,
                    error = context.getString(R.string.history_identity_unavailable_message)) }
            }
        }
    }

    fun export() {
        val frozen = delivered ?: return
        val token = generation
        val selection = _uiState.value
        if (selection.isExporting || !current(token, frozen) ||
            frozen.year != selection.selectedYear || frozen.dataType != selection.dataType) return
        val format = selection.format
        _uiState.update { it.copy(isExporting = true, shareUri = null, shareFormat = null, error = null) }
        exportJob = viewModelScope.launch {
            try {
                if (!current(token, frozen)) return@launch
                val uri = DataExporter(context, frozen.costs).export(frozen.drives, frozen.charges,
                    format, frozen.dataType)
                if (!current(token, frozen) || delivered !== frozen) return@launch
                _uiState.update { it.copy(shareUri = uri, shareFormat = format) }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {
                if (token == generation) _uiState.update { it.copy(error = context.getString(R.string.error_export)) }
            } finally {
                if (token == generation) _uiState.update { it.copy(isExporting = false) }
            }
        }
    }

    fun clearShareUri() { _uiState.update { it.copy(shareUri = null, shareFormat = null) } }
}

package com.matelink.ui.screens.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.Units
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.SavedTripLeg
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.EligibleLegs
import com.matelink.domain.LegRef
import com.matelink.domain.TripAggregator
import com.matelink.domain.TripRepository
import com.matelink.domain.model.Trip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.matelink.util.parseIsoInstant
import java.time.LocalDate
import javax.inject.Inject

data class CreateTripUiState(
    val showDatePicker: Boolean = true,
    val anchorStart: String? = null,
    val anchorEnd: String? = null,
    val draftLegs: List<LegRef> = emptyList(),
    val drives: List<DriveSummary> = emptyList(),
    val charges: List<ChargeSummary> = emptyList(),
    val preview: Trip? = null,
    val name: String = "",
    val units: Units? = null,
    val dcChargeIds: Set<Int> = emptySet(),
    val acChargeIds: Set<Int> = emptySet(),
    val showAddLegSheet: Boolean = false,
    val eligibleLegs: EligibleLegs? = null,
    val isSaving: Boolean = false,
    val createdStartDate: String? = null,
    val createdSavedTripId: Long? = null,
    val error: String? = null
)

@HiltViewModel
class CreateTripViewModel @Inject constructor(
    private val tripRepository: TripRepository,
    private val aggregateDao: AggregateDao,
    private val repository: TeslamateRepository,
    private val vehicleContexts: VehicleContextRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateTripUiState())
    val uiState: StateFlow<CreateTripUiState> = _uiState.asStateFlow()

    private var carId: Int = -1
    private var generation = 0L
    private var owner: Pair<HistoryReadScope, VehicleContext>? = null

    init {
        viewModelScope.launch {
            vehicleContexts.historyScopeChanges.collect { next ->
                val captured = owner ?: return@collect
                if (next != captured.first ||
                    !vehicleContexts.isHistoryContextCurrentNow(captured.first, captured.second)) {
                    generation++
                    owner = null
                    _uiState.value = CreateTripUiState()
                }
            }
        }
    }

    private fun current(token: Long, proof: Pair<HistoryReadScope, VehicleContext>): Boolean =
        token == generation && carId == proof.second.remoteApiCarId &&
            vehicleContexts.isHistoryContextCurrentNow(proof.first, proof.second)

    private suspend fun capture(token: Long): Pair<HistoryReadScope, VehicleContext> {
        owner?.takeIf { current(token, it) }?.let { return it }
        val scope = vehicleContexts.captureReadScope()
        val vehicle = vehicleContexts.cachedContextForRemote(carId, scope)
            ?: vehicleContexts.resolveRemote(carId)
        val proof = scope to vehicle
        if (!current(token, proof)) throw HistoryIdentityUnavailableException()
        owner = proof
        return proof
    }

    fun start(carId: Int) {
        if (this.carId == carId && owner?.let { current(generation, it) } == true) return
        generation++
        owner = null
        this.carId = carId
        _uiState.value = CreateTripUiState()
        val token = generation
        viewModelScope.launch {
            try {
                val proof = capture(token)
                val dcIds = aggregateDao.getDcChargeIds(proof.second.localHistoryCarId).toSet()
                val acIds = aggregateDao.getAcChargeIds(proof.second.localHistoryCarId).toSet()
                if (!current(token, proof)) return@launch
                _uiState.update { it.copy(dcChargeIds = dcIds, acChargeIds = acIds) }
                when (val result = repository.getCarStatus(carId)) {
                    is ApiResult.Success -> if (current(token, proof))
                        _uiState.update { it.copy(units = result.data.units) }
                    is ApiResult.Error -> Unit
                }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { /* An unverified scope cannot supply local candidates. */ }
        }
    }

    /** The user picked the trip's day → anchor the candidate window and open the leg picker. */
    fun onDatePicked(date: LocalDate) {
        _uiState.update {
            it.copy(
                showDatePicker = false,
                anchorStart = date.atStartOfDay().toString(),
                anchorEnd = date.atTime(23, 59, 59).toString()
            )
        }
        openAddLegSheet()
    }

    fun openAddLegSheet() {
        val token = generation
        viewModelScope.launch {
            try {
            val proof = capture(token)
            val st = _uiState.value
            val (anchorStart, anchorEnd) = if (st.draftLegs.isEmpty()) {
                val s = st.anchorStart ?: return@launch
                val e = st.anchorEnd ?: return@launch
                s to e
            } else {
                val dates = st.drives.flatMap { listOf(it.startDate, it.endDate) } +
                    st.charges.flatMap { listOf(it.startDate, it.endDate) }
                (dates.minByOrNull { parseIsoInstant(it) ?: java.time.Instant.MAX } ?: return@launch) to
                    (dates.maxByOrNull { parseIsoInstant(it) ?: java.time.Instant.MIN } ?: return@launch)
            }
            val eligible = tripRepository.getUnusedLegsAround(proof.second.localHistoryCarId,
                anchorStart, anchorEnd, windowDays = 1)
            if (!current(token, proof)) return@launch
            val draftSet = st.draftLegs.toSet()
            val filtered = EligibleLegs(
                drives = eligible.drives.filter { LegRef(SavedTripLeg.TYPE_DRIVE, it.driveId) !in draftSet },
                charges = eligible.charges.filter { LegRef(SavedTripLeg.TYPE_CHARGE, it.chargeId) !in draftSet }
            )
            _uiState.update { it.copy(showAddLegSheet = true, eligibleLegs = filtered) }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {
                if (token == generation) _uiState.update { it.copy(error = "history_identity_unavailable") }
            }
        }
    }

    fun closeAddLegSheet() {
        _uiState.update { it.copy(showAddLegSheet = false, eligibleLegs = null) }
    }

    fun pickLegs(refs: List<LegRef>) {
        if (refs.isEmpty()) {
            closeAddLegSheet()
            return
        }
        viewModelScope.launch {
            try {
                rebuild((_uiState.value.draftLegs + refs).distinct())
                _uiState.update { it.copy(showAddLegSheet = false, eligibleLegs = null) }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { _uiState.update { it.copy(error = "history_identity_unavailable") } }
        }
    }

    fun removeLeg(ref: LegRef) {
        viewModelScope.launch {
            try { rebuild(_uiState.value.draftLegs.filterNot { it == ref }) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _uiState.update { it.copy(error = "history_identity_unavailable") } }
        }
    }

    fun setName(name: String) {
        _uiState.update {
            it.copy(
                name = name,
                preview = TripAggregator.buildQualifiedTrip(it.drives, it.charges, name.trim().ifEmpty { null })
            )
        }
    }

    fun clearError() { _uiState.update { it.copy(error = null) } }

    fun save() {
        val st = _uiState.value
        val startDate = st.preview?.startDate ?: return
        if (st.isSaving) return
        val token = generation
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val proof = capture(token)
                val saved = tripRepository.createTrip(proof.second.localHistoryCarId, st.draftLegs,
                    st.name.trim().ifEmpty { null }, EligibleLegs(st.drives, st.charges)) {
                    current(token, proof)
                }
                if (!current(token, proof)) return@launch
                _uiState.update { it.copy(createdStartDate = startDate.takeIf { saved != null },
                    createdSavedTripId = saved,
                    error = if (saved == null) "trip_save_failed" else null) }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {
                if (token == generation) _uiState.update { it.copy(error = "trip_save_failed") }
            } finally {
                if (token == generation) _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private suspend fun rebuild(draft: List<LegRef>) {
        val token = generation
        val proof = capture(token)
        val resolved = tripRepository.resolveLegs(proof.second.localHistoryCarId, draft)
        if (!current(token, proof)) return
        val drives = resolved.drives.sortedBy { parseIsoInstant(it.startDate) }
        val charges = resolved.charges.sortedBy { parseIsoInstant(it.startDate) }
        _uiState.update {
            it.copy(
                draftLegs = draft,
                drives = drives,
                charges = charges,
                preview = TripAggregator.buildQualifiedTrip(drives, charges, it.name.trim().ifEmpty { null })
            )
        }
    }
}

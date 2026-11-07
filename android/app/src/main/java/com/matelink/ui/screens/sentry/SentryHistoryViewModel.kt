package com.matelink.ui.screens.sentry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.SentryStateDataStore
import com.matelink.data.local.SentryState
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.dao.HourlyCount
import com.matelink.data.local.dao.SentryAlertLogDao
import com.matelink.data.local.entity.SentryAlertLog
import com.matelink.data.repository.GeocodingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

data class DayGroup(
    val dateMillis: Long,
    val alerts: List<SentryAlertLog>
)

data class SentryHistoryUiState(
    val isLoading: Boolean = true,
    val isSessionActive: Boolean = false,
    val sessionStartedAt: Long = 0L,
    val currentSessionAlerts: List<SentryAlertLog> = emptyList(),
    val pastAlertsByDay: List<DayGroup> = emptyList(),
    val identityUnavailable: Boolean = false,
    /** 72-element array: index 0 = oldest hour (72h ago), index 71 = current hour */
    val heatmapCounts: IntArray = IntArray(HEATMAP_BLOCKS),
    /** Epoch millis of the start of the heatmap window (floored to hour boundary) */
    val heatmapStartMillis: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SentryHistoryUiState) return false
        return isLoading == other.isLoading &&
            isSessionActive == other.isSessionActive &&
            sessionStartedAt == other.sessionStartedAt &&
            currentSessionAlerts == other.currentSessionAlerts &&
            pastAlertsByDay == other.pastAlertsByDay &&
            identityUnavailable == other.identityUnavailable &&
            heatmapCounts.contentEquals(other.heatmapCounts) &&
            heatmapStartMillis == other.heatmapStartMillis
    }

    override fun hashCode(): Int {
        var result = isLoading.hashCode()
        result = 31 * result + isSessionActive.hashCode()
        result = 31 * result + sessionStartedAt.hashCode()
        result = 31 * result + currentSessionAlerts.hashCode()
        result = 31 * result + pastAlertsByDay.hashCode()
        result = 31 * result + identityUnavailable.hashCode()
        result = 31 * result + heatmapCounts.contentHashCode()
        result = 31 * result + heatmapStartMillis.hashCode()
        return result
    }
}

const val HEATMAP_BLOCKS = 72
const val HEATMAP_COLS = 12
const val HEATMAP_ROWS = 6
/** Each block covers 2 hours → 72 blocks = 144 hours = 6 days */
const val HEATMAP_BUCKET_HOURS = 2
const val HEATMAP_BUCKET_MS = HEATMAP_BUCKET_HOURS * 3_600_000L

internal data class SentryHistoryIdentity(
    val localHistoryCarId: Int,
    val stillCurrent: suspend () -> Boolean
)

internal interface SentryHistorySource {
    val scopeChanges: Flow<Unit>
    suspend fun resolve(remoteCarId: Int): SentryHistoryIdentity
    suspend fun readState(localId: Int): SentryState
    fun observeAlerts(localId: Int): Flow<List<SentryAlertLog>>
    suspend fun countByHour(localId: Int, sinceMillis: Long): List<HourlyCount>
}

/** One read generation owns its Room flow, state and heatmap. */
internal class SentryHistoryController(
    private val source: SentryHistorySource,
    private val scope: CoroutineScope,
    private val onVerifiedAlerts: (List<SentryAlertLog>, suspend () -> Boolean, CoroutineScope) -> Unit = { _, _, _ -> },
    private val onReset: () -> Unit = {}
) {
    private val _uiState = MutableStateFlow(SentryHistoryUiState())
    val uiState: StateFlow<SentryHistoryUiState> = _uiState.asStateFlow()
    private var remoteCarId: Int? = null
    private var generation = 0L
    private var readJob: Job? = null

    init {
        scope.launch { source.scopeChanges.collect { remoteCarId?.let(::refresh) } }
    }

    fun setCarId(id: Int) {
        if (remoteCarId == id) return
        remoteCarId = id
        refresh(id)
    }

    private fun refresh(id: Int) {
        val token = ++generation
        readJob?.cancel()
        onReset()
        _uiState.value = SentryHistoryUiState(isLoading = true)
        readJob = scope.launch {
            try {
                val identity = source.resolve(id)
                suspend fun current(): Boolean = token == generation && remoteCarId == id &&
                    identity.stillCurrent() && token == generation && remoteCarId == id
                if (!current()) throw HistoryIdentityUnavailableException()
                val localId = identity.localHistoryCarId
                source.observeAlerts(localId).collectLatest { alerts ->
                    if (alerts.any { it.carId != localId }) throw HistoryIdentityUnavailableException()
                    val state = source.readState(localId)
                    if (!current()) throw HistoryIdentityUnavailableException()
                    val zone = ZoneId.systemDefault()
                    val heatmapStart = java.time.LocalDate.now(zone).minusDays((HEATMAP_ROWS - 1).toLong())
                        .atStartOfDay(zone).toInstant().toEpochMilli()
                    val counts = IntArray(HEATMAP_BLOCKS)
                    source.countByHour(localId, heatmapStart).forEach { hc ->
                        val index = ((hc.hourBucket * 3_600_000L - heatmapStart) / HEATMAP_BUCKET_MS).toInt()
                        if (index in counts.indices) counts[index] += hc.cnt
                    }
                    if (!current()) throw HistoryIdentityUnavailableException()
                    val sessionStart = state.sessionStartedAt.takeIf { state.sentryActive && it > 0L }
                    val currentAlerts = alerts.filter { sessionStart != null && it.sessionStartedAt == sessionStart }
                    val pastAlerts = alerts.filter { sessionStart == null || it.sessionStartedAt != sessionStart }
                    val grouped = pastAlerts.groupBy { alert ->
                        Instant.ofEpochMilli(alert.detectedAt).atZone(zone).toLocalDate()
                            .atStartOfDay(zone).toInstant().toEpochMilli()
                    }.map { (date, rows) -> DayGroup(date, rows) }.sortedByDescending { it.dateMillis }
                    _uiState.value = SentryHistoryUiState(
                        isLoading = false, isSessionActive = state.sentryActive,
                        sessionStartedAt = state.sessionStartedAt,
                        currentSessionAlerts = currentAlerts, pastAlertsByDay = grouped,
                        heatmapCounts = counts, heatmapStartMillis = heatmapStart
                    )
                    onVerifiedAlerts(alerts, ::current, this@launch)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (token == generation && remoteCarId == id)
                    _uiState.value = SentryHistoryUiState(isLoading = false, identityUnavailable = true)
            }
        }
    }
}

@HiltViewModel
class SentryHistoryViewModel @Inject constructor(
    private val sentryAlertLogDao: SentryAlertLogDao,
    private val sentryStateDataStore: SentryStateDataStore,
    private val geocodingRepository: GeocodingRepository,
    vehicleContextRepository: VehicleContextRepository
) : ViewModel() {
    private val geocodedIds = mutableSetOf<Long>()
    private val controller = SentryHistoryController(object : SentryHistorySource {
        override val scopeChanges = vehicleContextRepository.historyScopeChanges.map { Unit }
        override suspend fun resolve(remoteCarId: Int): SentryHistoryIdentity {
            val scope = vehicleContextRepository.captureReadScope()
            val context = vehicleContextRepository.resolveRemote(remoteCarId)
            if (!vehicleContextRepository.isHistoryContextCurrent(scope, context))
                throw HistoryIdentityUnavailableException()
            return SentryHistoryIdentity(context.localHistoryCarId) {
                vehicleContextRepository.isHistoryContextCurrent(scope, context)
            }
        }
        override suspend fun readState(localId: Int) = sentryStateDataStore.getState(localId)
        override fun observeAlerts(localId: Int) = sentryAlertLogDao.observeAll(localId)
        override suspend fun countByHour(localId: Int, sinceMillis: Long) =
            sentryAlertLogDao.countByHour(localId, sinceMillis)
    }, viewModelScope, ::resolveAddresses, { geocodedIds.clear() })

    val uiState: StateFlow<SentryHistoryUiState> = controller.uiState
    fun setCarId(id: Int) = controller.setCarId(id)

    private fun resolveAddresses(
        alerts: List<SentryAlertLog>, stillCurrent: suspend () -> Boolean, scope: CoroutineScope
    ) {
        for (alert in alerts) {
            if (alert.address.isNullOrBlank() && alert.latitude != null && alert.longitude != null &&
                geocodedIds.add(alert.id)) {
                scope.launch {
                    try {
                        val cached = geocodingRepository.getFromCache(alert.latitude, alert.longitude)
                        val address = if (cached != null) {
                            listOfNotNull(cached.city, cached.regionName).joinToString(", ").ifBlank { null }
                        } else geocodingRepository.reverseGeocode(alert.latitude, alert.longitude)
                        if (address != null && stillCurrent()) sentryAlertLogDao.updateAddress(alert.id, address)
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { /* optional address */ }
                }
            }
        }
    }
}

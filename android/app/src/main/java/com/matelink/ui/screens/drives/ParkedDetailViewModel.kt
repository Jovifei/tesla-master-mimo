package com.matelink.ui.screens.drives

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.R
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.DriveSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.util.parseIsoInstant
import com.matelink.util.toChineseDisplayAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import javax.inject.Inject

data class ParkedDetailUiState(
    val isLoading: Boolean = true,
    val data: ParkedDetailData? = null,
    val error: String? = null,
    val degraded: Boolean = false,
    val syncWarning: String? = null
)

internal data class ParkedPageIdentity(val stillCurrent: () -> Boolean, val localHistoryCarId: Int? = null)

internal interface ParkedPageSource {
    val scopeChanges: Flow<Unit>
    val timeoutMs: Long get() = Long.MAX_VALUE
    suspend fun identity(carId: Int): ParkedPageIdentity
    suspend fun localDetail(localHistoryCarId: Int, olderDriveId: Int, newerDriveId: Int): ParkedDetailData? = null
    suspend fun detail(carId: Int, olderDriveId: Int, newerDriveId: Int): ApiResult<ParkedDetailData>
}

@HiltViewModel
class ParkedDetailViewModel private constructor(
    private val source: ParkedPageSource,
    private val appContext: Context?
) : ViewModel() {
    internal constructor(source: ParkedPageSource) : this(source, null)

    @Inject constructor(
        @ApplicationContext appContext: Context,
        repository: TeslamateRepository,
        vehicleContexts: VehicleContextRepository,
        driveSummaryDao: DriveSummaryDao
    ) : this(object : ParkedPageSource {
        override val scopeChanges = vehicleContexts.historyScopeChanges.map { Unit }
        override val timeoutMs = 20_000L
        override suspend fun identity(carId: Int): ParkedPageIdentity {
            val scope = vehicleContexts.captureReadScope()
            val context = vehicleContexts.cachedContextForRemote(carId, scope)
                ?: throw HistoryIdentityUnavailableException()
            return ParkedPageIdentity(
                stillCurrent = { vehicleContexts.isHistoryContextCurrentNow(scope, context) },
                localHistoryCarId = context.localHistoryCarId
            )
        }
        override suspend fun localDetail(localHistoryCarId: Int, olderDriveId: Int, newerDriveId: Int): ParkedDetailData? {
            return readLocalParkedDetail(driveSummaryDao, localHistoryCarId, olderDriveId, newerDriveId)
        }
        override suspend fun detail(carId: Int, olderDriveId: Int, newerDriveId: Int) =
            repository.getParkedDetail(carId, olderDriveId, newerDriveId)
    }, appContext)
    private val _uiState = MutableStateFlow(ParkedDetailUiState())
    val uiState: StateFlow<ParkedDetailUiState> = _uiState.asStateFlow()

    private var generation = 0L
    private var loadJob: Job? = null
    private var activeIdentity: ParkedPageIdentity? = null
    private var request: Triple<Int, Int, Int>? = null

    init {
        viewModelScope.launch {
            source.scopeChanges.collect {
                if (activeIdentity?.stillCurrent() == false) request?.let { load(it.first, it.second, it.third) }
            }
        }
    }

    fun load(carId: Int, olderDriveId: Int, newerDriveId: Int) {
        request = Triple(carId, olderDriveId, newerDriveId)
        val currentGeneration = ++generation
        loadJob?.cancel()
        activeIdentity = null
        _uiState.value = ParkedDetailUiState(isLoading = true)
        loadJob = viewModelScope.launch {
            val execution = currentCoroutineContext()
            var identity: ParkedPageIdentity? = null
            fun requireCurrent() {
                execution.ensureActive()
                if (currentGeneration != generation || identity?.stillCurrent() != true)
                    throw HistoryIdentityUnavailableException()
            }
            try {
                identity = source.identity(carId)
                requireCurrent()
                activeIdentity = identity
                val local = identity.localHistoryCarId?.let { localCarId ->
                    try {
                        source.localDetail(localCarId, olderDriveId, newerDriveId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                requireCurrent()
                if (local != null)
                    _uiState.value = ParkedDetailUiState(isLoading = false, data = local, degraded = true)

                val result = if (source.timeoutMs == Long.MAX_VALUE)
                    source.detail(carId, olderDriveId, newerDriveId)
                else withTimeoutOrNull(source.timeoutMs) {
                    source.detail(carId, olderDriveId, newerDriveId)
                }
                requireCurrent()
                _uiState.value = when (result) {
                    is ApiResult.Success -> ParkedDetailUiState(isLoading = false, data = result.data)
                    is ApiResult.Error -> ParkedDetailUiState(
                        isLoading = false, data = local, degraded = local != null,
                        syncWarning = if (local != null) message(R.string.history_sync_cached, "Cloud detail unavailable") else null,
                        error = if (local == null) result.message else null
                    )
                    null -> ParkedDetailUiState(
                        isLoading = false, data = local, degraded = local != null,
                        syncWarning = if (local != null) message(R.string.history_sync_timeout, "Cloud detail timed out") else null,
                        error = if (local == null) message(R.string.history_sync_timeout, "Cloud detail timed out") else null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (currentGeneration == generation && execution.isActive)
                    _uiState.value = ParkedDetailUiState(isLoading = false, error = HISTORY_IDENTITY_UNAVAILABLE)
            }
        }
    }

    private fun message(id: Int, fallback: String): String = appContext?.getString(id) ?: fallback
}

internal suspend fun readLocalParkedDetail(
    dao: DriveSummaryDao,
    localHistoryCarId: Int,
    olderDriveId: Int,
    newerDriveId: Int
): ParkedDetailData? {
    val older = dao.get(localHistoryCarId, olderDriveId) ?: return null
    val newer = dao.get(localHistoryCarId, newerDriveId) ?: return null
    return buildLocalParkedDetail(olderDriveId, newerDriveId, older, newer)
}

internal fun buildLocalParkedDetail(
    olderDriveId: Int,
    newerDriveId: Int,
    older: DriveSummary,
    newer: DriveSummary
): ParkedDetailData? {
    if (older.carId != newer.carId || older.driveId != olderDriveId || newer.driveId != newerDriveId) return null
    val start = parseIsoInstant(older.endDate) ?: return null
    val end = parseIsoInstant(newer.startDate) ?: return null
    if (!start.isBefore(end)) return null
    val startBattery = older.endBatteryLevel.takeIf { it in 0..100 }
    val endBattery = newer.startBatteryLevel.takeIf { it in 0..100 }
    return ParkedDetailData(
        olderDriveId = olderDriveId,
        newerDriveId = newerDriveId,
        startDate = older.endDate,
        endDate = newer.startDate,
        address = older.endAddress?.toChineseDisplayAddress() ?: older.endAddress,
        startBatteryLevel = startBattery,
        endBatteryLevel = endBattery,
        batteryDelta = if (startBattery != null && endBattery != null) endBattery - startBattery else null,
        energyKwh = null,
        averagePowerKw = null,
        peakPowerKw = null,
        insideTempAverage = null,
        outsideTempAverage = null,
        sampleCount = 0,
        coverageSeconds = 0,
        coverageRatio = 0.0,
        linkedCharge = null,
        source = "local_room_cache"
    )
}

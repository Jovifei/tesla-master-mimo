package com.matelink.ui.screens.stats

import android.content.Context
import androidx.lifecycle.ViewModel
import com.matelink.R
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.matelink.data.api.models.Units
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.model.Currency
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE
import com.matelink.data.repository.GeocodeProgressInfo
import com.matelink.data.repository.StatsRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.sync.DataSyncWorker
import com.matelink.data.sync.HistoryMetadataState
import com.matelink.data.sync.HistoryMetadataStore
import com.matelink.data.sync.SyncLogCollector
import com.matelink.data.sync.SyncManager
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.model.CarStats
import com.matelink.domain.model.SyncPhase
import com.matelink.domain.model.SyncProgress
import com.matelink.domain.model.YearFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isSyncing: Boolean = false,
    val carStats: CarStats? = null,
    val availableYears: List<Int> = emptyList(),
    val selectedYearFilter: YearFilter = YearFilter.AllTime,
    val deepSyncProgress: Float = 0f,
    val syncProgress: SyncProgress? = null,
    val geocodeProgress: GeocodeProgressInfo? = null,
    val isGeocoding: Boolean = false,
    val currencySymbol: String = Currency.CNY.symbol,
    val units: Units? = null,
    val historyMetadata: HistoryMetadataState? = null,
    val error: String? = null,
    val isUpdating: Boolean = false
)

internal data class StatsLoadResult(val stats: CarStats?, val years: List<Int>, val progress: Float)

internal fun StatsUiState.publishStats(result: StatsLoadResult): StatsUiState = copy(
    isLoading = false, isRefreshing = false, isUpdating = false,
    carStats = result.stats, availableYears = result.years,
    deepSyncProgress = result.progress, syncProgress = result.stats?.syncProgress,
    error = null,
    currencySymbol = result.stats?.currencyCode?.let { code ->
        Currency.entries.firstOrNull { it.code.equals(code, ignoreCase = true) }?.symbol ?: code
    } ?: currencySymbol
)

internal data class StatsIdentityProof(
    val localHistoryCarId: Int,
    val stillCurrent: suspend () -> Boolean
)

/** Actual async publisher used by both analytics screens; cancelled work cannot publish after ABA. */
internal class ScopedUiLoader(private val scope: CoroutineScope) {
    private var generation = 0L
    private var job: Job? = null

    inner class Request internal constructor(private val token: Long, private val proof: suspend () -> Boolean) {
        suspend fun requireCurrent() {
            if (token != generation || !proof() || token != generation)
                throw HistoryIdentityUnavailableException()
        }
    }

    fun invalidate() {
        generation++
        job?.cancel()
    }

    fun <T> load(
        proof: suspend () -> (suspend () -> Boolean),
        fetch: suspend (Request) -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        invalidate()
        val token = generation
        job = scope.launch {
            var capturedProof: (suspend () -> Boolean)? = null
            try {
                val currentProof = proof()
                capturedProof = currentProof
                val request = Request(token, currentProof)
                request.requireCurrent()
                val result = fetch(request)
                request.requireCurrent()
                onSuccess(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val stillCurrent = try { capturedProof?.invoke() == true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (token == generation && stillCurrent) onFailure(e)
            }
        }
    }
}

@HiltViewModel
class StatsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val teslamateRepository: TeslamateRepository,
    private val syncManager: SyncManager,
    private val syncLogCollector: SyncLogCollector,
    private val settingsDataStore: SettingsDataStore,
    private val historyMetadataStore: HistoryMetadataStore,
    private val vehicleContextRepository: VehicleContextRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()

    /** Sync logs for debug viewing */
    val syncLogs: StateFlow<List<String>> = syncLogCollector.logs

    /** Expose sync status for UI to observe */
    val syncStatus = syncManager.syncStatus

    private var carId: Int? = null
    private var identityEpoch = 0L
    private var identityJob: Job? = null
    private var unitsJob: Job? = null
    private var activeProof: StatsIdentityProof? = null
    private var activeScope: HistoryReadScope? = null
    private var observedScope: HistoryReadScope? = null
    private var scopeObserved = false
    private val statsLoader = ScopedUiLoader(viewModelScope)
    private var syncObserverJob: Job? = null
    private var progressObserverJob: Job? = null
    private var geocodeProgressJob: Job? = null
    private var historyMetadataJob: Job? = null

    init {
        loadSettings()
        viewModelScope.launch {
            vehicleContextRepository.historyScopeChanges.collect { scope ->
                scopeObserved = true
                observedScope = scope
                val id = carId ?: return@collect
                if (activeScope != null && activeScope != scope) activate(id)
                else if (activeScope == null && identityJob?.isActive != true) activate(id)
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val settings = settingsDataStore.settings.first()
            val currency = Currency.findByCode(settings.currencyCode)
            _uiState.update { if (it.carStats == null) it.copy(currencySymbol = currency.symbol) else it }
        }
    }

    fun setCarId(id: Int) {
        activate(id)
    }

    private fun activate(id: Int) {
        carId = id
        val epoch = ++identityEpoch
        identityJob?.cancel()
        unitsJob?.cancel()
        syncObserverJob?.cancel()
        progressObserverJob?.cancel()
        geocodeProgressJob?.cancel()
        historyMetadataJob?.cancel()
        statsLoader.invalidate()
        activeProof = null
        activeScope = null
        val previous = _uiState.value
        _uiState.value = StatsUiState(
            selectedYearFilter = previous.selectedYearFilter,
            currencySymbol = previous.currencySymbol
        )
        identityJob = viewModelScope.launch {
            try {
                val scope = vehicleContextRepository.captureReadScope()
                val context = vehicleContextRepository.resolveRemote(id)
                val proof = StatsIdentityProof(context.localHistoryCarId) {
                    vehicleContextRepository.isHistoryContextCurrent(scope, context)
                }
                if (scopeObserved && observedScope != null && observedScope != scope) {
                    activate(id)
                    return@launch
                }
                if (!isCurrent(id, epoch, proof)) throw HistoryIdentityUnavailableException()
                activeProof = proof
                activeScope = scope
                loadUnits(id, epoch, proof)
                startObservingSyncStatus(id, epoch, proof)
                startObservingProgress(id, epoch, proof)
                startObservingGeocodeProgress(id, epoch, proof)
                startObservingHistoryMetadata(id, epoch, proof)
                requestStats(id, epoch, proof)
                // The existing KEEP worker fetches summaries only after identity is verified.
                triggerSync()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (carId == id && identityEpoch == epoch)
                    _uiState.update { it.copy(isLoading = false, error = HISTORY_IDENTITY_UNAVAILABLE) }
            }
        }
    }

    private suspend fun isCurrent(id: Int, epoch: Long, proof: StatsIdentityProof): Boolean =
        carId == id && identityEpoch == epoch && proof.stillCurrent() &&
            carId == id && identityEpoch == epoch

    private fun loadUnits(id: Int, epoch: Long, proof: StatsIdentityProof) {
        unitsJob?.cancel()
        unitsJob = viewModelScope.launch {
            try {
                val result = teslamateRepository.getCarStatus(id)
                if (isCurrent(id, epoch, proof) && result is ApiResult.Success)
                    _uiState.update { it.copy(units = result.data.units) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* optional units */ }
        }
    }

    /**
     * Observe sync progress directly from Room's reactive queries.
     * This provides real-time updates as aggregates are written to the database.
     */
    private fun startObservingProgress(id: Int, epoch: Long, proof: StatsIdentityProof) {
        progressObserverJob?.cancel()
        progressObserverJob = viewModelScope.launch {
            try {
                statsRepository.observeDeepSyncProgress(id).collect { progress ->
                    if (isCurrent(id, epoch, proof))
                        _uiState.update { it.copy(deepSyncProgress = progress) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: HistoryIdentityUnavailableException) {
                if (carId == id && identityEpoch == epoch)
                    _uiState.update { it.copy(error = HISTORY_IDENTITY_UNAVAILABLE) }
            }
        }
    }

    /**
     * Observe geocoding progress for location identification.
     * Shows progress bar while locations are being identified in background.
     */
    private fun startObservingGeocodeProgress(id: Int, epoch: Long, proof: StatsIdentityProof) {
        geocodeProgressJob?.cancel()
        geocodeProgressJob = viewModelScope.launch {
            try {
                statsRepository.observeGeocodeProgress(id).collect { progress ->
                    if (isCurrent(id, epoch, proof)) _uiState.update {
                        it.copy(geocodeProgress = progress,
                            isGeocoding = progress != null && progress.processed < progress.total &&
                                progress.availability == com.matelink.data.repository.ChineseLocationAvailability.READY)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: HistoryIdentityUnavailableException) {
                if (carId == id && identityEpoch == epoch)
                    _uiState.update { it.copy(error = HISTORY_IDENTITY_UNAVAILABLE) }
            }
        }
    }

    private fun startObservingHistoryMetadata(id: Int, epoch: Long, proof: StatsIdentityProof) {
        historyMetadataJob?.cancel()
        historyMetadataJob = viewModelScope.launch {
            try {
                historyMetadataStore.observe(proof.localHistoryCarId).collect { metadata ->
                    if (isCurrent(id, epoch, proof))
                        _uiState.update { it.copy(historyMetadata = metadata) }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: HistoryIdentityUnavailableException) {
                if (carId == id && identityEpoch == epoch)
                    _uiState.update { it.copy(error = HISTORY_IDENTITY_UNAVAILABLE) }
            }
        }
    }

    /**
     * Observe sync status changes to update isSyncing state.
     * Progress updates are handled separately by observeDeepSyncProgress Flow.
     */
    private fun startObservingSyncStatus(id: Int, epoch: Long, proof: StatsIdentityProof) {
        syncObserverJob?.cancel()
        syncObserverJob = viewModelScope.launch {
            syncManager.syncStatus.collect { status ->
                if (!isCurrent(id, epoch, proof)) return@collect
                val carProgress = status.carProgresses[proof.localHistoryCarId]

                // Update syncing state
                val isSyncing = carProgress != null &&
                    carProgress.phase != SyncPhase.COMPLETE &&
                    carProgress.phase != SyncPhase.ERROR &&
                    carProgress.phase != SyncPhase.IDLE

                _uiState.update { it.copy(isSyncing = isSyncing, syncProgress = carProgress) }

                // Reload full stats when sync completes (not during sync - too expensive)
                if (carProgress?.phase == SyncPhase.COMPLETE) requestStats(id, epoch, proof)
            }
        }
    }

    fun setYearFilter(yearFilter: YearFilter) {
        _uiState.update { it.copy(selectedYearFilter = yearFilter, isUpdating = true) }
        val id = carId ?: return
        activeProof?.let { requestStats(id, identityEpoch, it) }
    }

    fun refresh() {
        val id = carId ?: return
        _uiState.update { it.copy(isRefreshing = true) }
        activeProof?.let {
            requestStats(id, identityEpoch, it)
            triggerSync()
        } ?: activate(id)
    }

    /**
     * Trigger a background sync to fetch new data from the server.
     * Uses KEEP policy to avoid interrupting a running sync.
     */
    fun triggerSync() {
        // Skip if sync is already running
        if (syncManager.syncStatus.value.isAnySyncing) {
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<DataSyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(DataSyncWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            DataSyncWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP, // Don't interrupt running sync
            syncRequest
        )
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Fetch drives between two dates for displaying range record details.
     */
    suspend fun getDrivesForRangeRecord(fromDate: String, toDate: String): List<DriveSummary> {
        val id = carId ?: return emptyList()
        val proof = activeProof ?: return emptyList()
        val epoch = identityEpoch
        return try {
            if (!isCurrent(id, epoch, proof)) return emptyList()
            val rows = statsRepository.getDrivesBetweenDates(id, fromDate, toDate)
            if (isCurrent(id, epoch, proof)) rows else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (_: HistoryIdentityUnavailableException) {
            emptyList()
        }
    }

    private fun requestStats(id: Int, epoch: Long, proof: StatsIdentityProof) {
        val filter = _uiState.value.selectedYearFilter
        if (_uiState.value.carStats == null) _uiState.update { it.copy(isLoading = true) }
        statsLoader.load(
            proof = { suspend {
                isCurrent(id, epoch, proof) && _uiState.value.selectedYearFilter == filter
            } },
            fetch = { request ->
                val hasData = statsRepository.hasData(id)
                request.requireCurrent()
                val progress = statsRepository.getDeepSyncProgress(id)
                request.requireCurrent()
                if (!hasData) StatsLoadResult(null, emptyList(), progress) else {
                    val years = statsRepository.getAvailableYears(id)
                    request.requireCurrent()
                    val stats = statsRepository.getStats(id, filter)
                    request.requireCurrent()
                    StatsLoadResult(stats, years, progress)
                }
            },
            onSuccess = { result ->
                _uiState.update { it.publishStats(result) }
            },
            onFailure = { error ->
                _uiState.update { it.copy(isLoading = false, isRefreshing = false, isUpdating = false,
                    error = if (error is HistoryIdentityUnavailableException) HISTORY_IDENTITY_UNAVAILABLE
                    else error.message ?: context.getString(R.string.error_load_stats)) }
            }
        )
    }
}

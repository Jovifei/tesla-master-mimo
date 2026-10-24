package com.matelink.ui.screens.dashboard

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.validHistoryVehicleUid
import com.matelink.data.api.models.DataReadiness
import com.matelink.data.api.models.Units
import com.matelink.data.repository.ApiErrorKind
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.GeocodingRepository
import com.matelink.data.repository.apiErrorKindFor
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.local.DataReadinessStore
import com.matelink.data.sync.DataSyncWorker
import java.time.Clock
import com.matelink.domain.telemetry.failedSnapshotFreshness
import com.matelink.domain.telemetry.SnapshotFreshness
import com.matelink.domain.telemetry.snapshotEvidence
import com.matelink.domain.telemetry.usableVehicleCoordinates
import com.matelink.domain.map.VehiclePositionResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton

internal fun withCachedPositionEvidence(evidence: com.matelink.domain.telemetry.SnapshotEvidence): com.matelink.domain.telemetry.SnapshotEvidence =
    snapshotEvidence(
        evidence.source,
        evidence.observedAt,
        evidence.fieldSources + mapOf(
            "latitude" to "database_latest",
            "longitude" to "database_latest"
        )
    )

/** No HTTP calls: consumes only an already received, explicitly scoped raw response. */
@Singleton
class DashboardTpmsReceiptCollector @Inject constructor(
    private val vehicleContexts: com.matelink.data.local.VehicleContextRepository,
    private val history: com.matelink.data.repository.TpmsHistoryRepository,
    private val rules: com.matelink.data.local.TpmsChangeRuleStore,
    private val clock: Clock
) {
    internal suspend fun collect(
        scope: com.matelink.data.local.HistoryReadScope,
        car: CarData?, rawStatus: CarStatus?, stillCurrent: () -> Boolean
    ): Boolean {
        if (car == null || rawStatus?.tpmsDetails == null || !stillCurrent()) return false
        val active = try {
            vehicleContexts.captureReadScope()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: com.matelink.data.local.HistoryIdentityUnavailableException) { return false }
        if (active != scope || !stillCurrent()) return false
        // A foreground status response is not permission to invent or discover a
        // vehicle binding. Missing/mismatched cached UID proof is simply skipped.
        val vehicle = try {
            if (scope.source == com.matelink.data.local.HistoryConnectionSource.CLOUD) {
                vehicleContexts.cachedProofForCar(car, scope)
            } else {
                vehicleContexts.cachedContextForRemote(car.carId, scope)
            }
        } catch (_: com.matelink.data.local.HistoryIdentityUnavailableException) { null } ?: return false
        val context = currentCoroutineContext()
        fun ensureCurrent() {
            context.ensureActive()
            if (!stillCurrent() || !vehicleContexts.isHistoryContextCurrentNow(scope, vehicle)) {
                throw CancellationException("Dashboard TPMS response superseded")
            }
        }
        ensureCurrent()
        val now = clock.millis()
        val samples = com.matelink.data.sync.verifiedTpmsSamples(vehicle.localHistoryCarId, rawStatus.tpmsDetails, now)
        val warnings = com.matelink.data.sync.verifiedTpmsWarnings(rawStatus.tpmsDetails, now)
        if (samples.isEmpty() && warnings.isEmpty()) return false
        val rule = rules.observe(vehicle.localHistoryCarId).first()
        ensureCurrent()
        var accepted = false
        for (sample in samples) {
            ensureCurrent()
            com.matelink.data.sync.admitTpmsObservation(vehicle.localHistoryCarId, sample, rule, history, rules,
                ensureCurrent = { ensureCurrent() }, executionNow = now)
            ensureCurrent()
            val matches = history.hasMatchingVerifiedCollectorReceipt(vehicle.localHistoryCarId, sample)
            ensureCurrent()
            accepted = accepted || matches
        }
        for (warning in warnings) {
            ensureCurrent()
            rules.rememberVerifiedWarning(vehicle.localHistoryCarId, warning.details, warning.observedAt)
            ensureCurrent()
            accepted = true
        }
        return accepted
    }
}

data class DashboardUiState(
    val isLoading: Boolean = true,
    val car: CarData? = null,
    val status: CarStatus? = null,
    val error: String? = null,
    val errorCode: Int? = null,
    val errorKind: ApiErrorKind? = null,
    val snapshotSource: String? = null,
    val observedAt: String? = null,
    val fieldSources: Map<String, String> = emptyMap(),
    val snapshotFreshness: SnapshotFreshness = SnapshotFreshness.UNAVAILABLE,
    val snapshotMixedSources: Boolean = false,
    val units: Units = Units(),
    val cachedAddress: String? = null,
    val dataReadiness: DataReadiness? = null,
    val dataReadinessCarId: Int? = null,
    val showReadinessIntro: Boolean = false,
    val customPhotoFile: java.io.File? = null,
    val isAmapConfigured: Boolean = false,
    val isHudDismissed: Boolean = false
)

/** Final publication fence: a late response from a previous login cannot reuse a numeric car id. */
internal fun dashboardStatusMayPublish(
    capturedScope: com.matelink.data.local.HistoryReadScope?,
    vehicle: com.matelink.data.local.VehicleContext?,
    currentScope: com.matelink.data.local.HistoryReadScope?,
    requestedCarId: Int,
    selectedCarId: Int,
    requestGeneration: Long,
    currentGeneration: Long,
    vehicleCurrent: Boolean
): Boolean = capturedScope != null && vehicle != null && capturedScope == currentScope &&
    vehicleCurrent && requestedCarId == selectedCarId && vehicle.remoteApiCarId == requestedCarId &&
    requestGeneration == currentGeneration

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: TeslamateRepository,
    private val settingsRepository: SettingsRepository,
    private val geocodingRepository: GeocodingRepository,
    private val dataReadinessStore: DataReadinessStore,
    private val vehiclePhotoStore: com.matelink.data.local.VehiclePhotoStore,
    private val amapSettingsStore: com.matelink.data.local.AmapSettingsStore,
    private val amapReverseGeocoder: com.matelink.data.repository.AmapReverseGeocoder,
    private val vehicleContextRepository: com.matelink.data.local.VehicleContextRepository,
    private val vehicleStatusStore: com.matelink.data.local.VehicleStatusStore,
    private val driveSummaryDao: com.matelink.data.local.dao.DriveSummaryDao,
    private val clock: Clock,
    private val tpmsReceiptCollector: DashboardTpmsReceiptCollector
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var requestGeneration = 0L

    init {
        val hasAmap = amapSettingsStore.currentKey().isNotBlank()
        _uiState.update { it.copy(isAmapConfigured = hasAmap) }
        loadDashboard()
        startPolling()
        viewModelScope.launch {
            vehiclePhotoStore.photoUpdateSignal.collect {
                val carId = _uiState.value.car?.carId ?: return@collect
                val photo = vehiclePhotoStore.getCustomPhotoFile(carId)
                _uiState.update { it.copy(customPhotoFile = photo) }
            }
        }
        viewModelScope.launch {
            amapSettingsStore.settings.collect { settings ->
                _uiState.update { it.copy(isAmapConfigured = settings.hasKey) }
            }
        }
        viewModelScope.launch {
            var first = true
            vehicleContextRepository.historyScopeChanges.collect {
                if (first) { first = false; return@collect }
                requestGeneration++
                val current = _uiState.value
                _uiState.value = DashboardUiState(isHudDismissed = current.isHudDismissed,
                    isAmapConfigured = current.isAmapConfigured)
                loadDashboard()
            }
        }
    }

    fun toggleHudDismissed() {
        _uiState.update { it.copy(isHudDismissed = !it.isHudDismissed) }
    }

    private suspend fun captureTpmsScope(): com.matelink.data.local.HistoryReadScope? = try {
        vehicleContextRepository.captureReadScope()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    /** Only the current authenticated /cars row may authorize cache binding.
     * No numeric-ID fallback can qualify a UID after failed discovery.
     */
    private fun statusCacheContext(scope: com.matelink.data.local.HistoryReadScope?,
                                   carId: Int, verifiedCar: CarData?): com.matelink.data.local.VehicleContext? {
        if (scope == null || verifiedCar?.carId != carId) return null
        if (scope.source == com.matelink.data.local.HistoryConnectionSource.CLOUD &&
            !validHistoryVehicleUid(verifiedCar.vehicleUid)) return null
        return try {
            vehicleContextRepository.resolve(verifiedCar, scope)
                .takeIf { vehicleContextRepository.isHistoryContextCurrentNow(scope, it) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    /** A received live status is displayable without durable UID proof, but
     * may not be written to history, cache, or external geocoding.
     */
    private suspend fun liveScopeCurrent(scope: com.matelink.data.local.HistoryReadScope?,
                                         carId: Int, generation: Long): Boolean {
        if (scope == null || generation != requestGeneration) return false
        return try {
            val current = vehicleContextRepository.captureReadScope()
            val selected = settingsRepository.currentCarId.first()
            generation == requestGeneration && scope == current && carId == selected
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    private suspend fun statusIdentityCurrent(scope: com.matelink.data.local.HistoryReadScope?,
                                               vehicle: com.matelink.data.local.VehicleContext?,
                                               carId: Int, generation: Long): Boolean {
        if (scope == null || vehicle == null || generation != requestGeneration) return false
        return try {
            dashboardStatusMayPublish(scope, vehicle,
                vehicleContextRepository.captureReadScope(),
                carId, settingsRepository.currentCarId.first(), generation, requestGeneration,
                vehicleContextRepository.isHistoryContextCurrentNow(scope, vehicle))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    private suspend fun collectTpms(
        scope: com.matelink.data.local.HistoryReadScope?, car: CarData?, rawStatus: CarStatus?, generation: Long
    ) {
        if (scope == null || generation != requestGeneration) return
        try {
            tpmsReceiptCollector.collect(scope, car, rawStatus) { generation == requestGeneration }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Optional history persistence must not turn a usable live response
            // into a dashboard/network error or expose request/account details.
            android.util.Log.w("DashboardViewModel", "event=tpms_receipt_persist_failed category=local_error")
        }
    }

    private fun loadDashboard() {
        val generation = ++requestGeneration
        viewModelScope.launch {
            try {
                val carId = settingsRepository.currentCarId.first()
                val tpmsScope = captureTpmsScope()

                val carsResult = repository.getCars()
                var car = when (carsResult) {
                    is ApiResult.Success -> {
                        val cars = carsResult.data
                        cars.find { it.carId == carId }
                    }
                    is ApiResult.Error -> null
                }

                val effectiveCarId = car?.carId ?: carId
                if (car == null && carsResult is ApiResult.Success) {
                    if (generation != requestGeneration) return@launch
                    _uiState.value = DashboardUiState(
                        isLoading = false,
                        error = "vehicle_not_found",
                        errorCode = 404,
                        errorKind = ApiErrorKind.CONFIGURATION,
                        isAmapConfigured = amapSettingsStore.currentKey().isNotBlank() || _uiState.value.isAmapConfigured,
                        isHudDismissed = _uiState.value.isHudDismissed
                    )
                    return@launch
                }
                val adapterResult = repository.getAdapterSnapshot(effectiveCarId)
                val statusResult = if (adapterResult is ApiResult.Error) {
                    repository.getCarStatus(effectiveCarId)
                } else null
                val liveStatus = when {
                    adapterResult is ApiResult.Success -> {
                        adapterResult.data.status
                    }
                    statusResult is ApiResult.Success -> statusResult.data.status
                    else -> null
                }
                if (!liveScopeCurrent(tpmsScope, effectiveCarId, generation)) return@launch
                collectTpms(tpmsScope, car.takeIf { carsResult is ApiResult.Success }, liveStatus, generation)
                // Vehicle discovery precedes the first live fetch; configuration may
                // only have been persisted by that fetch. Refresh metadata, not OAuth.
                if (liveStatus != null && car?.carDetails?.model.isNullOrBlank()) {
                    try {
                        val refreshed = repository.getCars()
                        if (refreshed is ApiResult.Success) car = refreshedDashboardCar(car, refreshed.data)
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { /* keep live state */ }
                }
                if (!liveScopeCurrent(tpmsScope, effectiveCarId, generation)) return@launch
                val cacheContext = statusCacheContext(tpmsScope, effectiveCarId,
                    car.takeIf { carsResult is ApiResult.Success })
                val cacheVerified = cacheContext != null &&
                    statusIdentityCurrent(tpmsScope, cacheContext, effectiveCarId, generation)
                val cachedStatus = if (cacheVerified) vehicleStatusStore.getCachedStatus(tpmsScope!!, cacheContext!!) else null
                // The fresh response is usable without a UID. Only a fresh UID
                // can authorize persistence or retrieval of a scoped cache.
                val status = if (liveStatus != null && cacheVerified)
                    vehicleStatusStore.reconcileAndSave(tpmsScope!!, cacheContext!!, liveStatus,
                        (adapterResult as? ApiResult.Success)?.data?.observedAt)
                else liveStatus ?: cachedStatus

                val units = when {
                    adapterResult is ApiResult.Success -> adapterResult.data.units
                    statusResult is ApiResult.Success -> statusResult.data.units
                    else -> Units()
                }
                val evidence = when {
                    adapterResult is ApiResult.Success -> snapshotEvidence(
                        adapterResult.data.source,
                        adapterResult.data.observedAt,
                        adapterResult.data.fieldSources
                    )
                    statusResult is ApiResult.Success -> snapshotEvidence(
                        "teslamate_api", null, emptyMap()
                    )
                    else -> snapshotEvidence(null, null, emptyMap())
                }
                // Previous GPS belongs to its own old receipt, never this response.
                val displayEvidence = evidence

                val primaryError = when {
                    carsResult is ApiResult.Error -> carsResult
                    car == null -> ApiResult.Error(message = "vehicle_not_found", code = 404)
                    adapterResult is ApiResult.Error && statusResult is ApiResult.Error -> statusResult
                    else -> null
                }
                if (!liveScopeCurrent(tpmsScope, effectiveCarId, generation)) return@launch
                val hasAmap = amapSettingsStore.currentKey().isNotBlank() || _uiState.value.isAmapConfigured
                // Only fresh, UID-qualified positions can be sent for geocoding.
                val resolvedAddress = if (cacheVerified && liveStatus != null)
                    loadCachedAddress(liveStatus, effectiveCarId) else null
                if (!liveScopeCurrent(tpmsScope, effectiveCarId, generation) ||
                    (cacheContext != null &&
                     !statusIdentityCurrent(tpmsScope, cacheContext, effectiveCarId, generation)))
                    return@launch
                _uiState.value = DashboardUiState(
                    isLoading = false,
                    car = car,
                    status = status,
                    error = if (status != null) null else primaryError?.message,
                    errorCode = if (status != null) null else primaryError?.code,
                    errorKind = if (status != null) null else primaryError?.kind,
                    snapshotSource = if (liveStatus != null) displayEvidence.source else "scoped_cached",
                    observedAt = if (liveStatus != null) displayEvidence.observedAt
                        else if (cacheVerified) vehicleStatusStore.getCachedObservedAt(tpmsScope!!, cacheContext!!) else null,
                    fieldSources = displayEvidence.fieldSources,
                    snapshotFreshness = if (liveStatus != null) displayEvidence.freshness
                        else if (cachedStatus != null) SnapshotFreshness.HISTORY else SnapshotFreshness.UNAVAILABLE,
                    snapshotMixedSources = displayEvidence.isMixed,
                    units = units,
                    cachedAddress = resolvedAddress,
                    customPhotoFile = vehiclePhotoStore.getCustomPhotoFile(effectiveCarId),
                    isAmapConfigured = hasAmap,
                    isHudDismissed = _uiState.value.isHudDismissed
                )

                launch {
                    val readinessResult = try {
                        repository.getDataReadiness(effectiveCarId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    val readiness = (readinessResult as? ApiResult.Success)?.data ?: return@launch
                    if (!liveScopeCurrent(tpmsScope, effectiveCarId, generation)) return@launch
                    _uiState.update { current ->
                        if (current.car?.carId != effectiveCarId) {
                            current
                        } else {
                            current.copy(
                                dataReadiness = readiness,
                                dataReadinessCarId = effectiveCarId,
                                showReadinessIntro = !dataReadinessStore.hasSeen(readiness, effectiveCarId)
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!liveScopeCurrent(captureTpmsScope(), settingsRepository.currentCarId.first(),
                        generation)) return@launch
                _uiState.value = DashboardUiState(
                    isLoading = false,
                    error = e.message,
                    errorKind = apiErrorKindFor(null, e.message),
                    isAmapConfigured = amapSettingsStore.currentKey().isNotBlank() || _uiState.value.isAmapConfigured,
                    isHudDismissed = _uiState.value.isHudDismissed
                )
            }
        }
    }

    /** Polling displays the request's raw status. Unlike the full /cars loader,
     * it has no fresh UID proof; never persist or geocode it under an old mapping.
     */
    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                val state = _uiState.value.status
                val speed = state?.speed ?: 0.0
                val delayMs = when {
                    state?.state?.lowercase() == "driving" || speed > 0.0 -> 8000L
                    state?.state?.lowercase() == "charging" -> 15000L
                    state?.state?.lowercase() == "asleep" -> 60000L
                    else -> 10000L
                }
                delay(delayMs)
                val generation = requestGeneration
                try {
                    val carId = settingsRepository.currentCarId.first()
                    val scope = captureTpmsScope()
                    if (!liveScopeCurrent(scope, carId, generation)) continue
                    val snapshot = repository.getAdapterSnapshot(carId)
                    val legacy = if (snapshot is ApiResult.Error) repository.getCarStatus(carId) else null
                    if (!liveScopeCurrent(scope, carId, generation)) continue
                    val raw = when {
                        snapshot is ApiResult.Success -> snapshot.data.status
                        legacy is ApiResult.Success -> legacy.data.status
                        else -> null
                    }
                    if (raw != null) {
                        val evidence = if (snapshot is ApiResult.Success) snapshotEvidence(
                            snapshot.data.source, snapshot.data.observedAt, snapshot.data.fieldSources)
                        else snapshotEvidence("teslamate_api", null, emptyMap())
                        if (!liveScopeCurrent(scope, carId, generation)) continue
                        _uiState.update { ui -> ui.copy(
                            status = raw, error = null, errorCode = null, errorKind = null,
                            snapshotSource = evidence.source, observedAt = evidence.observedAt,
                            fieldSources = evidence.fieldSources, snapshotFreshness = evidence.freshness,
                            snapshotMixedSources = evidence.isMixed,
                            units = when {
                                snapshot is ApiResult.Success -> snapshot.data.units
                                legacy is ApiResult.Success -> legacy.data.units
                                else -> ui.units
                            }, cachedAddress = null)
                        }
                    } else {
                        val failure = (legacy as? ApiResult.Error) ?: (snapshot as? ApiResult.Error)
                        if (!liveScopeCurrent(scope, carId, generation)) continue
                        _uiState.update { ui -> ui.copy(
                            snapshotFreshness = failedSnapshotFreshness(
                                ui.observedAt, ui.status != null, clock.instant()),
                            error = failure?.message, errorCode = failure?.code, errorKind = failure?.kind)
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    // Request generation alone is not sufficient after a new login.
                    if (generation == requestGeneration) {
                        val carId = settingsRepository.currentCarId.first()
                        val scope = captureTpmsScope()
                        if (!liveScopeCurrent(scope, carId, generation)) continue
                        _uiState.update { ui -> ui.copy(
                            snapshotFreshness = failedSnapshotFreshness(
                                ui.observedAt, ui.status != null, clock.instant()),
                            error = "status_poll_unavailable")
                        }
                    }
                }
            }
        }
    }

    fun saveCustomPhoto(inputStream: java.io.InputStream) {
        val carId = _uiState.value.car?.carId ?: return
        viewModelScope.launch {
            val file = vehiclePhotoStore.saveCustomPhoto(carId, inputStream)
            _uiState.update { it.copy(customPhotoFile = file) }
        }
    }

    fun clearCustomPhoto() {
        val carId = _uiState.value.car?.carId ?: return
        viewModelScope.launch {
            vehiclePhotoStore.clearCustomPhoto(carId)
            _uiState.update { it.copy(customPhotoFile = null) }
        }
    }

    fun switchCar(carId: Int) {
        viewModelScope.launch {
            settingsRepository.setCurrentCarId(carId)
            loadDashboard()
        }
    }

    fun refresh() {
        triggerDataSync()
        loadDashboard()
    }

    fun dismissReadinessIntro() {
        val readiness = _uiState.value.dataReadiness ?: return
        val carId = _uiState.value.dataReadinessCarId ?: return
        _uiState.update { it.copy(showReadinessIntro = false) }
        viewModelScope.launch {
            dataReadinessStore.markSeen(readiness, carId)
        }
    }

    private fun triggerDataSync() {
        val request = OneTimeWorkRequestBuilder<DataSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(DataSyncWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            DataSyncWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private suspend fun loadCachedAddress(status: CarStatus?, carId: Int): String? {
        val coordinates = usableVehicleCoordinates(status?.latitude, status?.longitude)
        if (coordinates != null) {
            val location = amapReverseGeocoder.reverse(coordinates.first, coordinates.second)
            if (location != null) {
                val addr = location.address?.trim()?.takeIf { it.isNotEmpty() }
                if (addr != null) return addr
                val fallback = listOfNotNull(location.city, location.regionName, location.countryName)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .joinToString(" ")
                    .takeIf { it.isNotEmpty() }
                if (fallback != null) return fallback
            }
            val cache = geocodingRepository.getFromCache(coordinates.first, coordinates.second)
            if (cache != null) {
                return listOfNotNull(cache.city, cache.regionName, cache.countryName)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .joinToString(" ")
                    .takeIf { it.isNotEmpty() }
            }
        }
        return null
    }
}

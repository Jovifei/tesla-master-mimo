package com.matelink.ui.screens.drives

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.Units
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.entity.SavedTripLeg
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.GeocodingRepository
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.VerifiedHistoryReadContext
import com.matelink.data.repository.HistoryDetailRepository
import com.matelink.data.repository.HistoryDetailPointSaveRejected
import com.matelink.data.repository.persistVerifiedDriveDetail
import com.matelink.data.repository.verifiedDetailIdentity
import com.matelink.data.repository.historyIdentityUnavailableError
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.repository.WeatherPoint
import com.matelink.data.repository.WeatherRepository
import com.matelink.domain.history.LatestHistoryLoad
import com.matelink.domain.LegRef
import com.matelink.domain.TripRepository
import com.matelink.domain.analytics.asCachedDetail
import com.matelink.domain.analytics.resolveDriveEnergy
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.model.Trip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

internal suspend fun optionalDetailIsCurrent(
    sameLoad: () -> Boolean,
    sameContext: suspend () -> Boolean
): Boolean {
    if (!sameLoad()) return false
    val current = sameContext()
    currentCoroutineContext().ensureActive()
    return current && sameLoad()
}

internal data class DriveCapacityMetadata(val reportedCapacity: Double?, val model: String?, val trim: String?)

/** A reused numeric car ID cannot qualify either its model or its battery-health value. */
internal fun VerifiedHistoryReadContext.capacityMetadata(
    cars: ApiResult<List<CarData>>?, health: ApiResult<BatteryHealth>?
): DriveCapacityMetadata? {
    val car = (cars as? ApiResult.Success)?.data?.filter { it.carId == context.remoteApiCarId }
        ?.singleOrNull()?.takeIf { matchesCar(it) } ?: return null
    val reported = (health as? ApiResult.Success)?.data?.currentCapacity?.takeIf { it > 0.0 && it.isFinite() }
    return DriveCapacityMetadata(reported, car.carDetails?.model, car.carDetails?.trimBadging)
}

data class DriveDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val localArchiveLinkPending: Boolean = false,
    val historySyncWarning: String? = null,
    val error: String? = null,
    val driveDetail: DriveDetail? = null,
    val units: Units? = null,
    val stats: DriveDetailStats? = null,
    val weatherPoints: List<WeatherPoint> = emptyList(),
    val isLoadingWeather: Boolean = false,
    val containingTrip: Pair<Long, Trip>? = null
)

data class DriveDetailStats(
    val speedMax: Int?, val speedAvg: Double?, val speedMin: Int?,
    val powerMax: Int?, val powerMin: Int?, val powerAvg: Double?,
    val elevationMax: Int?, val elevationMin: Int?, val elevationGain: Int?, val elevationLoss: Int?,
    val batteryStart: Int?, val batteryEnd: Int?, val batteryUsed: Int?,
    val energy: DriveDetailEnergyPresentation,
    val distance: Double?, val durationMin: Int?, val avgSpeedFromDistance: Double?,
    val outsideTempAvg: Double?, val insideTempAvg: Double?
)

@HiltViewModel
class DriveDetailViewModel @Inject constructor(
    private val repository: TeslamateRepository,
    private val driveSummaryDao: DriveSummaryDao,
    private val historyRepository: UnifiedHistoryRepository,
    private val weatherRepository: WeatherRepository,
    private val tripRepository: TripRepository,
    private val geocodingRepository: GeocodingRepository,
    private val statsDatabase: StatsDatabase,
    private val historyDetailStore: HistoryDetailRepository,
    private val vehicleContexts: VehicleContextRepository? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(DriveDetailUiState())
    val uiState: StateFlow<DriveDetailUiState> = _uiState.asStateFlow()
    private val latestLoad = LatestHistoryLoad()
    private var historyProof: VerifiedHistoryReadContext? = null
    private var carId: Int? = null
    private var driveId: Int? = null

    init {
        viewModelScope.launch {
            historyRepository.scopeChanges?.collect { next ->
                val proof = historyProof ?: return@collect
                if (next != proof.scope || vehicleContexts?.isHistoryContextCurrentNow(proof.scope, proof.context) == false) {
                    latestLoad.invalidate()
                    historyProof = null
                    _uiState.value = DriveDetailUiState(isLoading = false, error = historyIdentityUnavailableError().message)
                }
            }
        }
    }

    fun loadDriveDetail(carId: Int, driveId: Int) {
        val previous = historyProof
        val keep = this.carId == carId && this.driveId == driveId && previous != null &&
            vehicleContexts?.isHistoryContextCurrentNow(previous.scope, previous.context) == true
        this.carId = carId
        this.driveId = driveId
        if (!keep) historyProof = null
        _uiState.value = if (keep) _uiState.value.copy(isLoading = false, isRefreshing = true, isLoadingWeather = false, error = null) else DriveDetailUiState()
        latestLoad.launch(viewModelScope) {
            try {
                // Cache identity discovery uses only a previously verified local binding, never HTTP.
                val cachedProof = try { cachedReadProof(carId) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                ensureCurrent()
                if (cachedProof != null) {
                    suspend fun checkCached() {
                        ensureCurrent()
                        val current = historyRepository.isContextCurrent(cachedProof)
                        ensureCurrent()
                        if (!current || vehicleContexts?.isHistoryContextCurrentNow(cachedProof.scope, cachedProof.context) == false) {
                            historyProof = null
                            _uiState.value = DriveDetailUiState(isLoading = false, error = historyIdentityUnavailableError().message)
                            throw CancellationException("Cached drive identity changed")
                        }
                    }
                    checkCached()
                    historyProof = cachedProof
                    val cached = readCachedDetail(cachedProof, driveId, ::checkCached)
                    cached?.let { (detail, warning) ->
                        checkCached()
                        publishCached(detail, warning)
                    }
                    if (cached == null && keep) _uiState.value = DriveDetailUiState()
                }
                val identity = withTimeoutOrNull(20_000L) { historyRepository.resolveContext(carId) }
                    ?: ApiResult.Error("history_sync_timeout", 408)
                val resolved = when (val result = identity) {
                    is ApiResult.Error -> {
                        ensureCurrent()
                        if (result.message == com.matelink.data.repository.HISTORY_IDENTITY_UNAVAILABLE) {
                            historyProof = null
                            _uiState.value = DriveDetailUiState(isLoading = false, error = result.message)
                        } else _uiState.update { it.copy(isLoading = false, isRefreshing = false,
                            historySyncWarning = if (it.driveDetail != null) "history_cached" else it.historySyncWarning,
                            error = if (it.driveDetail == null) result.message else null) }
                        return@launch
                    }
                    is ApiResult.Success -> result.data
                }
                suspend fun checkContext() {
                    ensureCurrent()
                    val current = historyRepository.isContextCurrent(resolved)
                    ensureCurrent()
                    if (!current || vehicleContexts?.isHistoryContextCurrentNow(resolved.scope, resolved.context) == false) {
                        historyProof = null
                        _uiState.value = DriveDetailUiState(isLoading = false, error = historyIdentityUnavailableError().message)
                        throw CancellationException("History identity changed")
                    }
                }
                checkContext()
                if (cachedProof != null && (cachedProof.scope != resolved.scope || cachedProof.context != resolved.context))
                    _uiState.value = DriveDetailUiState()
                historyProof = resolved
                _uiState.update { it.copy(localArchiveLinkPending = resolved.localArchiveLinkPending) }
                val remote = if (resolved.remoteAuthorized) withTimeoutOrNull(20_000L) { repository.getDriveDetail(carId, driveId) }
                    ?: ApiResult.Error("history_sync_timeout", 408)
                    else resolved.identityError ?: historyIdentityUnavailableError()
                checkContext()
                val detail = when (remote) {
                    is ApiResult.Success -> remote.data
                    is ApiResult.Error -> {
                        val cached = readCachedDetail(resolved, driveId, ::checkContext)
                        if (cached == null) {
                            _uiState.value = DriveDetailUiState(isLoading = false, error = remote.message)
                            return@launch
                        }
                        _uiState.update { it.copy(historySyncWarning = cached.second) }
                        cached.first
                    }
                }
                require(detail.driveId == driveId) { "history_detail_id_mismatch" }
                val proposedEnergy = detail.resolveDriveEnergy()
                val resolvedEnergy = if (remote is ApiResult.Success) {
                    var pointWriteFailed = false
                    val persisted = try {
                        persistVerifiedDriveDetail(statsDatabase, historyDetailStore,
                            resolved.context, detail, proposedEnergy) { checkContext() }
                    } catch (_: HistoryDetailPointSaveRejected) {
                        pointWriteFailed = true
                        null
                    }
                    checkContext()
                    if (persisted == null && !pointWriteFailed) {
                        _uiState.update { it.copy(isLoading = false, isRefreshing = false,
                            historySyncWarning = "history_detail_waiting_for_summary") }
                        return@launch
                    }
                    val receipt = persisted?.receipt
                    _uiState.update { it.copy(historySyncWarning = if (pointWriteFailed)
                        "history_detail_points_missing" else when {
                        receipt == null -> "history_detail_points_missing"
                        receipt.sourceInstanceVerified != true || receipt?.sourceIdVerified != true ->
                            "history_detail_source_unverified"
                        receipt?.providerTotalVerified != true -> "history_detail_total_unverified"
                        else -> null
                    }) }
                    if (pointWriteFailed) proposedEnergy else persisted!!.summary.toAnalysisDriveData()
                        .asCachedDetail().copy(positions = detail.positions).resolveDriveEnergy()
                } else proposedEnergy
                val energy = resolvedEnergy.estimate
                val stats = calculateDriveDetailStats(detail, presentDriveDetailEnergy(
                    energy.energyKwh, energy.efficiencyWhKm, energy.source.name.lowercase(),
                    energy.coverageSeconds, energy.coverageRatio, resolvedEnergy.evidence))
                checkContext()
                _uiState.update { it.copy(isLoading = false, isRefreshing = false, driveDetail = detail, stats = stats, error = null) }
                // Optional metadata, units and geocoding cannot delay energy publication.
                loadOptionalMetadata(detail, resolved)
                loadWeatherData(detail, resolved)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ensureCurrent()
                val proof = historyProof
                if (proof != null && vehicleContexts?.isHistoryContextCurrentNow(proof.scope, proof.context) == false) {
                    historyProof = null
                    _uiState.value = DriveDetailUiState(isLoading = false, error = historyIdentityUnavailableError().message)
                } else _uiState.update { it.copy(isLoading = false, isRefreshing = false,
                    historySyncWarning = if (it.driveDetail != null) "history_cached" else it.historySyncWarning,
                    error = if (it.driveDetail == null) "history_read_failed" else null) }
            }
        }
    }

    private suspend fun cachedReadProof(remoteId: Int): VerifiedHistoryReadContext? {
        val contexts = vehicleContexts
        if (contexts != null) {
            val scope = contexts.captureReadScope()
            val context = contexts.cachedContextForRemote(remoteId, scope) ?: return null
            if (!contexts.isHistoryContextCurrentNow(scope, context)) return null
            return VerifiedHistoryReadContext(scope, context, false, null, false)
        }
        return when (val result = historyRepository.loadCached(remoteId)) {
            is ApiResult.Success -> result.data.verifiedReadContext
            is ApiResult.Error -> null
        }
    }

    private suspend fun readCachedDetail(
        proof: VerifiedHistoryReadContext, id: Int, check: suspend () -> Unit
    ): Pair<DriveDetail, String>? {
        val cached = driveSummaryDao.get(proof.context.localHistoryCarId, id) ?: return null
        check()
        val points = try { cached.verifiedDetailIdentity(proof.context)?.let { historyDetailStore.readDrive(it) } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        check()
        val summary = cached.toAnalysisDriveData().asCachedDetail()
        val header = points?.detail
        val sameLength = header == null || com.matelink.data.api.models.sameDriveLengthUnit(summary.lengthUnit, header.lengthUnit)
        // The complete point header is one received record, including alias spellings and
        // its own units. A sparse list projection must not erase its richer parameters.
        val detail = header?.copy(
            startAddress = summary.startAddress?.takeIf(String::isNotBlank) ?: header.startAddress,
            endAddress = summary.endAddress?.takeIf(String::isNotBlank) ?: header.endAddress,
            durationMin = summary.durationMin ?: header.durationMin,
            durationStr = summary.durationStr ?: header.durationStr,
            odometerDetails = if (sameLength) summary.odometerDetails ?: header.odometerDetails else header.odometerDetails,
            speedMax = if (sameLength) summary.speedMax ?: header.speedMax else header.speedMax,
            speedAvg = if (sameLength) summary.speedAvg ?: header.speedAvg else header.speedAvg,
            speedMetricsSource = if (sameLength && summary.speedAvg != null) summary.speedMetricsSource else header.speedMetricsSource,
            speedSampleCount = if (sameLength && summary.speedAvg != null) summary.speedSampleCount else header.speedSampleCount,
            powerMax = summary.powerMax ?: header.powerMax, powerMin = summary.powerMin ?: header.powerMin,
            // A current explicit unknown energy contract still revokes an older scalar.
            energyContract = summary.energyContract, energyConsumedNet = summary.energyConsumedNet,
            consumptionNet = summary.consumptionNet.takeIf { sameLength }
        ) ?: summary
        val warning = when {
            points?.reason == "detail_cache_corrupt" -> "history_detail_cache_corrupt"
            header == null -> "history_cached"
            points?.receipt?.sourceInstanceVerified != true || points?.receipt?.sourceIdVerified != true -> "history_detail_cached_source_unverified"
            points?.receipt?.providerTotalVerified != true -> "history_detail_cached_total_unverified"
            else -> "history_cached"
        }
        return detail to warning
    }

    private fun publishCached(detail: DriveDetail, warning: String) {
        val resolved = detail.resolveDriveEnergy()
        val energy = resolved.estimate
        val stats = calculateDriveDetailStats(detail, presentDriveDetailEnergy(energy.energyKwh,
            energy.efficiencyWhKm, energy.source.name.lowercase(), energy.coverageSeconds, energy.coverageRatio, resolved.evidence))
        _uiState.update { it.copy(isLoading = false, isRefreshing = true, driveDetail = detail,
            stats = stats, historySyncWarning = warning, error = null) }
    }

    private fun loadOptionalMetadata(detail: DriveDetail, proof: VerifiedHistoryReadContext) {
        viewModelScope.launch {
            suspend fun isCurrent() = optionalDetailIsCurrent({ historyProof === proof },
                { historyRepository.isContextCurrent(proof) })
            try {
                val containing = withTimeoutOrNull(2_000) {
                    tripRepository.findTripContaining(proof.context.localHistoryCarId, SavedTripLeg.TYPE_DRIVE, detail.driveId)
                }
                if (!isCurrent()) return@launch
                _uiState.update { it.copy(containingTrip = containing) }
                if (proof.remoteAuthorized) {
                    val status = withTimeoutOrNull(2_000) { repository.getCarStatus(proof.context.remoteApiCarId) }
                    if (!isCurrent()) return@launch
                    if (status is ApiResult.Success) _uiState.update { it.copy(units = status.data.units) }

                    val health = withTimeoutOrNull(2_000) { repository.getBatteryHealth(proof.context.remoteApiCarId) }
                    val cars = withTimeoutOrNull(2_000) { repository.getCars() }
                    if (!isCurrent()) return@launch
                    val metadata = proof.capacityMetadata(cars, health)
                    if (metadata != null && (metadata.reportedCapacity != null || metadata.model != null)) {
                        val refinedEnergy = detail.resolveDriveEnergy(
                            batteryCapacityKwh = metadata.reportedCapacity,
                            model = metadata.model,
                            trimBadging = metadata.trim,
                            capacityBasis = metadata.reportedCapacity?.let { "api_reported_current_capacity" }
                        )
                        val saved = driveSummaryDao.refineResolvedCapacity(proof.context.localHistoryCarId, detail, refinedEnergy) {
                            if (!isCurrent())
                                throw CancellationException("History identity changed")
                        }
                        if (!isCurrent() || saved == null) return@launch
                        val latestDetail = saved.toAnalysisDriveData().asCachedDetail().copy(positions = detail.positions)
                        val published = latestDetail.resolveDriveEnergy(
                            batteryCapacityKwh = metadata.reportedCapacity,
                            model = metadata.model,
                            trimBadging = metadata.trim,
                            capacityBasis = metadata.reportedCapacity?.let { "api_reported_current_capacity" }
                        )
                        val e = published.estimate
                        val currentDetail = detail.copy(batteryDetails = latestDetail.batteryDetails,
                            odometerDetails = latestDetail.odometerDetails, energyContract = latestDetail.energyContract,
                            energyConsumedNet = latestDetail.energyConsumedNet, consumptionNet = latestDetail.consumptionNet)
                        val refinedStats = calculateDriveDetailStats(currentDetail, presentDriveDetailEnergy(
                            e.energyKwh, e.efficiencyWhKm, e.source.name.lowercase(),
                            e.coverageSeconds, e.coverageRatio, published.evidence))
                        _uiState.update { it.copy(stats = refinedStats, driveDetail = currentDetail) }
                    }
                }
                val enriched = withTimeoutOrNull(3_000) { enrichAddresses(detail) } ?: return@launch
                if (!isCurrent()) return@launch
                _uiState.update { state -> state.copy(driveDetail = state.driveDetail?.copy(
                    startAddress = enriched.startAddress, endAddress = enriched.endAddress)) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (isCurrent())
                    _uiState.update { it.copy(historySyncWarning = "history_energy_cache_failed") }
            }
        }
    }

    private suspend fun enrichAddresses(detail: DriveDetail): DriveDetail {
        suspend fun address(latitude: Double?, longitude: Double?, existing: String?): String? {
            if (!existing.isNullOrBlank()) return existing
            if (latitude == null || longitude == null) return null
            return geocodingRepository.reverseGeocode(latitude, longitude)
        }
        val first = com.matelink.domain.map.driveBoundaryCoordinates(detail, true)
        val last = com.matelink.domain.map.driveBoundaryCoordinates(detail, false)
        return detail.copy(
            startAddress = address(first?.first, first?.second, detail.startAddress),
            endAddress = address(last?.first, last?.second, detail.endAddress)
        )
    }

    private fun loadWeatherData(detail: DriveDetail, proof: VerifiedHistoryReadContext) {
        val positions = detail.positions
        val distance = detail.distanceKm
        if (positions.isNullOrEmpty() || distance == null || !distance.isFinite() || distance <= 0) return
        viewModelScope.launch {
            suspend fun isCurrent() = optionalDetailIsCurrent({ historyProof === proof },
                { historyRepository.isContextCurrent(proof) })
            if (!isCurrent()) return@launch
            _uiState.update { it.copy(isLoadingWeather = true) }
            try {
                val points = weatherRepository.getWeatherAlongDrive(positions = positions, totalDistanceKm = distance)
                if (!isCurrent()) return@launch
                _uiState.update { it.copy(weatherPoints = points, isLoadingWeather = false) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (!isCurrent()) return@launch
                _uiState.update { it.copy(isLoadingWeather = false) }
            }
        }
    }

    fun clearError() { _uiState.update { it.copy(error = null) } }

    fun removeFromTrip() {
        val proof = historyProof ?: return
        val tripId = _uiState.value.containingTrip?.first ?: return
        val drive = driveId ?: return
        viewModelScope.launch {
            if (historyProof !== proof || !historyRepository.isContextCurrent(proof)) return@launch
            tripRepository.removeLegFromTrip(tripId, LegRef(SavedTripLeg.TYPE_DRIVE, drive))
            if (historyProof !== proof || !historyRepository.isContextCurrent(proof)) return@launch
            _uiState.update { it.copy(containingTrip = null) }
        }
    }
}

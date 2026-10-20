package com.matelink.ui.screens.drives

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.Units
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.StatsDatabase
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
    private val historyDetailStore: HistoryDetailRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(DriveDetailUiState())
    val uiState: StateFlow<DriveDetailUiState> = _uiState.asStateFlow()
    private val latestLoad = LatestHistoryLoad()
    private var historyProof: VerifiedHistoryReadContext? = null
    private var carId: Int? = null
    private var driveId: Int? = null

    fun loadDriveDetail(carId: Int, driveId: Int) {
        this.carId = carId
        this.driveId = driveId
        historyProof = null
        _uiState.value = DriveDetailUiState()
        latestLoad.launch(viewModelScope) {
            try {
                val resolved = when (val result = historyRepository.resolveContext(carId)) {
                    is ApiResult.Error -> {
                        ensureCurrent()
                        _uiState.update { it.copy(isLoading = false, error = result.message) }
                        return@launch
                    }
                    is ApiResult.Success -> result.data
                }
                suspend fun checkContext() {
                    ensureCurrent()
                    val current = historyRepository.isContextCurrent(resolved)
                    ensureCurrent()
                    if (!current) {
                        historyProof = null
                        _uiState.value = DriveDetailUiState(isLoading = false, error = historyIdentityUnavailableError().message)
                        throw CancellationException("History identity changed")
                    }
                }
                checkContext()
                historyProof = resolved
                _uiState.update { it.copy(localArchiveLinkPending = resolved.localArchiveLinkPending) }
                val remote = if (resolved.remoteAuthorized) repository.getDriveDetail(carId, driveId)
                    else resolved.identityError ?: historyIdentityUnavailableError()
                checkContext()
                val detail = when (remote) {
                    is ApiResult.Success -> remote.data
                    is ApiResult.Error -> {
                        val cached = driveSummaryDao.get(resolved.context.localHistoryCarId, driveId)
                        checkContext()
                        if (cached == null) {
                            _uiState.update { it.copy(isLoading = false, error = remote.message) }
                            return@launch
                        }
                        val pointCache = try {
                            cached.verifiedDetailIdentity(resolved.context)
                                ?.let { historyDetailStore.readDrive(it) }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                        checkContext()
                        _uiState.update { it.copy(historySyncWarning = when {
                            pointCache?.reason == "detail_cache_corrupt" ->
                                "history_detail_cache_corrupt"
                            pointCache?.detail == null -> "history_cached"
                            pointCache?.receipt?.sourceInstanceVerified != true ||
                                pointCache?.receipt?.sourceIdVerified != true ->
                                "history_detail_cached_source_unverified"
                            pointCache?.receipt?.providerTotalVerified != true ->
                                "history_detail_cached_total_unverified"
                            else -> "history_cached"
                        }) }
                        val summaryDetail = cached.toAnalysisDriveData().asCachedDetail()
                        val header = pointCache?.detail ?: summaryDetail
                        summaryDetail.copy(
                            positions = pointCache?.detail?.positions,
                            sourceInstanceId = header.sourceInstanceId,
                            sourceId = header.sourceId,
                            sourceRecordId = header.sourceRecordId,
                            lengthUnit = pointCache?.detail?.lengthUnit ?: summaryDetail.lengthUnit)
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
                        _uiState.update { it.copy(isLoading = false,
                            historySyncWarning = "history_detail_waiting_for_summary") }
                        return@launch
                    }
                    val receipt = persisted?.receipt
                    _uiState.update { it.copy(historySyncWarning = if (pointWriteFailed)
                        "history_detail_points_missing" else when {
                        receipt?.sourceInstanceVerified != true || receipt?.sourceIdVerified != true ->
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
                _uiState.update { it.copy(isLoading = false, driveDetail = detail, stats = stats, error = null) }
                // Optional metadata, units and geocoding cannot delay energy publication.
                loadOptionalMetadata(detail, resolved)
                loadWeatherData(detail, resolved)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ensureCurrent()
                _uiState.update { it.copy(isLoading = false, error = "history_read_failed") }
            }
        }
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
        val positions = detail.positions.orEmpty()
        val first = positions.firstOrNull { it.latitude != null && it.longitude != null }
        val last = positions.lastOrNull { it.latitude != null && it.longitude != null }
        return detail.copy(
            startAddress = address(detail.startLatitude ?: first?.latitude, detail.startLongitude ?: first?.longitude, detail.startAddress),
            endAddress = address(detail.endLatitude ?: last?.latitude, detail.endLongitude ?: last?.longitude, detail.endAddress)
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

package com.matelink.ui.screens.trips

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.Units
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.dao.GeocodeCacheDao
import com.matelink.data.local.dao.TripCountryCacheDao
import com.matelink.data.local.dao.TripRouteCacheDao
import com.matelink.data.local.entity.TripCountryCache
import com.matelink.data.local.entity.TripRouteCache
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.model.Currency
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.data.repository.TripWeatherPoint
import com.matelink.data.repository.WeatherRepository
import com.matelink.data.repository.countryCodeToFlag
import com.matelink.domain.EligibleLegs
import com.matelink.domain.LegRef
import com.matelink.domain.RouteSimplifier
import com.matelink.domain.TripRepository
import com.matelink.domain.analytics.chargeTotalOverrideKey
import com.matelink.domain.analytics.observedCostSumOrNull
import com.matelink.domain.analytics.validManualChargeTotal
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.toRawAnalysisChargeData
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.model.Trip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.ByteBuffer
import com.matelink.util.parseIsoInstant
import javax.inject.Inject

@Immutable
data class TripRoutePoint(
    val latitude: Double,
    val longitude: Double
)

@Immutable
data class TripMapMarker(
    val latitude: Double,
    val longitude: Double,
    val type: TripMapPointType,
    val label: String,
    val chargeId: Int? = null
)

enum class TripMapPointType { START, CHARGE, END }

/** One drive leg's GPS points, kept separate for alternating colors on the map. */
@Immutable
data class TripRouteSegment(
    val points: List<TripRoutePoint>
)

@Immutable
data class TripCountry(
    val countryCode: String,
    val flagEmoji: String
)

data class TripDetailUiState(
    val isLoading: Boolean = true,
    val trip: Trip? = null,
    val routeSegments: List<TripRouteSegment> = emptyList(),
    val markers: List<TripMapMarker> = emptyList(),
    val isMapLoading: Boolean = true,
    val routeDeferredByBudget: Boolean = false,
    val countries: List<TripCountry> = emptyList(),
    val units: Units? = null,
    val currencySymbol: String = Currency.CNY.symbol,
    val chargeCosts: TripChargeCostProjection? = null,

    // Edit / merge / delete (PR 2)
    val savedTripId: Long? = null,
    val savedTripName: String? = null,
    val dcChargeIds: Set<Int> = emptySet(),
    val showAddLegSheet: Boolean = false,
    val showMergeSheet: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val showRenameDialog: Boolean = false,
    val renameDraft: String = "",
    val eligibleLegs: EligibleLegs? = null,
    val adjacentTrips: List<Pair<Long, Trip>> = emptyList(),
    val pendingMergeTarget: Pair<Long, Trip>? = null,
    val justDeleted: Boolean = false,

    val weatherPoints: List<TripWeatherPoint> = emptyList()
)

data class PricedTripCharge(val charge: ChargeSummary, val cost: Double, val energyKwh: Double?)

data class TripChargeCostProjection(
    val pricedCharges: List<PricedTripCharge>,
    val totalCost: Double?,
    val pairedUnitCost: Double?,
    val allPriced: Boolean,
    val energyCount: Int,
    val pairedCount: Int
)

/** Reuses only rows from the verified local vehicle; raw Room/API receipts remain unchanged. */
internal fun projectTripChargeCosts(
    trip: Trip, localHistoryCarId: Int, manualTotals: Map<String, Double>
): TripChargeCostProjection? {
    if (trip.drives.isEmpty() || trip.drives.any { it.carId != localHistoryCarId } ||
        trip.charges.any { it.carId != localHistoryCarId }) return null
    val energyById = trip.charges.associate { charge ->
        charge.chargeId to qualifiedTripChargeEnergy(charge)
    }
    val priced = trip.charges.mapNotNull { charge ->
        val cost = validManualChargeTotal(
            manualTotals[chargeTotalOverrideKey(localHistoryCarId, charge.chargeId)])
            ?: charge.cost?.takeIf { it.isFinite() && it >= 0.0 }
        cost?.let { PricedTripCharge(charge, it, energyById[charge.chargeId]) }
    }
    val paired = priced.mapNotNull { item ->
        item.energyKwh
            ?.takeIf { it.isFinite() && it > 0.0 }?.let { item.cost to it }
    }
    val pairedEnergy = paired.sumOf { it.second }
    val pairedUnitCost = if (pairedEnergy.isFinite() && pairedEnergy > 0.0)
        (paired.sumOf { it.first } / pairedEnergy).takeIf { it.isFinite() && it >= 0.0 }
    else null
    return TripChargeCostProjection(priced, observedCostSumOrNull(priced.map { it.cost }),
        pairedUnitCost, trip.charges.isNotEmpty() && priced.size == trip.charges.size,
        energyById.values.count { it != null }, paired.size)
}

private fun qualifiedTripChargeEnergy(charge: ChargeSummary): Double? {
    val analysis = charge.toAnalysisChargeData()
    val energy = analysis.batteryInputKwh?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    val raw = charge.toRawAnalysisChargeData()
    val sameWindow = parseIsoInstant(raw.startDate)?.let { it == parseIsoInstant(charge.startDate) } == true &&
        parseIsoInstant(raw.endDate)?.let { it == parseIsoInstant(charge.endDate) } == true
    val archiveReceipt = raw.source == "teslamate_archive" && sameWindow
    val scopedDetail = HistorySummaryEvidenceCodec.chargePresentation(charge.apiEvidence,
        charge.carId, charge.chargeId, raw.source, charge.startDate, charge.endDate)
        ?.source == "teslamate_archive"
    val qualifiedContract = analysis.energyContract?.batteryInput?.source != null
    return energy.takeIf { archiveReceipt || scopedDetail || qualifiedContract }
}

internal const val MAX_AUTO_TRIP_ROUTE_DRIVES = 12

/** Child requests belong to the current load; scope is checked at each request admission. */
internal suspend fun <T : Any> fetchBoundedTripRoute(
    driveIds: List<Int>, isCurrent: () -> Boolean, fetch: suspend (Int) -> T?
): List<T> {
    if (driveIds.size > MAX_AUTO_TRIP_ROUTE_DRIVES || !isCurrent()) return emptyList()
    return coroutineScope {
        val gate = Semaphore(2)
        driveIds.map { id ->
            async {
                gate.withPermit {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent()) null else fetch(id)?.takeIf { isCurrent() }
                }
            }
        }.awaitAll().filterNotNull()
    }
}

/** Clear already rendered trip data as soon as the authenticated history scope changes. */
internal suspend fun <T> observeTripScopeInvalidation(
    changes: Flow<T?>, isCurrent: () -> Boolean, invalidate: () -> Unit,
    onAvailable: (T) -> Unit = {}
) {
    changes.collect { scope ->
        if (!isCurrent()) invalidate()
        else if (scope != null) onAvailable(scope)
    }
}

@HiltViewModel
class TripDetailViewModel @Inject constructor(
    private val aggregateDao: AggregateDao,
    private val geocodeCacheDao: GeocodeCacheDao,
    private val tripRouteCacheDao: TripRouteCacheDao,
    private val tripCountryCacheDao: TripCountryCacheDao,
    private val tripRepository: TripRepository,
    private val tripCache: TripCache,
    private val repository: TeslamateRepository,
    private val weatherRepository: WeatherRepository,
    private val settingsDataStore: SettingsDataStore,
    private val vehicleContextRepository: VehicleContextRepository,
    private val chargeCostOverrideStore: ChargeCostOverrideStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripDetailUiState())
    val uiState: StateFlow<TripDetailUiState> = _uiState.asStateFlow()

    private var loaded = false
    private var currentCarId: Int = -1
    private var currentStartDate: String = ""
    private var hasBeenPaused = false
    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var historyScope: HistoryReadScope? = null
    private var historyContext: VehicleContext? = null
    private var pendingHistoryReload = false

    init {
        viewModelScope.launch {
            observeTripScopeInvalidation(vehicleContextRepository.historyScopeChanges,
                isCurrent = {
                    val scope = historyScope
                    val context = historyContext
                    scope == null || context == null ||
                        vehicleContextRepository.isHistoryContextCurrentNow(scope, context)
                }, invalidate = ::invalidateHistory,
                onAvailable = {
                    if (pendingHistoryReload && loadJob?.isActive != true && currentCarId > 0 &&
                        currentStartDate.isNotBlank()) loadTrip(currentCarId, currentStartDate)
                })
        }
    }

    private fun invalidateHistory() {
        val remoteCarId = currentCarId
        val startDate = currentStartDate
        ++loadGeneration
        loadJob?.cancel()
        loadJob = null
        historyScope = null
        historyContext = null
        pendingHistoryReload = true
        loaded = false
        _uiState.value = TripDetailUiState(isLoading = false, isMapLoading = false)
        if (remoteCarId > 0 && startDate.isNotBlank()) loadTrip(remoteCarId, startDate)
    }

    private fun isCurrent(generation: Long, scope: HistoryReadScope, context: VehicleContext): Boolean =
        generation == loadGeneration && currentCarId == context.remoteApiCarId &&
            vehicleContextRepository.isHistoryContextCurrentNow(scope, context)

    private fun currentLocalCarId(): Int? {
        val scope = historyScope ?: return null
        val context = historyContext ?: return null
        return context.localHistoryCarId.takeIf {
            vehicleContextRepository.isHistoryContextCurrentNow(scope, context)
        }
    }

    private fun checkedLocalCarId(): Int? = currentLocalCarId().also { id ->
        if (id == null && historyContext != null) invalidateHistory()
    }

    fun loadTrip(carId: Int, tripStartDate: String) {
        if (loaded && currentCarId == carId && currentStartDate == tripStartDate &&
            currentLocalCarId() != null) return
        if (currentCarId != carId || currentStartDate != tripStartDate || currentLocalCarId() == null) {
            _uiState.value = TripDetailUiState()
        }
        loaded = true
        currentCarId = carId
        currentStartDate = tripStartDate
        val generation = ++loadGeneration

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val scope = try { vehicleContextRepository.captureReadScope() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (generation == loadGeneration) {
                    pendingHistoryReload = true
                    _uiState.update { it.copy(isLoading = false) }
                }
                return@launch
            }
            val context = vehicleContextRepository.cachedContextForRemote(carId, scope)
            if (context == null || !isCurrent(generation, scope, context)) {
                if (generation == loadGeneration) {
                    pendingHistoryReload = true
                    _uiState.update { it.copy(isLoading = false) }
                }
                return@launch
            }
            historyScope = scope
            historyContext = context
            pendingHistoryReload = false
            // Skip these on revalidation — units and currency don't change between same-session
            // navigations, and the network call was the slowest step of the reload.
            val isFirstLoad = _uiState.value.units == null
            if (isFirstLoad) {
                launch {
                    when (val result = repository.getCarStatus(carId)) {
                        is ApiResult.Success -> if (isCurrent(generation, scope, context))
                            _uiState.update { it.copy(units = result.data.units) }
                        is ApiResult.Error -> {}
                    }
                }
                launch {
                    val settings = settingsDataStore.settings.first()
                    val symbol = Currency.findByCode(settings.currencyCode).symbol
                    if (isCurrent(generation, scope, context))
                        _uiState.update { it.copy(currencySymbol = symbol) }
                }
            }

            // Fast path: use cached trip from list screen (avoids a repository round trip)
            val manualTotals = chargeCostOverrideStore.getAll()
            if (!isCurrent(generation, scope, context)) return@launch
            val localCarId = context.localHistoryCarId
            val cached = tripCache.take(tripStartDate)
                ?.takeIf { projectTripChargeCosts(it, localCarId, manualTotals) != null }
            val trip = cached ?: tripRepository.getSavedTripsReadOnly(localCarId)
                .find { it.startDate == tripStartDate &&
                    projectTripChargeCosts(it, localCarId, manualTotals) != null }
            if (!isCurrent(generation, scope, context)) return@launch
            if (trip == null) {
                _uiState.update { it.copy(isLoading = false, isMapLoading = false) }
                return@launch
            }
            val costs = projectTripChargeCosts(trip, localCarId, manualTotals) ?: return@launch

            // Lookup savedTripId (for edit/merge/delete actions) and DC charge id set (for AC/DC visuals)
            val savedId = tripRepository.findSavedTripId(localCarId, trip)
            val savedName = savedId?.let { tripRepository.getTripName(it) }
            val dcIds = try {
                aggregateDao.getDcChargeIds(localCarId).toSet()
            } catch (e: Exception) { emptySet() }
            if (!isCurrent(generation, scope, context)) return@launch

            // Placeholder charge markers — only on first load. On revalidation, keep the existing
            // markers (which already include start+end) so the map doesn't flash a shorter set.
            val existingMarkers = _uiState.value.markers
            val initialMarkers = if (existingMarkers.isEmpty()) {
                trip.charges.map { charge ->
                    TripMapMarker(
                        charge.latitude, charge.longitude,
                        TripMapPointType.CHARGE, charge.address,
                        chargeId = charge.chargeId
                    )
                }
            } else existingMarkers

            // Show trip info immediately — countries and map load in background
            _uiState.update {
                it.copy(
                    isLoading = false,
                    trip = trip,
                    markers = initialMarkers,
                    savedTripId = savedId,
                    savedTripName = savedName,
                    dcChargeIds = dcIds,
                    chargeCosts = costs
                )
            }

            // Resolve countries — try cache first, resolve in background if miss
            launch {
                val tripKey = computeTripKey(trip, localCarId)
                val cached = tripCountryCacheDao.get(tripKey)
                val countries = if (cached != null) {
                    cached.countries.split(",")
                        .map { TripCountry(it, countryCodeToFlag(it)) }
                } else {
                    val resolved = resolveCountriesFromDriveEdges(trip, localCarId, carId) {
                        isCurrent(generation, scope, context)
                    }
                    if (resolved.isNotEmpty() && isCurrent(generation, scope, context)) {
                        tripCountryCacheDao.insert(
                            TripCountryCache(
                                tripKey = tripKey,
                                countries = resolved.joinToString(",") { it.countryCode },
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    }
                    resolved
                }
                if (isCurrent(generation, scope, context))
                    _uiState.update { it.copy(countries = countries) }
            }

            // Fetch GPS positions for all drives in parallel (for the map)
            loadRoutePositions(carId, trip, localCarId, generation, scope, context)
        }
    }

    // === Edit / merge / delete actions (PR 2) ===

    fun openAddLegSheet() {
        val tripId = _uiState.value.savedTripId ?: return
        val localCarId = checkedLocalCarId() ?: return
        viewModelScope.launch {
            val eligible = tripRepository.getEligibleNewLegs(tripId, localCarId)
            if (currentLocalCarId() == localCarId)
                _uiState.update { it.copy(showAddLegSheet = true, eligibleLegs = eligible) }
        }
    }

    fun closeAddLegSheet() {
        _uiState.update { it.copy(showAddLegSheet = false, eligibleLegs = null) }
    }

    fun pickLegs(refs: List<LegRef>) {
        if (refs.isEmpty()) return
        val tripId = _uiState.value.savedTripId ?: return
        val localCarId = checkedLocalCarId() ?: return
        viewModelScope.launch {
            if (currentLocalCarId() != localCarId) return@launch
            tripRepository.extendTripWithLegs(tripId, refs)
            if (currentLocalCarId() != localCarId) return@launch
            _uiState.update { it.copy(showAddLegSheet = false, eligibleLegs = null) }
            reloadTrip()
        }
    }

    fun openMergeSheet() {
        val tripId = _uiState.value.savedTripId ?: return
        val localCarId = checkedLocalCarId() ?: return
        viewModelScope.launch {
            val adjacent = tripRepository.getAdjacentTrips(tripId, localCarId)
            if (currentLocalCarId() == localCarId)
                _uiState.update { it.copy(showMergeSheet = true, adjacentTrips = adjacent) }
        }
    }

    fun closeMergeSheet() {
        _uiState.update { it.copy(showMergeSheet = false, adjacentTrips = emptyList()) }
    }

    fun pickMergeTarget(otherId: Long, otherTrip: Trip) {
        if (checkedLocalCarId() == null) return
        _uiState.update {
            it.copy(showMergeSheet = false, pendingMergeTarget = otherId to otherTrip)
        }
    }

    fun cancelMergeTarget() {
        _uiState.update { it.copy(pendingMergeTarget = null) }
    }

    fun confirmMergeTarget() {
        val keptId = _uiState.value.savedTripId ?: return
        val (otherId, _) = _uiState.value.pendingMergeTarget ?: return
        val localCarId = checkedLocalCarId() ?: return
        viewModelScope.launch {
            if (currentLocalCarId() != localCarId) return@launch
            val newId = tripRepository.mergeTrips(keptId, otherId, localCarId) ?: return@launch
            if (currentLocalCarId() != localCarId) return@launch
            // The merged trip's startDate equals the earliest source's startDate.
            // Re-derive currentStartDate so reloadTrip finds the merged trip.
            val all = tripRepository.getSavedTripsReadOnly(localCarId)
            val merged = all.find { trip ->
                tripRepository.findSavedTripId(localCarId, trip) == newId
            } ?: return@launch
            if (currentLocalCarId() != localCarId) return@launch
            currentStartDate = merged.startDate
            _uiState.update { it.copy(pendingMergeTarget = null, adjacentTrips = emptyList()) }
            reloadTrip()
        }
    }

    fun openRenameDialog() {
        if (checkedLocalCarId() == null) return
        _uiState.update {
            it.copy(
                showRenameDialog = true,
                renameDraft = it.savedTripName.orEmpty()
            )
        }
    }

    fun closeRenameDialog() {
        _uiState.update { it.copy(showRenameDialog = false, renameDraft = "") }
    }

    fun updateRenameDraft(draft: String) {
        if (checkedLocalCarId() == null) return
        _uiState.update { it.copy(renameDraft = draft) }
    }

    fun confirmRename() {
        val tripId = _uiState.value.savedTripId ?: return
        val localCarId = checkedLocalCarId() ?: return
        val draft = _uiState.value.renameDraft
        viewModelScope.launch {
            if (currentLocalCarId() != localCarId) return@launch
            tripRepository.renameTrip(tripId, draft)
            val updated = tripRepository.getTripName(tripId)
            if (currentLocalCarId() == localCarId)
                _uiState.update { it.copy(showRenameDialog = false, savedTripName = updated, renameDraft = "") }
        }
    }

    fun openDeleteConfirm() {
        if (checkedLocalCarId() == null) return
        _uiState.update { it.copy(showDeleteConfirm = true) }
    }

    fun closeDeleteConfirm() {
        _uiState.update { it.copy(showDeleteConfirm = false) }
    }

    fun confirmDelete() {
        val tripId = _uiState.value.savedTripId ?: return
        val localCarId = checkedLocalCarId() ?: return
        viewModelScope.launch {
            if (currentLocalCarId() != localCarId) return@launch
            tripRepository.deleteTrip(tripId)
            if (currentLocalCarId() == localCarId)
                _uiState.update { it.copy(showDeleteConfirm = false, justDeleted = true) }
        }
    }

    /** Called by the screen on ON_PAUSE so we know the user navigated away. */
    fun onScreenPaused() {
        hasBeenPaused = true
    }

    /**
     * Called by the screen on ON_RESUME. Reloads the trip if the user is returning from a child
     * screen (e.g. a drive/charge detail) where they might have changed the trip's composition.
     *
     * The reload is delayed a short while so the fresh composition that follows back-navigation
     * can finish its first frame (heavy children like the map/timeline/legs/donut). Without this
     * delay the reload's state emissions pile onto the same main-thread budget, which shows up
     * as "scroll is blocked for a while".
     */
    fun onScreenResumed() {
        if (historyContext != null && checkedLocalCarId() == null) {
            hasBeenPaused = false
            return
        }
        if (loaded && hasBeenPaused) {
            hasBeenPaused = false
            viewModelScope.launch {
                kotlinx.coroutines.delay(400)
                reloadTrip()
            }
        }
    }

    /**
     * Revalidate the trip in the background. We deliberately do NOT clear existing state — the
     * user keeps seeing the current trip while fresh data is fetched. StateFlow dedupes equal
     * values, so if nothing actually changed in the DB, no emission happens and nothing in the
     * UI recomposes. Only genuine changes (leg added/removed/renamed) cause updates.
     */
    private fun reloadTrip() {
        loaded = false
        loadTrip(currentCarId, currentStartDate)
    }

    /**
     * Resolve the trip's country sequence: [start, ...intermediates..., end].
     *
     * Collects all leg edge points (each drive's start + end, each charge's location),
     * resolves each to a country via geocode cache, then:
     *   - first leg start  → trip start country
     *   - last leg end     → trip end country
     *   - everything else  → deduplicated intermediates (excl start & end)
     *
     * Drive aggregates store start + end coords (V9+). For aggregates computed
     * before V9, the last drive's end is fetched from the API as fallback (1 call).
     */
    private suspend fun resolveCountriesFromDriveEdges(
        trip: Trip, localCarId: Int, remoteCarId: Int, isCurrent: () -> Boolean
    ): List<TripCountry> {
        // 1. Collect all edge points in chronological order
        data class EdgePoint(val lat: Double, val lon: Double, val time: String)

        val points = mutableListOf<EdgePoint>()

        val driveCoords = aggregateDao.getDriveEdgeCoordinates(localCarId, trip.drives.map { it.driveId })
            .associateBy { it.driveId }

        // Drive start + end points from cached coordinates
        for (drive in trip.drives) {
            val coord = driveCoords[drive.driveId] ?: continue
            points.add(EdgePoint(coord.startLatitude, coord.startLongitude, drive.startDate))
            if (coord.endLatitude != null && coord.endLongitude != null) {
                points.add(EdgePoint(coord.endLatitude, coord.endLongitude, drive.endDate))
            }
        }

        // Fallback: if the last drive has no cached end coords, fetch from API (1 call).
        // SchemaVersion V5 triggers re-sync to populate end coords, but the sync may
        // not have completed yet when the user first opens the trip detail after update.
        val lastDrive = trip.drives.lastOrNull()
        val lastCoord = lastDrive?.let { driveCoords[it.driveId] }
        if (lastDrive != null && (lastCoord == null || lastCoord.endLatitude == null) && isCurrent()) {
            when (val result = repository.getDriveDetail(remoteCarId, lastDrive.driveId)) {
                is ApiResult.Success -> {
                    result.data.positions
                        ?.lastOrNull { it.latitude != null && it.longitude != null }
                        ?.let { points.add(EdgePoint(it.latitude!!, it.longitude!!, lastDrive.endDate)) }
                }
                is ApiResult.Error -> {}
            }
        }

        // Charge locations
        for (charge in trip.charges) {
            points.add(EdgePoint(charge.latitude, charge.longitude, charge.startDate))
        }

        points.sortBy { it.time }

        // 2. Resolve each point to a country code
        val codes = points.mapNotNull { pt ->
            val cached = geocodeCacheDao.get((pt.lat * 100).toInt(), (pt.lon * 100).toInt())
            cached?.countryCode
        }

        if (codes.isEmpty()) return emptyList()

        return buildCountrySequence(codes).map { TripCountry(it, countryCodeToFlag(it)) }
    }

    companion object {
        /**
         * Build [start, ...intermediates..., end] from an ordered list of country codes.
         * - First code = trip start
         * - Last code = trip end
         * - Everything in between: deduplicated, excluding start & end countries
         */
        internal fun buildCountrySequence(codes: List<String>): List<String> {
            if (codes.isEmpty()) return emptyList()
            if (codes.size == 1) return listOf(codes.first())

            val startCode = codes.first()
            val endCode = codes.last()

            val intermediates = codes
                .drop(1).dropLast(1)
                .filter { it != startCode && it != endCode }
                .distinct()

            val result = mutableListOf(startCode)
            result.addAll(intermediates)
            if (endCode != startCode || intermediates.isNotEmpty()) {
                result.add(endCode)
            }

            return result
        }
    }

    private suspend fun CoroutineScope.loadRoutePositions(carId: Int, trip: Trip, localCarId: Int,
                                   generation: Long, scope: HistoryReadScope, context: VehicleContext) {
            val tripKey = computeTripKey(trip, localCarId)

            // Try cache first
            val cachedRows = tripRouteCacheDao.getSegments(tripKey)
            val deferredByBudget = cachedRows.isEmpty() && trip.drives.size > MAX_AUTO_TRIP_ROUTE_DRIVES
            val segments = if (cachedRows.isNotEmpty()) {
                cachedRows.map { row -> deserializeSegment(row.segmentData) }
            } else if (deferredByBudget) {
                emptyList()
            } else {
                val fetched = fetchRouteFromApi(carId, trip) {
                    isCurrent(generation, scope, context)
                }
                if (fetched.isNotEmpty() && isCurrent(generation, scope, context)) {
                    val now = System.currentTimeMillis()
                    tripRouteCacheDao.insertAll(
                        fetched.mapIndexed { index, segment ->
                            TripRouteCache(
                                tripKey = tripKey,
                                segmentIndex = index,
                                segmentData = serializeSegment(segment),
                                createdAt = now
                            )
                        }
                    )
                }
                fetched
            }

            // Simplify for display — keeps route shape, reduces rendering work
            val simplified = segments.map { segment ->
                TripRouteSegment(
                    RouteSimplifier.simplify(
                        segment.points,
                        epsilon = 0.0001,
                        lat = { it.latitude },
                        lon = { it.longitude }
                    )
                )
            }

            val allPoints = simplified.flatMap { it.points }

            // Rebuild markers from scratch: [start] + charges + [end]. Building from `trip` avoids
            // double-adding start/end on revalidation (previous version appended to current state).
            val markers = mutableListOf<TripMapMarker>()
            if (allPoints.isNotEmpty()) {
                val first = allPoints.first()
                markers.add(
                    TripMapMarker(
                        first.latitude, first.longitude,
                        TripMapPointType.START, trip.startAddress
                    )
                )
            }
            trip.charges.forEach { charge ->
                markers.add(
                    TripMapMarker(
                        charge.latitude, charge.longitude,
                        TripMapPointType.CHARGE, charge.address,
                        chargeId = charge.chargeId
                    )
                )
            }
            if (allPoints.isNotEmpty()) {
                val last = allPoints.last()
                markers.add(
                    TripMapMarker(
                        last.latitude, last.longitude,
                        TripMapPointType.END, trip.endAddress
                    )
                )
            }

            if (!isCurrent(generation, scope, context)) return
            _uiState.update { it.copy(routeSegments = simplified, markers = markers,
                isMapLoading = false, routeDeferredByBudget = deferredByBudget) }

            // Weather fetch kicks in after route data is ready — it needs per-drive GPS points
            // to sample. Runs in the same viewModelScope so it's cancelled if the screen goes
            // away. Only fetches if we don't already have samples (memory cache across reloads).
            if (_uiState.value.weatherPoints.isEmpty() && simplified.isNotEmpty()) {
                launch {
                    val samples = weatherRepository.getWeatherAlongTrip(trip.drives, simplified)
                    if (samples.isNotEmpty() && isCurrent(generation, scope, context)) {
                        _uiState.update { it.copy(weatherPoints = samples) }
                    }
                }
            }
    }

    private suspend fun fetchRouteFromApi(
        carId: Int, trip: Trip, isCurrent: () -> Boolean
    ): List<TripRouteSegment> {
        return fetchBoundedTripRoute(trip.drives.map { it.driveId }, isCurrent) { driveId ->
            when (val result = repository.getDriveDetail(carId, driveId)) {
                is ApiResult.Success -> TripRouteSegment(result.data.positions
                    ?.filter { it.latitude != null && it.longitude != null }
                    ?.map { TripRoutePoint(it.latitude!!, it.longitude!!) }
                    ?: emptyList())
                is ApiResult.Error -> null
            }
        }.filter { it.points.isNotEmpty() }
        }

    /** SHA-256 hash of sorted drive IDs, used as cache key. Delegates to [TripRepository]. */
    private fun computeTripKey(trip: Trip, localCarId: Int): String =
        "$localCarId:${tripRepository.computeFingerprint(trip)}"

    /** Pack points as big-endian doubles: [lat0, lon0, lat1, lon1, ...] */
    private fun serializeSegment(segment: TripRouteSegment): ByteArray {
        val buf = ByteBuffer.allocate(segment.points.size * 16) // 2 doubles × 8 bytes
        for (pt in segment.points) {
            buf.putDouble(pt.latitude)
            buf.putDouble(pt.longitude)
        }
        return buf.array()
    }

    private fun deserializeSegment(data: ByteArray): TripRouteSegment {
        val buf = ByteBuffer.wrap(data)
        val count = data.size / 16
        return TripRouteSegment(
            (0 until count).map {
                TripRoutePoint(buf.getDouble(), buf.getDouble())
            }
        )
    }
}

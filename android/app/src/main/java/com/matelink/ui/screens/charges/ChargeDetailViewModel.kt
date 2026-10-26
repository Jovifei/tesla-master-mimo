package com.matelink.ui.screens.charges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.matelink.R
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.Units
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.SettingsDataStore
import com.matelink.data.local.StatsDatabase
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.HistoryDetailRepository
import com.matelink.data.repository.HistoryDetailPointSaveRejected
import com.matelink.data.repository.persistVerifiedChargeDetail
import com.matelink.data.repository.verifiedDetailIdentity
import com.matelink.data.repository.VerifiedHistoryReadContext
import com.matelink.data.repository.historyIdentityUnavailableError
import com.matelink.domain.history.LatestHistoryLoad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collect
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.SavedTripLeg
import com.matelink.data.model.Currency
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.TeslamateRepository
import com.matelink.domain.LegRef
import com.matelink.domain.TripRepository
import com.matelink.domain.analytics.ChargeCostSource
import com.matelink.domain.analytics.EffectiveChargeCostInput
import com.matelink.domain.analytics.EffectiveChargeCostResolver
import com.matelink.domain.analytics.EffectiveChargeCost
import com.matelink.domain.analytics.findChargeCostOverride
import com.matelink.domain.analytics.findOtherCurrencyChargeCostOverride
import com.matelink.domain.analytics.projectEffectiveChargeCost
import com.matelink.domain.analytics.toCostChargeData
import com.matelink.domain.analytics.validManualChargeTotal
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.analytics.acceptsDetail
import com.matelink.domain.analytics.withQualifiedEnergy
import com.matelink.domain.model.Trip
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChargeDetailUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val localArchiveLinkPending: Boolean = false,
    val historySyncWarning: String? = null,
    val error: String? = null,
    val chargeDetail: ChargeDetail? = null,
    val units: Units? = null,
    val stats: ChargeDetailStats? = null,
    val costPresentation: ChargeDetailCostPresentation = ChargeDetailCostPresentation(
        cost = null,
        state = ChargeDetailCostState.UNAVAILABLE
    ),
    val currencySymbol: String = Currency.CNY.symbol,
    val currencyCode: String = "CNY",
    val isDcCharge: Boolean? = null,
    val manualTotalAmount: Double? = null,
    val manualVersion: Long = 0L,
    val pendingLegacyAmount: Double? = null,
    val pendingBoundAmount: Pair<String?, Double>? = null,
    val otherCurrencyAmount: Pair<String, Double>? = null,
    val sourceUnconfirmedAmount: Double? = null,
    val isExplicitlyFree: Boolean = false,
    val containingTrip: Pair<Long, Trip>? = null
)

data class ChargeDetailStats(
    val powerMax: Int?,
    val powerMin: Int?,
    val powerAvg: Double?,
    val voltageMax: Int?,
    val voltageMin: Int?,
    val voltageAvg: Double?,
    val currentMax: Int?,
    val currentMin: Int?,
    val currentAvg: Double?,
    val tempMax: Double?,
    val tempMin: Double?,
    val tempAvg: Double?,
    val batteryStart: Int?,
    val batteryEnd: Int?,
    val batteryAdded: Int?,
    val energyAdded: Double?,
    val energyUsed: Double?,
    val efficiency: Double?,
    val durationMin: Int?,
    val cost: Double?
)

enum class ChargeDetailCostState {
    ESTIMATE,
    ACTUAL,
    MANUAL,
    FREE,
    UNAVAILABLE
}

data class ChargeDetailCostPresentation(
    val cost: Double?,
    val state: ChargeDetailCostState,
    val qualificationReason: String? = null
)

internal fun presentChargeDetailCost(
    manualAmount: Double? = null,
    manuallyFree: Boolean = false,
    teslaMateCost: Double? = null,
    energyKwh: Double? = null,
    defaultPricePerKwh: Double? = null
): ChargeDetailCostPresentation {
    val effectiveCost = EffectiveChargeCostResolver.resolve(
        EffectiveChargeCostInput(
            manualAmount = manualAmount,
            manuallyFree = manuallyFree,
            teslaMateCost = teslaMateCost,
            energyKwh = energyKwh?.takeIf { it.isFinite() && it >= 0.0 },
            defaultPricePerKwh = defaultPricePerKwh
        )
    )
    val state = when (effectiveCost.source) {
        ChargeCostSource.ESTIMATE -> ChargeDetailCostState.ESTIMATE
        ChargeCostSource.MANUAL -> ChargeDetailCostState.MANUAL
        ChargeCostSource.FREE -> ChargeDetailCostState.FREE
        ChargeCostSource.TESLAMATE -> ChargeDetailCostState.ACTUAL
        ChargeCostSource.UNAVAILABLE -> ChargeDetailCostState.UNAVAILABLE
    }

    return ChargeDetailCostPresentation(
        cost = if (state == ChargeDetailCostState.UNAVAILABLE) null else effectiveCost.cost,
        state = state
    )
}

private fun EffectiveChargeCost.toDetailCostPresentation(): ChargeDetailCostPresentation =
    ChargeDetailCostPresentation(
        cost = cost,
        state = when (source) {
            ChargeCostSource.ESTIMATE -> ChargeDetailCostState.ESTIMATE
            ChargeCostSource.MANUAL -> ChargeDetailCostState.MANUAL
            ChargeCostSource.FREE -> ChargeDetailCostState.FREE
            ChargeCostSource.TESLAMATE -> ChargeDetailCostState.ACTUAL
            ChargeCostSource.UNAVAILABLE -> ChargeDetailCostState.UNAVAILABLE
        },
        qualificationReason = qualificationReason
    )

@HiltViewModel
class ChargeDetailViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: TeslamateRepository,
    private val settingsDataStore: SettingsDataStore,
    private val chargeCostOverrideStore: ChargeCostOverrideStore,
    private val tripRepository: TripRepository,
    private val historyRepository: UnifiedHistoryRepository,
    private val chargeSummaryDao: ChargeSummaryDao,
    private val statsDatabase: StatsDatabase,
    private val historyDetailStore: HistoryDetailRepository,
    private val vehicleContexts: com.matelink.data.local.VehicleContextRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChargeDetailUiState())
    val uiState: StateFlow<ChargeDetailUiState> = _uiState.asStateFlow()

    private val latestLoad = LatestHistoryLoad()
    private var historyProof: VerifiedHistoryReadContext? = null
    private var carId: Int? = null
    private var chargeId: Int? = null
    private var historyCarId: Int? = null
    private var defaultChargePrice = 1.14
    private var tariffSettings: com.matelink.data.local.AppSettings? = null

    init {
        viewModelScope.launch {
            historyRepository.scopeChanges?.collect { scope ->
                val proof = historyProof ?: return@collect
                if (scope == proof.scope && historyRepository.isContextCurrent(proof)) return@collect
                latestLoad.invalidate()
                historyProof = null
                historyCarId = null
                _uiState.value = ChargeDetailUiState(isLoading = false, error = "history_identity_unavailable")
                val car = carId; val charge = chargeId
                if (car != null && charge != null) loadChargeDetail(car, charge)
            }
        }
    }

    private data class LoadedCost(
        val presentation: ChargeDetailCostPresentation,
        val manualTotal: Double?,
        val version: Long,
        val pendingLegacy: Double?,
        val pendingBound: Pair<String?, Double>?,
        val otherCurrency: Pair<String, Double>?,
        val sourceUnconfirmed: Double?
    )

    private suspend fun loadCost(
        localCarId: Int, detail: ChargeDetail, currencyCode: String,
        isExplicitlyFree: Boolean, isDcCharge: Boolean
    ): LoadedCost {
        val charge = detail.toCostChargeData()
        val records = chargeCostOverrideStore.getRecordsForCharge(localCarId, detail.chargeId)
        val bound = findChargeCostOverride(records, localCarId, charge, currencyCode)
        val other = findOtherCurrencyChargeCostOverride(records, localCarId, charge, currencyCode)
        val legacy = records.firstOrNull { it.sessionKey == "legacy" && !it.deleted }
        val effective = projectEffectiveChargeCost(charge, localCarId, bound, currencyCode,
            isExplicitlyFree, isDcCharge, defaultChargePrice,
            tariffEstimate = tariffSettings?.let { com.matelink.domain.analytics.estimateChargeTariff(charge, it) })
        return LoadedCost(
            presentation = effective.toDetailCostPresentation(),
            manualTotal = bound?.manualTotalAmount?.takeIf { !bound.deleted && effective.source == ChargeCostSource.MANUAL },
            version = bound?.version ?: 0L,
            pendingLegacy = legacy?.manualTotalAmount,
            pendingBound = bound?.takeIf { !it.deleted && effective.source != ChargeCostSource.MANUAL }
                ?.let { it.currencyCode to it.manualTotalAmount },
            otherCurrency = other?.currencyCode?.let { it to other.manualTotalAmount },
            sourceUnconfirmed = detail.cost?.takeIf { it.isFinite() && it >= 0.0 &&
                detail.currencyCode.isNullOrBlank() && !detail.source.isNullOrBlank() }
        )
    }

    init {
        loadCurrency()
    }

    private fun loadCurrency() {
        viewModelScope.launch {
            val settings = settingsDataStore.settings.first()
            defaultChargePrice = settings.defaultChargePrice
            tariffSettings = settings
            val currency = Currency.entries.firstOrNull {
                it.code.equals(settings.currencyCode, ignoreCase = true)
            }
            _uiState.update { it.copy(currencySymbol = currency?.symbol ?: "?",
                currencyCode = currency?.code ?: settings.currencyCode) }
        }
    }

    private suspend fun publishColdCache(remoteId: Int, recordId: Int, ensureCurrent: suspend () -> Unit): Boolean {
        val contexts = vehicleContexts
        val proof = if (contexts != null) {
            val scope = contexts.captureReadScope()
            val context = contexts.cachedContextForRemote(remoteId, scope) ?: return false
            VerifiedHistoryReadContext(scope, context, false, null, false)
        } else (historyRepository.loadCached(remoteId) as? ApiResult.Success)?.data?.verifiedReadContext ?: return false
        suspend fun check() {
            ensureCurrent()
            if (!historyRepository.isContextCurrent(proof)) throw com.matelink.data.local.HistoryIdentityUnavailableException()
        }
        check()
        val row = chargeSummaryDao.get(proof.context.localHistoryCarId, recordId) ?: return false
        if (row.qualityState == "quarantined") return false
        val settings = settingsDataStore.settings.first()
        check()
        val saved = row.verifiedDetailIdentity(proof.context)?.let { historyDetailStore.readCharge(it) }
        check()
        val (detail, units) = presentChargeTemperature(cachedChargeDetail(row, saved?.detail), null)
        defaultChargePrice = settings.defaultChargePrice
        tariffSettings = settings
        val cost = loadCost(proof.context.localHistoryCarId, detail, settings.currencyCode,
            false, ChargeStatsCalculator.detectChargeType(detail) == ChargeType.DC)
        check()
        if (settingsDataStore.settings.first().currencyCode != settings.currencyCode) return false
        check()
        historyProof = proof
        historyCarId = proof.context.localHistoryCarId
        val warning = when {
            saved?.reason == "detail_cache_corrupt" -> "history_detail_cache_corrupt"
            saved?.detail == null -> "history_cached"
            saved.receipt?.sourceInstanceVerified != true || saved.receipt?.sourceIdVerified != true -> "history_detail_cached_source_unverified"
            saved.receipt?.providerTotalVerified != true -> "history_detail_cached_total_unverified"
            else -> "history_cached"
        }
        _uiState.update { it.copy(isLoading = false, isRefreshing = true, chargeDetail = detail,
            stats = ChargeStatsCalculator.calculateStats(detail), units = units, historySyncWarning = warning,
            currencyCode = settings.currencyCode, currencySymbol = Currency.entries.firstOrNull { c -> c.code == settings.currencyCode }?.symbol ?: "?",
            isDcCharge = ChargeStatsCalculator.detectChargeType(detail).toDcFlag(), costPresentation = cost.presentation,
            manualTotalAmount = cost.manualTotal, manualVersion = cost.version, pendingLegacyAmount = cost.pendingLegacy,
            pendingBoundAmount = cost.pendingBound, otherCurrencyAmount = cost.otherCurrency,
            sourceUnconfirmedAmount = cost.sourceUnconfirmed, error = null) }
        return true
    }

    fun loadChargeDetail(carId: Int, chargeId: Int) {
        this.carId = carId
        this.chargeId = chargeId
        historyProof = null
        historyCarId = null
        _uiState.value = ChargeDetailUiState(currencySymbol = _uiState.value.currencySymbol,
            currencyCode = _uiState.value.currencyCode)
        latestLoad.launch(viewModelScope) {
            val cachedPublished = try { publishColdCache(carId, chargeId) { ensureCurrent() } }
                catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            val resolved = when (val result = withTimeoutOrNull(20_000L) { historyRepository.resolveContext(carId) }
                    ?: ApiResult.Error("history_sync_timeout")) {
                is ApiResult.Error -> {
                    ensureCurrent()
                    val cachedProof = historyProof
                    if (cachedPublished && cachedProof != null && historyRepository.isContextCurrent(cachedProof))
                        _uiState.update { it.copy(isLoading = false, isRefreshing = false, historySyncWarning = "history_cached", error = null) }
                    else _uiState.value = ChargeDetailUiState(isLoading = false, error = result.message)
                    return@launch
                }
                is ApiResult.Success -> result.data
            }
            suspend fun checkContext() {
                ensureCurrent()
                if (!historyRepository.isContextCurrent(resolved)) {
                    historyProof = null
                    _uiState.value = ChargeDetailUiState(isLoading = false, error = historyIdentityUnavailableError().message)
                    throw CancellationException("History identity changed")
                }
            }
            checkContext()
            val previous = historyProof
            if (previous != null && (previous.scope != resolved.scope || previous.context.localHistoryCarId != resolved.context.localHistoryCarId))
                _uiState.value = ChargeDetailUiState()
            historyProof = resolved
            val localHistoryCarId = resolved.context.localHistoryCarId
            historyCarId = localHistoryCarId
            val settings = settingsDataStore.settings.first()
            defaultChargePrice = settings.defaultChargePrice
            tariffSettings = settings
            checkContext()
            _uiState.update { it.copy(currencySymbol = Currency.entries.firstOrNull { currency ->
                    currency.code.equals(settings.currencyCode, ignoreCase = true)
                }?.symbol ?: "?",
                currencyCode = settings.currencyCode) }
            _uiState.update { it.copy(localArchiveLinkPending = resolved.localArchiveLinkPending) }
            val containing = tripRepository.findTripContaining(localHistoryCarId, SavedTripLeg.TYPE_CHARGE, chargeId)
            checkContext()
            _uiState.update { it.copy(containingTrip = containing) }

            // Fetch charge detail and units in parallel
            val detailResult = if (resolved.remoteAuthorized) withTimeoutOrNull(20_000L) { repository.getChargeDetail(carId, chargeId) }
                ?: ApiResult.Error("history_sync_timeout")
                else resolved.identityError ?: historyIdentityUnavailableError()
            checkContext()
            val statusResult = if (resolved.remoteAuthorized) withTimeoutOrNull(5_000L) { repository.getCarStatus(carId) }
                ?: ApiResult.Error("history_sync_timeout") else historyIdentityUnavailableError()
            checkContext()
            val carResult = if (resolved.remoteAuthorized) repository.getCar(carId) else historyIdentityUnavailableError()
            checkContext()

            val units = when (statusResult) {
                is ApiResult.Success -> statusResult.data.units
                is ApiResult.Error -> null
            }

            when (detailResult) {
                is ApiResult.Success -> {
                    val rawDetail = detailResult.data
                    val qualified = presentChargeTemperature(rawDetail.withQualifiedEnergy(), units).first
                    val coords = resolveChargeCoordinates(qualified, localHistoryCarId)
                    val detail = if (coords != null) {
                        qualified.copy(latitude = coords.first, longitude = coords.second)
                    } else qualified
                    var pointWriteFailed = false
                    val persisted = try {
                        persistVerifiedChargeDetail(statsDatabase, historyDetailStore,
                            resolved.context, rawDetail) { checkContext() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: HistoryDetailPointSaveRejected) {
                        pointWriteFailed = true
                        null
                    } catch (_: Exception) {
                        pointWriteFailed = true
                        null
                    }
                    checkContext()
                    if (persisted == null && !pointWriteFailed) {
                        _uiState.update { it.copy(isLoading = false,
                            historySyncWarning = "history_detail_waiting_for_summary", error = null) }
                        return@launch
                    }
                    val receipt = persisted?.receipt
                    val detailWarning = if (pointWriteFailed) "history_detail_points_missing" else when {
                        receipt == null -> "history_detail_points_missing"
                        receipt.sourceInstanceVerified != true ||
                            receipt?.sourceIdVerified != true -> "history_detail_source_unverified"
                        receipt?.providerTotalVerified != true -> "history_detail_total_unverified"
                        else -> null
                    }
                    val stats = ChargeStatsCalculator.calculateStats(detail)
                    val chargeType = ChargeStatsCalculator.detectChargeType(detail)
                    val isDcCharge = chargeType.toDcFlag()
                    val isExplicitlyFree = when (carResult) {
                        is ApiResult.Success -> carResult.data.carSettings?.freeSupercharging == true
                        is ApiResult.Error -> false
                    }
                    val loadedCost = loadCost(localHistoryCarId, detail, settings.currencyCode,
                        isExplicitlyFree, isDcCharge == true)
                    checkContext()
                    _uiState.update {
                        it.copy(
                            isLoading = false, isRefreshing = false,
                            historySyncWarning = detailWarning,
                            chargeDetail = detail,
                            units = presentChargeTemperature(detail, units).second,
                            stats = stats,
                            costPresentation = loadedCost.presentation,
                            isDcCharge = isDcCharge,
                            manualTotalAmount = loadedCost.manualTotal,
                            manualVersion = loadedCost.version,
                            pendingLegacyAmount = loadedCost.pendingLegacy,
                            pendingBoundAmount = loadedCost.pendingBound,
                            otherCurrencyAmount = loadedCost.otherCurrency,
                            sourceUnconfirmedAmount = loadedCost.sourceUnconfirmed,
                            isExplicitlyFree = isExplicitlyFree,
                            error = null
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update { it.copy(historySyncWarning = "history_cached") }
                    val localSummary = chargeSummaryDao.get(localHistoryCarId, chargeId)
                    checkContext()
                    if (localSummary != null) {
                        val pointCache = try {
                            localSummary.verifiedDetailIdentity(resolved.context)
                                ?.let { historyDetailStore.readCharge(it) }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { null }
                        checkContext()
                        // A local summary is a bounded offline fallback only. Never
                        // fabricate an electrical trace from aggregate values.
                        val cached = localSummary.toAnalysisChargeData()
                        val localDetail = presentChargeTemperature(cachedChargeDetail(localSummary, pointCache?.detail), units).first
                            .let { detail ->
                                val coords = resolveChargeCoordinates(detail, localHistoryCarId)
                                if (coords == null) detail else detail.copy(latitude = coords.first, longitude = coords.second)
                            }
                        val stats = ChargeStatsCalculator.calculateStats(localDetail)
                        val isDcCharge = ChargeStatsCalculator.detectChargeType(localDetail).toDcFlag()
                        val loadedCost = loadCost(localHistoryCarId, localDetail, settings.currencyCode,
                            false, isDcCharge == true)
                        checkContext()
                        _uiState.update {
                            it.copy(
                                isLoading = false, isRefreshing = false,
                                historySyncWarning = when {
                                    pointCache?.reason == "detail_cache_corrupt" ->
                                        "history_detail_cache_corrupt"
                                    pointCache?.detail == null -> "history_cached"
                                    pointCache?.receipt?.sourceInstanceVerified != true ||
                                        pointCache?.receipt?.sourceIdVerified != true ->
                                        "history_detail_cached_source_unverified"
                                    pointCache?.receipt?.providerTotalVerified != true ->
                                        "history_detail_cached_total_unverified"
                                    else -> "history_cached"
                                },
                                chargeDetail = localDetail,
                                units = presentChargeTemperature(localDetail, units).second,
                                stats = stats,
                                costPresentation = loadedCost.presentation,
                                isDcCharge = isDcCharge,
                                manualTotalAmount = loadedCost.manualTotal,
                                manualVersion = loadedCost.version,
                                pendingLegacyAmount = loadedCost.pendingLegacy,
                                pendingBoundAmount = loadedCost.pendingBound,
                                otherCurrencyAmount = loadedCost.otherCurrency,
                                sourceUnconfirmedAmount = loadedCost.sourceUnconfirmed,
                                error = null
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false, isRefreshing = false,
                                error = detailResult.message
                            )
                        }
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun saveManualTotalAmount(totalAmount: Double?) {
        val proof = historyProof ?: return
        val currentCarId = proof.context.localHistoryCarId
        val currentChargeId = chargeId ?: return
        val captured = _uiState.value
        val detail = captured.chargeDetail ?: return
        if (detail.chargeId != currentChargeId) return
        val currencyCode = captured.currencyCode
        if (Currency.entries.none { it.code.equals(currencyCode, ignoreCase = true) }) {
            _uiState.update { it.copy(error = appContext.getString(R.string.charge_cost_currency_unverified)) }
            return
        }
        val expectedVersion = captured.manualVersion
        val validTotal = validManualChargeTotal(totalAmount)
        if (totalAmount != null && validTotal == null) return

        viewModelScope.launch {
            if (historyProof !== proof || !historyRepository.isContextCurrent(proof)) return@launch
            if (settingsDataStore.settings.first().currencyCode != currencyCode) return@launch
            val saved = chargeCostOverrideStore.saveBound(currentCarId, detail.toCostChargeData(),
                validTotal, currencyCode, expectedVersion)
            if (historyProof !== proof || !historyRepository.isContextCurrent(proof)) return@launch
            if (!saved) {
                _uiState.update { it.copy(error = appContext.getString(R.string.charge_cost_edit_conflict)) }
                return@launch
            }
            val loadedCost = loadCost(currentCarId, detail, currencyCode,
                captured.isExplicitlyFree, captured.isDcCharge == true)
            if (historyProof !== proof || !historyRepository.isContextCurrent(proof)) return@launch
            _uiState.update {
                it.copy(
                    manualTotalAmount = loadedCost.manualTotal,
                    manualVersion = loadedCost.version,
                    pendingLegacyAmount = loadedCost.pendingLegacy,
                    pendingBoundAmount = loadedCost.pendingBound,
                    otherCurrencyAmount = loadedCost.otherCurrency,
                    sourceUnconfirmedAmount = loadedCost.sourceUnconfirmed,
                    costPresentation = loadedCost.presentation
                )
            }
        }
    }

    private suspend fun resolveChargeCoordinates(detail: ChargeDetail, carId: Int): Pair<Double, Double>? =
        chooseChargeCoordinates(detail, chargeSummaryDao.get(carId, detail.chargeId))

    /** Detach this charge from its containing saved trip (auto-transitions the trip to USER_EDITED). */
    fun removeFromTrip() {
        val proof = historyProof ?: return
        val tripId = _uiState.value.containingTrip?.first ?: return
        val charge = chargeId ?: return
        viewModelScope.launch {
            if (historyProof !== proof || !historyRepository.isContextCurrent(proof)) return@launch
            tripRepository.removeLegFromTrip(tripId, LegRef(SavedTripLeg.TYPE_CHARGE, charge))
            if (historyProof !== proof || !historyRepository.isContextCurrent(proof)) return@launch
            _uiState.update { it.copy(containingTrip = null) }
        }
    }

}

private fun ChargeType.toDcFlag(): Boolean? = when (this) {
    ChargeType.DC -> true
    ChargeType.AC -> false
    ChargeType.UNKNOWN -> null

}

internal fun cachedChargeDetail(localSummary: com.matelink.data.local.entity.ChargeSummary,
    pointHeader: ChargeDetail? = null): ChargeDetail {
    val cached = localSummary.toAnalysisChargeData()
    val summaryUnit = receivedChargeTemperatureUnit(cached.temperatureUnit, cached.source)
    val pointUnit = receivedChargeTemperatureUnit(pointHeader?.temperatureUnit, pointHeader?.source)
    val displayUnit = summaryUnit ?: pointUnit
    val points = pointHeader?.chargePoints.orEmpty().map { point ->
        point.copy(outsideTemp = translateChargeTemperature(point.outsideTemp, pointUnit, displayUnit))
    }
    return ChargeDetail(
                            chargeId = cached.chargeId,
                            startDate = cached.startDate,
                            endDate = cached.endDate,
                            address = cached.address?.takeIf(String::isNotBlank),
                            chargeEnergyAdded = cached.batteryInputKwh,
                            chargeEnergyUsed = cached.inputEnergyKwh,
                            cost = cached.cost,
                            durationMin = cached.durationMin,
                            durationStr = cached.durationStr,
                            batteryDetails = cached.batteryDetails,
                            outsideTempAvg = translateChargeTemperature(cached.outsideTempAvg, summaryUnit, displayUnit),
                            odometer = cached.odometer,
                            latitude = cached.latitude,
                            longitude = cached.longitude,
                            chargePoints = points,
                            isCharging = false,
                            source = cached.source,
                            sourceInstanceId = cached.sourceInstanceId,
                            sourceId = cached.sourceId,
                            sourceRecordId = cached.sourceRecordId,
                            currencyCode = cached.currencyCode,
                            chargeType = cached.chargeType,
                            temperatureUnit = displayUnit ?: "",
                            lengthUnit = cached.lengthUnit,
                            energyContract = cached.energyContract
                        )
}

internal fun chooseChargeCoordinates(detail: ChargeDetail, local: ChargeSummary?): Pair<Double, Double>? {
    if (detail.sourceRecordIdentity.conflicted) return null
    com.matelink.domain.telemetry.usableVehicleCoordinates(detail.latitude, detail.longitude)?.let { return it }
    if (local == null || !com.matelink.domain.history.isAnalysisEligible(local.qualityState, local.qualityReason) ||
        !local.acceptsDetail(detail)) return null
    val saved = local.toAnalysisChargeData()
    return com.matelink.domain.telemetry.usableVehicleCoordinates(saved.latitude, saved.longitude)
}

internal fun presentChargeTemperature(detail: ChargeDetail, preferred: Units?): Pair<ChargeDetail, Units?> {
    val unit = receivedChargeTemperatureUnit(detail.temperatureUnit, detail.source)
    val display = if (unit != null) detail else detail.copy(outsideTempAvg = null,
        chargePoints = detail.chargePoints?.map { it.copy(outsideTemp = null) })
    return display to (preferred ?: Units()).copy(unitOfTemperature = unit ?: "")
}

private fun receivedChargeTemperatureUnit(unit: String?, source: String?): String? =
    when (val received = unit?.trim()?.uppercase()?.removePrefix("°")) {
        "C", "F" -> received
        null -> if (source in setOf("fleet_api", "telemetry_mqtt")) "C" else null
        else -> null
    }

private fun translateChargeTemperature(value: Double?, from: String?, to: String?): Double? {
    val raw = value?.takeIf(Double::isFinite) ?: return null
    if (from !in setOf("C", "F") || to !in setOf("C", "F")) return null
    if (from == to) return raw
    return (if (from == "F") (raw - 32.0) * (5.0 / 9.0) else raw * (9.0 / 5.0) + 32.0)
        .takeIf(Double::isFinite)
}

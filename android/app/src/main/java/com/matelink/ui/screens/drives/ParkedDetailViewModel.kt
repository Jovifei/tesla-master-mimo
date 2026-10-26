package com.matelink.ui.screens.drives

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matelink.R
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.HistoryIdentityUnavailableException
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.StatsDatabase
import androidx.room.withTransaction
import com.matelink.data.repository.ParkedHistoryReceiptStore
import com.matelink.data.repository.ParkedReceiptBinding
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.analytics.toRawAnalysisDriveData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.matelink.data.api.models.ParkedDetailData
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.data.api.models.LinkedCharge
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
    val syncWarning: String? = null,
    val chargeBreakdown: ParkedChargeBreakdown? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

internal data class ParkedPageIdentity(val stillCurrent: () -> Boolean, val localHistoryCarId: Int? = null,
    val context: VehicleContext? = null)

internal interface ParkedPageSource {
    val scopeChanges: Flow<Unit>
    val timeoutMs: Long get() = Long.MAX_VALUE
    suspend fun identity(carId: Int): ParkedPageIdentity
    suspend fun localDetail(localHistoryCarId: Int, olderDriveId: Int, newerDriveId: Int): ParkedDetailData? = null
    suspend fun localCharges(localHistoryCarId: Int, olderDriveId: Int, newerDriveId: Int): List<LinkedCharge> = emptyList()
    suspend fun coordinates(localHistoryCarId: Int, olderDriveId: Int, newerDriveId: Int): Pair<Double, Double>? = null
    suspend fun readReceipt(identity: ParkedPageIdentity, local: ParkedDetailData): ParkedDetailData? = null
    suspend fun saveReceipt(identity: ParkedPageIdentity, local: ParkedDetailData, received: ParkedDetailData,
        ensureCurrent: () -> Unit): Boolean = false
    suspend fun acceptsRemote(identity: ParkedPageIdentity, local: ParkedDetailData?, received: ParkedDetailData): Boolean =
        local != null && sameParkedWindow(local, received) && received.source == local.source &&
            (received.carId == null || received.carId == identity.context?.remoteApiCarId) &&
            (received.sourceInstanceId == null || received.sourceInstanceId == local.sourceInstanceId)
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
        driveSummaryDao: DriveSummaryDao,
        chargeSummaryDao: ChargeSummaryDao,
        statsDatabase: StatsDatabase,
        receiptStore: ParkedHistoryReceiptStore
    ) : this(object : ParkedPageSource {
        override val scopeChanges = vehicleContexts.historyScopeChanges.map { Unit }
        override val timeoutMs = 20_000L
        override suspend fun identity(carId: Int): ParkedPageIdentity {
            val scope = vehicleContexts.captureReadScope()
            val context = vehicleContexts.cachedContextForRemote(carId, scope)
                ?: throw HistoryIdentityUnavailableException()
            return ParkedPageIdentity(
                stillCurrent = { vehicleContexts.isHistoryContextCurrentNow(scope, context) },
                localHistoryCarId = context.localHistoryCarId, context = context
            )
        }
        override suspend fun localDetail(localHistoryCarId: Int, olderDriveId: Int, newerDriveId: Int): ParkedDetailData? {
            return statsDatabase.withTransaction { readLocalParkedDetail(driveSummaryDao, localHistoryCarId, olderDriveId, newerDriveId) }
        }
        override suspend fun readReceipt(identity: ParkedPageIdentity, local: ParkedDetailData): ParkedDetailData? =
            statsDatabase.withTransaction {
                val binding = verifiedParkedReceiptBinding(driveSummaryDao, identity, local) ?: return@withTransaction null
                receiptStore.read(binding) { if (!identity.stillCurrent()) throw HistoryIdentityUnavailableException() }
            }
        override suspend fun saveReceipt(identity: ParkedPageIdentity, local: ParkedDetailData, received: ParkedDetailData,
            ensureCurrent: () -> Unit): Boolean = statsDatabase.withTransaction {
            ensureCurrent()
            val binding = verifiedParkedReceiptBinding(driveSummaryDao, identity, local) ?: return@withTransaction false
            receiptStore.save(binding, received) { ensureCurrent() }
        }
        override suspend fun acceptsRemote(identity: ParkedPageIdentity, local: ParkedDetailData?, received: ParkedDetailData): Boolean =
            local != null && statsDatabase.withTransaction {
                verifiedParkedReceiptBinding(driveSummaryDao, identity, local)?.accepts(received) == true
            }
        override suspend fun localCharges(localHistoryCarId: Int, olderDriveId: Int, newerDriveId: Int): List<LinkedCharge> =
            readLocalParkedCharges(driveSummaryDao, chargeSummaryDao, localHistoryCarId, olderDriveId, newerDriveId)
        override suspend fun coordinates(localHistoryCarId: Int, olderDriveId: Int, newerDriveId: Int): Pair<Double, Double>? {
            val older = driveSummaryDao.get(localHistoryCarId, olderDriveId)?.toAnalysisDriveData()
            val newer = driveSummaryDao.get(localHistoryCarId, newerDriveId)?.toAnalysisDriveData()
            val lat = older?.endLatitude?.takeIf { it.isFinite() && it != 0.0 }
                ?: newer?.startLatitude?.takeIf { it.isFinite() && it != 0.0 }
            val lon = older?.endLongitude?.takeIf { it.isFinite() && it != 0.0 }
                ?: newer?.startLongitude?.takeIf { it.isFinite() && it != 0.0 }
            return if (lat != null && lon != null) lat to lon else null
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
                val reference = identity.localHistoryCarId?.let { localCarId ->
                    try {
                        source.localDetail(localCarId, olderDriveId, newerDriveId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                requireCurrent()
                val saved = reference?.let { known ->
                    try { source.readReceipt(identity, known) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                }
                requireCurrent()
                val admittedSaved = if (saved != null && source.acceptsRemote(identity, reference, saved)) saved else null
                requireCurrent()
                val local = admittedSaved ?: reference
                val coords = identity.localHistoryCarId?.let { localCarId ->
                    try { source.coordinates(localCarId, olderDriveId, newerDriveId) } catch (_: Exception) { null }
                }
                if (local != null)
                    _uiState.value = ParkedDetailUiState(isLoading = false, data = local, degraded = true, latitude = coords?.first, longitude = coords?.second)
                val localCharges = identity.localHistoryCarId?.takeIf { local != null && admittedSaved == null }?.let { localCarId ->
                    try {
                        source.localCharges(localCarId, olderDriveId, newerDriveId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                } ?: emptyList()
                requireCurrent()
                if (local != null)
                    _uiState.value = ParkedDetailUiState(isLoading = false, data = local, degraded = true, latitude = coords?.first, longitude = coords?.second,
                        chargeBreakdown = parkedChargeBreakdown(local, localCharges),
                        syncWarning = if (admittedSaved != null && admittedSaved.sourceInstanceId == null)
                            message(R.string.parked_receipt_source_unverified, "Upstream source instance unverified") else null)

                // A provider exception must not discard verified Room-first parking.
                // Cancellation and identity changes still invalidate the projection.
                val result = try {
                    if (source.timeoutMs == Long.MAX_VALUE)
                        source.detail(carId, olderDriveId, newerDriveId)
                    else withTimeoutOrNull(source.timeoutMs) {
                        source.detail(carId, olderDriveId, newerDriveId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ApiResult.Error(message(R.string.error_loading_data, "Cloud detail unavailable"))
                }
                requireCurrent()
                var accepted = result is ApiResult.Success && source.acceptsRemote(identity, reference, result.data)
                requireCurrent()
                var stored = false
                if (accepted && reference != null && result is ApiResult.Success) {
                    stored = try { source.saveReceipt(identity, reference, result.data, ::requireCurrent) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                    requireCurrent()
                    accepted = source.acceptsRemote(identity, reference, result.data)
                    requireCurrent()
                }
                _uiState.value = when (result) {
                    is ApiResult.Success -> if (accepted && result.data.olderDriveId == olderDriveId && result.data.newerDriveId == newerDriveId) {
                        ParkedDetailUiState(isLoading = false, data = result.data, latitude = coords?.first, longitude = coords?.second,
                            // Server sessions have no wire source-instance proof for
                            // merging with local receipts; never mix both lists.
                            chargeBreakdown = parkedChargeBreakdown(result.data, emptyList()),
                            syncWarning = when {
                                !stored -> receiptWarning(message(R.string.parked_receipt_save_failed, "Detail could not be saved; earlier cache is preserved"), result.data.sourceInstanceId == null)
                                result.data.sourceInstanceId == null -> message(R.string.parked_receipt_source_unverified, "Upstream source instance unverified")
                                else -> null
                            })
                    } else {
                        ParkedDetailUiState(isLoading = false, data = local, degraded = local != null,
                            chargeBreakdown = local?.let { parkedChargeBreakdown(it, localCharges) },
                            syncWarning = if (local != null) receiptWarning(message(R.string.history_sync_cached, "Cloud detail unavailable"), admittedSaved != null && admittedSaved.sourceInstanceId == null) else null,
                            error = if (local == null) HISTORY_IDENTITY_UNAVAILABLE else null)
                    }
                    is ApiResult.Error -> ParkedDetailUiState(
                        isLoading = false, data = local, degraded = local != null,
                        chargeBreakdown = local?.let { parkedChargeBreakdown(it, localCharges) },
                        syncWarning = if (local != null) receiptWarning(message(R.string.history_sync_cached, "Cloud detail unavailable"), admittedSaved != null && admittedSaved.sourceInstanceId == null) else null,
                        error = if (local == null) result.message else null
                    )
                    null -> ParkedDetailUiState(
                        isLoading = false, data = local, degraded = local != null,
                        chargeBreakdown = local?.let { parkedChargeBreakdown(it, localCharges) },
                        syncWarning = if (local != null) receiptWarning(message(R.string.history_sync_timeout, "Cloud detail timed out"), admittedSaved != null && admittedSaved.sourceInstanceId == null) else null,
                        error = if (local == null) message(R.string.history_sync_timeout, "Cloud detail timed out") else null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (currentGeneration == generation && execution.isActive) {
                    if (identity?.stillCurrent() == true) {
                        val previous = _uiState.value
                        _uiState.value = previous.copy(isLoading = false, degraded = previous.data != null,
                            syncWarning = if (previous.data != null) message(R.string.parked_receipt_save_failed, "Detail unavailable; earlier cache is preserved") else null,
                            error = if (previous.data == null) message(R.string.error_loading_data, "Parked detail unavailable") else null)
                    } else _uiState.value = ParkedDetailUiState(isLoading = false, error = HISTORY_IDENTITY_UNAVAILABLE)
                }
            }
        }
    }

    private fun message(id: Int, fallback: String): String = appContext?.getString(id) ?: fallback

    private fun receiptWarning(base: String, unverifiedInstance: Boolean): String =
        if (!unverifiedInstance) base else base + " " + message(R.string.parked_receipt_source_unverified, "Upstream source instance unverified")
}

internal fun sameParkedWindow(first: ParkedDetailData, second: ParkedDetailData): Boolean {
    val start = parseIsoInstant(first.startDate) ?: return false
    val end = parseIsoInstant(first.endDate)?.takeIf { it.isAfter(start) } ?: return false
    return first.olderDriveId == second.olderDriveId && first.newerDriveId == second.newerDriveId &&
        parseIsoInstant(second.startDate) == start && parseIsoInstant(second.endDate) == end
}

/** Caller holds a read transaction so boundary bytes and all-quality adjacency describe one snapshot. */
internal suspend fun verifiedParkedReceiptBinding(
    dao: DriveSummaryDao, identity: ParkedPageIdentity, local: ParkedDetailData
): ParkedReceiptBinding? {
    if (!identity.stillCurrent()) return null
    val context = identity.context ?: return null
    if (context.localHistoryCarId != identity.localHistoryCarId) return null
    val older = dao.get(context.localHistoryCarId, local.olderDriveId) ?: return null
    val newer = dao.get(context.localHistoryCarId, local.newerDriveId) ?: return null
    val proof = localParkedSourceProof(local.olderDriveId, local.newerDriveId, older, newer) ?: return null
    if (hasInterveningLocalDrive(dao, context.localHistoryCarId, local.olderDriveId, local.newerDriveId, older, newer)) return null
    val expected = buildLocalParkedDetail(local.olderDriveId, local.newerDriveId, older, newer) ?: return null
    if (!sameParkedWindow(expected, local) || !identity.stillCurrent()) return null
    return ParkedReceiptBinding(context, local.olderDriveId, local.newerDriveId, proof.source, proof.sourceInstanceId,
        expected.startDate, expected.endDate, older.apiEvidence ?: return null, newer.apiEvidence ?: return null)
}

internal fun localParkedSourceProof(
    olderDriveId: Int,
    newerDriveId: Int,
    older: DriveSummary,
    newer: DriveSummary
): ParkedSourceProof? {
    if (older.carId != newer.carId || older.driveId != olderDriveId || newer.driveId != newerDriveId ||
        older.qualityState == "quarantined" || newer.qualityState == "quarantined") return null
    val olderRaw = older.toRawAnalysisDriveData()
    val newerRaw = newer.toRawAnalysisDriveData()
    fun matchesReceipt(raw: com.matelink.data.api.models.DriveData, row: DriveSummary): Boolean {
        val start = parseIsoInstant(row.startDate) ?: return false
        val end = parseIsoInstant(row.endDate) ?: return false
        return raw.driveId == row.driveId && parseIsoInstant(raw.startDate) == start &&
            parseIsoInstant(raw.endDate) == end
    }
    if (!matchesReceipt(olderRaw, older) || !matchesReceipt(newerRaw, newer)) return null
    return sharedParkedSource(olderRaw, newerRaw)
}

internal suspend fun readLocalParkedCharges(
    driveDao: DriveSummaryDao,
    chargeDao: ChargeSummaryDao,
    localHistoryCarId: Int,
    olderDriveId: Int,
    newerDriveId: Int
): List<LinkedCharge> {
    val older = driveDao.get(localHistoryCarId, olderDriveId) ?: return emptyList()
    val newer = driveDao.get(localHistoryCarId, newerDriveId) ?: return emptyList()
    val proof = localParkedSourceProof(olderDriveId, newerDriveId, older, newer) ?: return emptyList()
    if (hasInterveningLocalDrive(driveDao, localHistoryCarId, olderDriveId, newerDriveId, older, newer))
        return emptyList()
    return chargeDao.getAllForCar(localHistoryCarId).mapNotNull { row ->
        runCatching { row.toParkedLinkedCharge(proof) }.getOrNull()
    }
}

internal suspend fun readLocalParkedDetail(
    dao: DriveSummaryDao,
    localHistoryCarId: Int,
    olderDriveId: Int,
    newerDriveId: Int
): ParkedDetailData? {
    val older = dao.get(localHistoryCarId, olderDriveId) ?: return null
    val newer = dao.get(localHistoryCarId, newerDriveId) ?: return null
    if (hasInterveningLocalDrive(dao, localHistoryCarId, olderDriveId, newerDriveId, older, newer)) return null
    return buildLocalParkedDetail(olderDriveId, newerDriveId, older, newer)
}

/** Same account/source/car scoped Room query as the visible parked consumer; all qualities block. */
private suspend fun hasInterveningLocalDrive(
    dao: DriveSummaryDao, localHistoryCarId: Int, olderDriveId: Int, newerDriveId: Int,
    older: DriveSummary, newer: DriveSummary
): Boolean {
    val olderStart = parseIsoInstant(older.startDate) ?: return true
    val olderEnd = parseIsoInstant(older.endDate) ?: return true
    val newerStart = parseIsoInstant(newer.startDate) ?: return true
    val newerEnd = parseIsoInstant(newer.endDate) ?: return true
    if (!olderStart.isBefore(olderEnd) || !olderEnd.isBefore(newerStart) ||
        !newerStart.isBefore(newerEnd)) return true
    return dao.hasInterveningParkedDrive(localHistoryCarId, olderDriveId, newerDriveId,
        olderStart.toString(), newerEnd.toString())
}

internal fun buildLocalParkedDetail(
    olderDriveId: Int,
    newerDriveId: Int,
    older: DriveSummary,
    newer: DriveSummary
): ParkedDetailData? {
    if (localParkedSourceProof(olderDriveId, newerDriveId, older, newer) == null) return null
    val start = parseIsoInstant(older.endDate) ?: return null
    val end = parseIsoInstant(newer.startDate) ?: return null
    if (!start.isBefore(end)) return null
    val observedOlder = older.toAnalysisDriveData()
    val observedNewer = newer.toAnalysisDriveData()
    val startBattery = observedOlder.endBatteryLevel?.takeIf { it in 0..100 }
    val endBattery = observedNewer.startBatteryLevel?.takeIf { it in 0..100 }
    return ParkedDetailData(
        olderDriveId = olderDriveId,
        newerDriveId = newerDriveId,
        startDate = older.endDate,
        endDate = newer.startDate,
        address = observedOlder.endAddress?.toChineseDisplayAddress() ?: observedOlder.endAddress,
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

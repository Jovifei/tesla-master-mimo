package com.matelink.data.repository

import androidx.room.withTransaction
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.analytics.DriveEnergyCalculator
import com.matelink.domain.analytics.DriveEnergySource
import com.matelink.domain.analytics.DrivePowerSample
import com.matelink.domain.analytics.ResolvedDriveEnergy
import com.matelink.domain.analytics.asCachedDetail
import com.matelink.domain.analytics.resolveDriveEnergy
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.analytics.toRawAnalysisDriveData
import com.matelink.domain.analytics.withResolvedDriveEnergy
import com.matelink.domain.history.isAnalysisEligible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class DriveEnergyAttemptKey(val identity: String, val receiptDigest: String)

internal class DriveEnergyRequestBudget(private val maxRequests: Int = 20) {
    private var used = 0
    private var stopped = false

    init { require(maxRequests >= 0) }

    @Synchronized fun tryAcquire(): Boolean {
        if (stopped || used >= maxRequests) return false
        used++
        return true
    }

    @Synchronized fun stop() { stopped = true }
}

internal fun DriveSummary.canEnrichPower(proof: VerifiedHistoryReadContext): Boolean {
    val qualified = toAnalysisDriveData()
    if (!isAnalysisEligible(qualityState, qualityReason) ||
        qualified.netEnergyKwh != null || qualified.efficiencyWhKm != null) return false
    val raw = toRawAnalysisDriveData()
    val endpoints = qualified.startBatteryLevel != null && qualified.endBatteryLevel != null
    val scalar = raw.energyConsumedNet
    val hasScalar = scalar != null && scalar.isFinite() && scalar > 0.0
    return raw.energyContract == null && (
        raw.source == "teslamate_archive" ||
            (raw.source == null && proof.context.connectionSource == HistoryConnectionSource.SELF_HOSTED) ||
            ((raw.source == "telemetry_mqtt" || raw.source == "fleet_api") && (endpoints || hasScalar))
        )
}

/** Power integrals require full coverage; SOC/capacity and energy deltas require observed endpoints. */
private suspend fun fullPowerEstimate(detail: DriveDetail) = withContext(Dispatchers.Default) {
    val cleanDetail = if (detail.energyContract?.netEnergy?.quality == "unknown" &&
        detail.energyContract?.netEnergy?.reason in setOf("no_verified_driving_net_energy", "local_detail_scope_or_window_mismatch"))
        detail.copy(energyContract = null) else detail
    if (cleanDetail.energyContract != null) return@withContext null
    val resolved = cleanDetail.resolveDriveEnergy()
    val powerComplete = if (resolved.estimate.source == DriveEnergySource.POWER_SAMPLES) {
        val positions = detail.positions ?: return@withContext null
        DriveEnergyCalculator.calculate(
            positions.map { DrivePowerSample(it.date, it.power) }, detail.startDate, detail.endDate).complete
    } else true
    resolved.takeIf {
        powerComplete && it.estimate.energyKwh != null && it.estimate.source != DriveEnergySource.UNAVAILABLE
    }
}

/** One frozen window, no automatic continuation, and no more than 20 network requests. */
internal suspend fun enrichDriveHistoryEnergy(
    proof: VerifiedHistoryReadContext,
    rows: List<DriveSummary>,
    repository: TeslamateRepository,
    database: StatsDatabase,
    pointStore: HistoryDetailRepository,
    attempted: MutableMap<DriveEnergyAttemptKey, Any>,
    budget: DriveEnergyRequestBudget = DriveEnergyRequestBudget(),
    ensureScope: suspend () -> Unit
) {
    val dao = database.driveSummaryDao()
    for (row in rows) {
        ensureScope()
        if (!row.canEnrichPower(proof)) continue
        val identity = row.verifiedDetailIdentity(proof.context) ?: continue
        val digest = driveSourceReceiptDigest(row) ?: continue
        val key = DriveEnergyAttemptKey(identity.identityKey, digest)

        suspend fun ensureCandidate() {
            ensureScope()
            val latest = dao.get(proof.context.localHistoryCarId, row.driveId)
            ensureScope()
            if (latest == null || latest.verifiedDetailIdentity(proof.context)?.identityKey != key.identity ||
                driveSourceReceiptDigest(latest) != key.receiptDigest)
                throw CancellationException("Drive history receipt changed")
        }

        suspend fun mergeEnergySummary(detail: DriveDetail, energy: ResolvedDriveEnergy): Boolean =
            database.withTransaction {
                ensureCandidate()
                val latest = dao.get(proof.context.localHistoryCarId, row.driveId) ?: return@withTransaction false
                if (!latest.canEnrichPower(proof)) return@withTransaction true
                if (!identity.matches(detail.driveId, detail.source, detail.sourceInstanceId,
                        detail.startDate, detail.endDate, detail.sourceId, detail.sourceRecordId)) return@withTransaction false
                val summaryDetail = latest.toAnalysisDriveData().asCachedDetail().copy(
                    startDate = detail.startDate, endDate = detail.endDate, source = detail.source,
                    sourceInstanceId = detail.sourceInstanceId, sourceId = detail.sourceId,
                    sourceRecordId = detail.sourceRecordId, energyConsumedNet = null,
                    consumptionNet = null, energyContract = null)
                val resolved = latest.withResolvedDriveEnergy(summaryDetail, energy)
                ensureCandidate()
                dao.upsert(latest.copy(
                    energyConsumed = resolved.energyConsumed, efficiency = resolved.efficiency,
                    energySource = resolved.energySource, energyCoverageSeconds = resolved.energyCoverageSeconds,
                    energyCoverageRatio = resolved.energyCoverageRatio, apiEvidence = resolved.apiEvidence))
                ensureCandidate()
                true
            }

        ensureCandidate()
        val summaryDetail = row.toAnalysisDriveData().asCachedDetail()
        val summaryEnergy = fullPowerEstimate(summaryDetail)
        ensureCandidate()
        if (summaryEnergy != null && mergeEnergySummary(summaryDetail, summaryEnergy)) {
            synchronized(attempted) { attempted[key] = Any() }
            continue
        }
        val cached = pointStore.readDrive(identity)
        ensureCandidate()
        val detail = cached.detail?.takeIf {
            cached.receipt?.responseComplete == true && cached.reason != "detail_partial_newer"
        }
        val cachedEnergy = detail?.let { fullPowerEstimate(it) }
        ensureCandidate()
        if (detail != null && cachedEnergy != null) {
            val accepted = mergeEnergySummary(detail, cachedEnergy)
            if (!accepted) return
            synchronized(attempted) { attempted[key] = Any() }
            continue
        }
        if (!proof.remoteAuthorized) continue
        val attempt = Any()
        val admitted = synchronized(attempted) {
            if (key in attempted || !budget.tryAcquire()) false
            else { attempted[key] = attempt; true }
        }
        if (!admitted) continue
        try {
            ensureCandidate()
            val result = withTimeoutOrNull(10_000L) {
                repository.getDriveDetail(proof.context.remoteApiCarId, row.driveId)
            }
            if (result == null) { budget.stop(); return }
            ensureCandidate()
            if (result !is ApiResult.Success) { budget.stop(); return }
            val received = result.data
            if (!identity.matches(received.driveId, received.source, received.sourceInstanceId,
                    received.startDate, received.endDate, received.sourceId, received.sourceRecordId)) {
                budget.stop()
                return
            }
            val energy = fullPowerEstimate(received) ?: continue
            ensureCandidate()
            val accepted = mergeEnergySummary(received, energy)
            if (!accepted) return
            ensureCandidate()
        } catch (e: CancellationException) {
            synchronized(attempted) {
                if (attempted[key] === attempt) attempted.remove(key)
            }
            throw e
        } catch (_: Exception) { budget.stop(); return }
    }
}

package com.matelink.ui.screens.readiness

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.DataReadiness
import com.matelink.data.local.*
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.dao.TpmsPressureSampleDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class DiagnosticResult { UNKNOWN, PASS, WAITING }
enum class DiagnosticStep { AUTHORIZATION, VEHICLE, RECEIVE, QUALIFICATION, HISTORY, DISPLAY }

data class DiagnosticFact(
    val step: DiagnosticStep,
    val result: DiagnosticResult = DiagnosticResult.UNKNOWN,
    val source: String = "unknown",
    val observedAt: String? = null,
    val detail: String = "unknown",
    val olderReceipt: Boolean = false,
    val officialFleet: OfficialFleetDiagnosticPresentation? = null
)

data class ReadinessLocalEvidence(
    val latestVerifiedTpms: Long? = null,
    val qualifiedDrives: Int = 0,
    val qualifiedCharges: Int = 0,
    val pendingSystem: Int = 0,
    val handledSystem: Int = 0,
    val handledApp: Int = 0,
    val notificationsEnabled: Boolean? = null,
    val readAt: Long = System.currentTimeMillis(),
    val scopeVerified: Boolean = false,
    val persistedDrives: Int? = null,
    val persistedCharges: Int? = null
)

fun unknownDiagnosticFacts() = DiagnosticStep.entries.map(::DiagnosticFact)

fun withDiagnosticPermissionBlocker(facts: List<DiagnosticFact>): List<DiagnosticFact> = facts.map {
    if (it.step != DiagnosticStep.AUTHORIZATION) it else it.copy(
        result = DiagnosticResult.WAITING, source = "scoped_response", detail = "permission_required")
}

/** Only current, source-qualified facts promote their own step. No overall PASS exists. */
fun resolveReadinessDiagnostics(
    readiness: DataReadiness?, scopeCurrent: Boolean, identityVerified: Boolean,
    local: ReadinessLocalEvidence? = null
): List<DiagnosticFact> {
    if (!scopeCurrent) return unknownDiagnosticFacts()
    val real = readiness?.takeIf { it.capabilityVersion == 1 && it.items.none { item -> item.source == "mock_fixture" } }
    val facts = unknownDiagnosticFacts().associateBy { it.step }.toMutableMap()
    fun put(step: DiagnosticStep, result: DiagnosticResult, source: String, at: String?, detail: String) {
        val atMillis = at?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        val older = step == DiagnosticStep.RECEIVE && atMillis != null &&
            (local?.readAt ?: System.currentTimeMillis()) - atMillis > 30 * 60 * 1000L
        facts[step] = DiagnosticFact(step, result, source, at, detail, older)
    }
    if (identityVerified && real != null) {
        put(DiagnosticStep.AUTHORIZATION, DiagnosticResult.PASS, "scoped_response", null, "scoped_authorization")
        put(DiagnosticStep.VEHICLE, DiagnosticResult.PASS, "verified_identity", null, "scoped_identity")
        if (real.items.any { it.status == "permission_required" }) {
            put(DiagnosticStep.AUTHORIZATION, DiagnosticResult.WAITING, "scoped_response", null, "permission_required")
        }
    }
    // Legacy capability availability and server aggregate counts are not a receipt.
    val received = real?.items?.filter { it.source in setOf("telemetry_mqtt", "fleet_api") && it.status == "available" }
        ?.mapNotNull { it.lastObservedAt?.let { at -> runCatching { java.time.Instant.parse(at) }.getOrNull() } }
        ?.filter { !it.isAfter(java.time.Instant.ofEpochMilli(local?.readAt ?: System.currentTimeMillis())) }
        ?.maxOrNull()
    if (identityVerified && real?.items?.any { it.status == "waiting_vehicle" } == true) {
        put(DiagnosticStep.RECEIVE, DiagnosticResult.WAITING, "scoped_response", null, "waiting_vehicle")
    }
    if (identityVerified && received != null) {
        put(DiagnosticStep.RECEIVE, DiagnosticResult.PASS, "scoped_response", received.toString(), "source_observation")
    }
    // Offline local evidence keeps its own verified account/origin namespace.
    // A mismatching live response cannot silently inherit an old cached namespace.
    val localAllowed = identityVerified || (readiness == null && local?.scopeVerified == true)
    if (localAllowed && local != null) {
        if (real == null && local.scopeVerified) {
            put(DiagnosticStep.VEHICLE, DiagnosticResult.PASS, "verified_identity", null, "cached_identity")
        }
        val tpmsAt = local.latestVerifiedTpms?.takeIf { it > 0 && it <= local.readAt }
        val qualified = tpmsAt != null || local.qualifiedDrives > 0 || local.qualifiedCharges > 0
        if (tpmsAt != null) put(DiagnosticStep.RECEIVE, DiagnosticResult.PASS, "room_verified", java.time.Instant.ofEpochMilli(tpmsAt).toString(), "collector_receipt")
        put(DiagnosticStep.QUALIFICATION, if (qualified) DiagnosticResult.PASS else DiagnosticResult.UNKNOWN,
            "room_projection", tpmsAt?.let { java.time.Instant.ofEpochMilli(it).toString() },
            "qualified:${if (tpmsAt != null) 1 else 0}:${local.qualifiedDrives}:${local.qualifiedCharges}")
        val persisted = tpmsAt != null || (local.persistedDrives ?: 0) > 0 || (local.persistedCharges ?: 0) > 0
        val historyResult = when {
            persisted -> DiagnosticResult.PASS
            local.persistedDrives != null && local.persistedCharges != null -> DiagnosticResult.WAITING
            else -> DiagnosticResult.UNKNOWN
        }
        put(DiagnosticStep.HISTORY, historyResult, "room_verified", null,
            "history:${if (tpmsAt != null) 1 else 0}:${local.persistedDrives ?: 0}:${local.persistedCharges ?: 0}")
        // Handling is not natural human receipt. Permission alone never produces PASS.
        put(DiagnosticStep.DISPLAY,
            if (local.handledSystem > 0 || local.handledApp > 0) DiagnosticResult.PASS else DiagnosticResult.UNKNOWN,
            "notification_journal", null,
            "delivery:${local.notificationsEnabled}:${local.pendingSystem}:${local.handledSystem}:${local.handledApp}")
    }
    return DiagnosticStep.entries.map { facts.getValue(it) }
}

interface ReadinessEvidenceSession {
    val requestFence: com.matelink.data.api.ArchiveEnrollmentRequestFence? get() = null
    val connectionSource: HistoryConnectionSource
    /** Captured existing mapping only; reading diagnostics never allocates identity. */
    val cachedContext: VehicleContext? get() = null
    suspend fun isCurrent(): Boolean
    fun verifies(readiness: DataReadiness?, car: CarData? = null): Boolean
    suspend fun localEvidence(): ReadinessLocalEvidence?
}

interface ReadinessEvidenceSource {
    val scopeChanges: Flow<Unit>
    suspend fun capture(carId: Int): ReadinessEvidenceSession?
}

/** Read-only: cached identity, scalar Room queries and the existing durable journal.
 * No provider call, identity allocation, wake, import, migration or configuration. */
@Singleton
class ReadOnlyReadinessDiagnostics @Inject constructor(
    private val contexts: VehicleContextRepository,
    private val tpms: TpmsPressureSampleDao,
    private val drives: DriveSummaryDao,
    private val charges: ChargeSummaryDao,
    private val journal: CompletedChargeEventStore,
    @ApplicationContext private val context: Context
) : ReadinessEvidenceSource {
    override val scopeChanges: Flow<Unit> get() = contexts.historyScopeChanges.map { Unit }

    override suspend fun capture(carId: Int): ReadinessEvidenceSession? {
        val scope = contexts.captureReadScope()
        val vehicle = contexts.cachedContextForRemote(carId, scope)
        return object : ReadinessEvidenceSession {
            override val requestFence = if (vehicle != null && scope.source == HistoryConnectionSource.CLOUD && scope.accountNamespace != null)
                com.matelink.data.api.ArchiveEnrollmentRequestFence(scope.accountNamespace, scope.sessionGeneration) {
                    try { contexts.captureReadScope() == scope && contexts.isHistoryContextCurrent(scope, vehicle) }
                    catch (_: HistoryIdentityUnavailableException) { false }
                } else null
            override val connectionSource = scope.source
            override val cachedContext = vehicle
            override suspend fun isCurrent(): Boolean = try {
                contexts.captureReadScope() == scope &&
                    (vehicle == null || contexts.isHistoryContextCurrent(scope, vehicle))
            } catch (_: HistoryIdentityUnavailableException) { false }

            override fun verifies(readiness: DataReadiness?, car: CarData?): Boolean =
                vehicle != null && verifiedFleetReadiness(contexts, scope, vehicle, readiness, car)


            override suspend fun localEvidence(): ReadinessLocalEvidence? {
                if (vehicle == null || !isCurrent()) return null
                val now = System.currentTimeMillis()
                val id = vehicle.localHistoryCarId
                val latest = tpms.latestDiagnosticReceipt(id, now)
                val driveCount = drives.countDiagnosticEnergy(id)
                val chargeCount = charges.countDiagnosticEnergy(id)
                val storedDrives = drives.countDiagnosticHistory(id)
                val storedCharges = charges.countDiagnosticHistory(id)
                val key = completionScopeKey(scope, id, carId)
                val events = journal.events(id, key, "drive").first() + journal.events(id, key, "charge").first()
                if (!isCurrent()) return null
                val scoped = events.filter { it.remoteCarId == carId && it.scopeKey == key }
                val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                if (!isCurrent()) return null
                return ReadinessLocalEvidence(latest, driveCount, chargeCount,
                    scoped.count { !it.systemConsumed }, scoped.count { it.systemConsumed }, scoped.count { it.appConsumed },
                    enabled, now, scopeVerified = true, persistedDrives = storedDrives, persistedCharges = storedCharges)
            }
        }
    }
}

/** Verify the wire digest and independently bind the provider ID to the cached local vehicle. */
internal fun verifiedFleetReadiness(
    contexts: VehicleContextRepository, scope: HistoryReadScope, vehicle: VehicleContext,
    readiness: DataReadiness?, car: CarData?
): Boolean {
    if (scope.source != HistoryConnectionSource.CLOUD || readiness == null || car == null ||
        car.carId != vehicle.remoteApiCarId || readiness.capabilityVersion != 1 ||
        readiness.items.any { it.source == "mock_fixture" || it.source == "legacy_compatibility" }) return false
    val owner = scope.accountNamespace?.takeIf { it.isNotBlank() } ?: return false
    val provider = car.vehicleUid?.takeIf { com.matelink.data.api.models.validHistoryVehicleUid(it) } ?: return false
    val bytes = "jourvolt.vehicle.uid.v1\u0000$owner\u0000$provider".toByteArray(Charsets.UTF_8)
    val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    if (readiness.vehicleUid != expected) return false
    return runCatching {
        contexts.cachedProofForCar(car, scope)?.localHistoryCarId == vehicle.localHistoryCarId
    }.getOrDefault(false)
}

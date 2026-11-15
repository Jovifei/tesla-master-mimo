package com.matelink.data.repository

import androidx.room.withTransaction
import com.matelink.data.api.models.BatteryTrendBoundary
import com.matelink.data.api.ArchiveEnrollmentRequestFence
import com.matelink.data.api.models.BatteryTrendEvidenceItem
import com.matelink.data.api.models.BatteryTrendEvidencePage
import com.matelink.data.api.models.BatteryTrendObservation
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.entity.BatteryTrendEvidence
import com.matelink.domain.analytics.BatteryTrendSample
import com.matelink.domain.analytics.qualifiedBatteryTrendSample
import com.matelink.util.parseIsoDateTime
import com.squareup.moshi.Moshi
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

internal data class BatteryEvidenceSnapshot(val samples: List<BatteryTrendSample>, val isCached: Boolean)
internal const val BATTERY_EVIDENCE_SOURCE_CONFLICT = "battery_evidence_source_conflict"

/** Separate from ordinary drive history: never clears or rewrites a user history row. */
@Singleton
class BatteryTrendEvidenceRepository internal constructor(
    private val database: StatsDatabase,
    private val api: TeslamateRepository,
    private val history: UnifiedHistoryRepository,
    private val vehicles: VehicleContextRepository,
    moshi: Moshi,
    private val pageTimeoutMs: Long,
    private val totalTimeoutMs: Long
) {
    @Inject constructor(database: StatsDatabase, api: TeslamateRepository, history: UnifiedHistoryRepository,
                        vehicles: VehicleContextRepository, moshi: Moshi) :
        this(database, api, history, vehicles, moshi, 20_000L, 300_000L)

    init { require(pageTimeoutMs > 0 && totalTimeoutMs > 0) }

    private val adapter = moshi.adapter(BatteryTrendObservation::class.java)

    private fun fingerprint(scope: HistoryReadScope, vehicle: VehicleContext): String? {
        if (scope.source != HistoryConnectionSource.CLOUD || vehicle.remoteApiCarId <= 0 ||
            vehicle.localHistoryCarId >= 0 || !vehicles.isHistoryContextCurrentNow(scope, vehicle)) return null
        val digest = vehicles.cachedVerifiedStatusIdentityDigest(vehicle.remoteApiCarId, scope, vehicle) ?: return null
        val parts = listOf("battery-trend-v1", scope.source.name, scope.effectiveApiOrigin,
            scope.accountNamespace.orEmpty(), digest, vehicle.localHistoryCarId.toString())
        val framed = parts.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(framed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun requireCurrent(scope: HistoryReadScope, vehicle: VehicleContext, expected: String) {
        currentCoroutineContext().ensureActive()
        if (vehicles.captureReadScope() != scope || fingerprint(scope, vehicle) != expected)
            throw CancellationException("Battery evidence identity changed")
        currentCoroutineContext().ensureActive()
    }

    internal suspend fun loadCached(scope: HistoryReadScope, vehicle: VehicleContext): ApiResult<BatteryEvidenceSnapshot?> {
        val identity = fingerprint(scope, vehicle) ?: return ApiResult.Success(null)
        requireCurrent(scope, vehicle, identity)
        val rows = database.batteryTrendEvidenceDao().allFor(identity, vehicle.localHistoryCarId)
        requireCurrent(scope, vehicle, identity)
        if (sourceCount(rows) > 1) return sourceConflict()
        return try {
            ApiResult.Success(rows.takeIf { it.isNotEmpty() }?.let { BatteryEvidenceSnapshot(decode(it), isCached = true) })
        } catch (_: IllegalStateException) { invalidEvidence() }
    }

    internal suspend fun refresh(scope: HistoryReadScope, vehicle: VehicleContext): ApiResult<BatteryEvidenceSnapshot?> =
        withTimeoutOrNull(totalTimeoutMs) { refreshWithinDeadline(scope, vehicle) } ?: evidenceTimeout()

    private suspend fun refreshWithinDeadline(scope: HistoryReadScope, vehicle: VehicleContext): ApiResult<BatteryEvidenceSnapshot?> {
        currentCoroutineContext().ensureActive()
        if (scope.source != HistoryConnectionSource.CLOUD || vehicles.captureReadScope() != scope ||
            vehicle.remoteApiCarId <= 0 || vehicle.localHistoryCarId >= 0 ||
            !vehicles.isHistoryContextCurrentNow(scope, vehicle)) return historyIdentityUnavailableError()
        val resolved = when (val result = history.resolveContext(vehicle.remoteApiCarId)) {
            is ApiResult.Error -> return result
            is ApiResult.Success -> result.data
        }
        if (!resolved.remoteAuthorized || resolved.scope != scope ||
            resolved.context.localHistoryCarId != vehicle.localHistoryCarId ||
            !history.isContextCurrent(resolved)) return historyIdentityUnavailableError()
        // A legacy remote→local mapping is not UID proof; discovery above must establish it first.
        val identity = fingerprint(scope, resolved.context) ?: return historyIdentityUnavailableError()
        requireCurrent(scope, vehicle, identity)
        val account = scope.accountNamespace ?: return historyIdentityUnavailableError()

        val staged = linkedMapOf<List<String>, BatteryTrendEvidence>()
        var cursor: Long = 0
        var snapshot: Long? = null
        repeat(MAX_PAGES) {
            requireCurrent(scope, vehicle, identity)
            val result = withTimeoutOrNull(pageTimeoutMs) {
                val caller = currentCoroutineContext()
                val fence = ArchiveEnrollmentRequestFence(account, scope.sessionGeneration) {
                    if (!caller.isActive) false else try {
                        vehicles.captureReadScope() == scope && fingerprint(scope, vehicle) == identity
                    } catch (_: com.matelink.data.local.HistoryIdentityUnavailableException) { false }
                }
                api.getBatteryTrendEvidence(vehicle.remoteApiCarId,
                    if (cursor == 0L) null else cursor.toString(), snapshot, fence)
            } ?: return evidenceTimeout()
            requireCurrent(scope, vehicle, identity)
            val page = when (result) {
                is ApiResult.Error -> return result
                is ApiResult.Success -> result.data
            }
            val max = page.snapshotMaxSeq ?: return invalidEvidence()
            if (!validPage(page, snapshot, cursor)) return invalidEvidence()
            if (snapshot == null) snapshot = max
            for (item in page.observations.orEmpty()) {
                val row = encode(identity, vehicle.localHistoryCarId, item) ?: return invalidEvidence()
                val key = listOf(row.sourceInstanceId, row.sourceVehicleId, row.sourceRecordId)
                val previous = staged.putIfAbsent(key, row)
                if (previous != null && previous != row) return invalidEvidence()
            }
            if (page.complete == true) {
                if (sourceCount(staged.values) > 1) return sourceConflict()
                val existing = try {
                    database.withTransaction {
                        requireCurrent(scope, vehicle, identity)
                        val dao = database.batteryTrendEvidenceDao()
                        val prior = dao.allFor(identity, vehicle.localHistoryCarId)
                        if (sourceCount(prior) > 1 ||
                            (prior.isNotEmpty() && staged.isNotEmpty() &&
                                sourceKey(prior.first()) != sourceKey(staged.values.first())))
                            throw EvidenceSourceConflict()
                        val byKey = prior.associateBy { listOf(it.sourceInstanceId, it.sourceVehicleId, it.sourceRecordId) }
                        if (staged.any { (key, row) -> byKey[key]?.let { it != row } == true })
                            throw EvidenceConflict()
                        dao.insertAll(staged.values.toList())
                        requireCurrent(scope, vehicle, identity)
                        dao.allFor(identity, vehicle.localHistoryCarId)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: EvidenceSourceConflict) { return sourceConflict() }
                catch (_: EvidenceConflict) { return invalidEvidence() }
                requireCurrent(scope, vehicle, identity)
                if (existing.isEmpty()) return ApiResult.Success(null)
                val allSeen = existing.all { row ->
                    staged[listOf(row.sourceInstanceId, row.sourceVehicleId, row.sourceRecordId)] == row
                }
                return ApiResult.Success(BatteryEvidenceSnapshot(decode(existing), isCached = !allSeen))
            }
            cursor = page.nextCursor!!.toLong()
        }
        return invalidEvidence()
    }

    private fun encode(scope: String, carId: Int, item: BatteryTrendEvidenceItem): BatteryTrendEvidence? {
        val instance = item.sourceInstanceId?.takeIf(::validSource) ?: return null
        val vehicle = item.sourceVehicleId?.takeIf(::validSource) ?: return null
        val hash = item.payloadSha256?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        val observation = item.observation?.takeIf(::validObservation) ?: return null
        val record = observation.sourceRecordId ?: return null
        val json = adapter.toJson(observation)
        if (json.toByteArray(Charsets.UTF_8).size > MAX_OBSERVATION_BYTES) return null
        return BatteryTrendEvidence(scope, carId, instance, vehicle, record, hash, json)
    }

    private fun decode(rows: List<BatteryTrendEvidence>): List<BatteryTrendSample> = rows.mapNotNull { row ->
        if (row.localHistoryCarId >= 0 || !row.scopeFingerprint.matches(Regex("[0-9a-f]{64}")) ||
            !validSource(row.sourceInstanceId) || !validSource(row.sourceVehicleId) ||
            !row.payloadSha256.matches(Regex("[0-9a-f]{64}")))
            throw IllegalStateException("Battery evidence cache invalid")
        val observation = runCatching { adapter.fromJson(row.observationJson) }.getOrNull()
            ?.takeIf { it.sourceRecordId == row.sourceRecordId && validObservation(it) }
            ?: throw IllegalStateException("Battery evidence cache invalid")
        val temperature = observation.outsideTempAvgC
        fun sample(date: String?, boundary: BatteryTrendBoundary?): BatteryTrendSample? =
            qualifiedBatteryTrendSample(parseIsoDateTime(date)?.toLocalDate(), boundary?.socPercent,
                boundary?.ratedRangeKm, temperature)
        sample(observation.startedAt, observation.start) ?: sample(observation.endedAt, observation.end)
    }

    private class EvidenceConflict : IllegalStateException()
    private class EvidenceSourceConflict : IllegalStateException()

    private companion object {
        const val MAX_PAGES = 200
        const val MAX_OBSERVATION_BYTES = 16 * 1024
    }
}

private fun invalidEvidence(): ApiResult.Error =
    ApiResult.Error("Battery trend evidence invalid", kind = ApiErrorKind.INVALID_RESPONSE)

private fun evidenceTimeout(): ApiResult.Error =
    ApiResult.Error("Battery trend evidence timeout", kind = ApiErrorKind.NETWORK)

private fun sourceConflict(): ApiResult.Error =
    ApiResult.Error("Battery trend evidence source conflict", details = BATTERY_EVIDENCE_SOURCE_CONFLICT,
        kind = ApiErrorKind.INVALID_RESPONSE)

private fun sourceKey(row: BatteryTrendEvidence): Pair<String, String> = row.sourceInstanceId to row.sourceVehicleId

private fun sourceCount(rows: Collection<BatteryTrendEvidence>): Int = rows.map(::sourceKey).distinct().size

private fun validSource(value: String): Boolean = value.isNotEmpty() && value == value.trim() &&
    value.toByteArray(Charsets.UTF_8).size <= 128

private fun validPage(page: BatteryTrendEvidencePage, fixed: Long?, cursor: Long): Boolean {
    val max = page.snapshotMaxSeq ?: return false
    val units = page.units ?: return false
    val observations = page.observations ?: return false
    val complete = page.complete ?: return false
    if (page.schemaVersion != 1 || units.ratedRange != "km" || units.temperature != "C" ||
        units.soc != "percent" || max < 0 || (fixed != null && max != fixed) || cursor > max ||
        observations.size > 50 || (max == 0L && observations.isNotEmpty())) return false
    if (complete) return page.nextCursor == null
    val next = page.nextCursor ?: return false
    if (!next.matches(Regex("[1-9][0-9]{0,18}"))) return false
    val value = next.toLongOrNull() ?: return false
    return value > cursor && value <= max
}

private fun validObservation(value: BatteryTrendObservation): Boolean {
    val record = value.sourceRecordId ?: return false
    if (!record.matches(Regex("teslamate:drive:[1-9][0-9]{0,18}")) ||
        record.substringAfterLast(':').toLongOrNull()?.let { it > 0 } != true) return false
    fun instant(raw: String?): Instant? = raw?.takeIf { it.length <= 40 && it.endsWith("Z") }
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val start = instant(value.startedAt) ?: return false
    val end = instant(value.endedAt) ?: return false
    if (end.isBefore(start)) return false
    val temperature = value.outsideTempAvgC
    if (temperature == null) {
        if (value.temperatureSource != null) return false
    } else if (!temperature.isFinite() || value.temperatureSource != "teslamate_drive_average") return false
    fun boundary(part: BatteryTrendBoundary?, at: Instant, source: String): Boolean {
        if (part == null) return true
        val position = part.socPositionId
        val observed = part.socPositionObservedAt
        if ((position == null) != (observed == null)) return false
        if (position != null) {
            val time = instant(observed) ?: return false
            if (position <= 0 || Duration.between(at, time).abs() > Duration.ofSeconds(1)) return false
        }
        if (part.socPercent?.let { !it.isFinite() || it < 0 || it > 100 || position == null } == true) return false
        val range = part.ratedRangeKm
        return if (range == null) part.ratedRangeSource == null && part.ratedRangeObservedAt == null
        else range.isFinite() && range >= 0 && part.ratedRangeSource == source && part.ratedRangeObservedAt == null
    }
    return boundary(value.start, start, "teslamate_drive_start_summary") &&
        boundary(value.end, end, "teslamate_drive_end_summary")
}

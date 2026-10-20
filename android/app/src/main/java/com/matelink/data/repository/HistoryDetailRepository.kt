package com.matelink.data.repository

import com.matelink.data.api.models.resolveSourceRecordIdentity
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.dao.HistoryDetailDao
import com.matelink.data.local.dao.HistoryDetailInsertResult
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.HistoryDetailReceipt
import com.matelink.data.local.entity.HistoryDetailReceiptState
import com.matelink.domain.analytics.toRawAnalysisChargeData
import com.matelink.domain.analytics.toRawAnalysisDriveData
import com.matelink.util.parseIsoInstant
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** A numeric API ID alone can never identify a locally reusable detail. */
class HistoryDetailIdentity private constructor(
    val localHistoryCarId: Int,
    val scopeFingerprint: String,
    val kind: String,
    val recordId: Int,
    val sourceKey: String,
    val sourceInstanceKey: String,
    val sourceIdKey: String,
    val startInstant: String,
    val endInstant: String,
    val identityKey: String
) {
    companion object {
        const val CHARGE = "charge"
        const val DRIVE = "drive"

        fun create(
            context: VehicleContext, kind: String, recordId: Int,
            source: String?, sourceInstanceId: String?, startDate: String?, endDate: String?,
            sourceId: String? = null, sourceRecordId: String? = null
        ): HistoryDetailIdentity {
            require(kind == CHARGE || kind == DRIVE)
            require(recordId >= 0 && context.stableIdentity.isNotBlank())
            val start = parseIsoInstant(startDate) ?: throw IllegalArgumentException("detail_start_missing")
            val end = parseIsoInstant(endDate) ?: throw IllegalArgumentException("detail_end_missing")
            require(!end.isBefore(start)) { "detail_window_invalid" }
            val scope = digest(context.stableIdentity, context.connectionSource.name, context.serverIdentity)
            val sourceKey = source?.trim().orEmpty()
            require(sourceKey.isNotEmpty()) { "detail_source_missing" }
            val instanceKey = sourceInstanceId?.trim().orEmpty()
            val record = resolveSourceRecordIdentity(sourceId, sourceRecordId)
            require(!record.conflicted) { "detail_source_record_conflict" }
            val sourceIdKey = record.value?.trim().orEmpty()
            val key = digest(context.localHistoryCarId.toString(), scope, kind, recordId.toString(),
                sourceKey, instanceKey, sourceIdKey, start.toString(), end.toString())
            return HistoryDetailIdentity(context.localHistoryCarId, scope, kind, recordId,
                sourceKey, instanceKey, sourceIdKey, start.toString(), end.toString(), key)
        }

        private fun digest(vararg values: String): String = sha256Framed(
            values.first().toByteArray(Charsets.UTF_8),
            values.drop(1).map { it.toByteArray(Charsets.UTF_8) })
    }

    fun matches(id: Int, source: String?, instanceId: String?, start: String?, end: String?,
                sourceId: String? = null, sourceRecordId: String? = null): Boolean {
        val record = resolveSourceRecordIdentity(sourceId, sourceRecordId)
        return !record.conflicted && recordId == id && sourceKey == source?.trim().orEmpty() &&
            sourceInstanceKey == instanceId?.trim().orEmpty() &&
            sourceIdKey == record.value?.trim().orEmpty() &&
            parseIsoInstant(start)?.toString() == startInstant &&
            parseIsoInstant(end)?.toString() == endInstant
    }
}

/** The identity comes from the saved list receipt, never from an unverified detail alone. */
fun ChargeSummary.verifiedDetailIdentity(context: VehicleContext): HistoryDetailIdentity? {
    if (carId != context.localHistoryCarId) return null
    val raw = toRawAnalysisChargeData()
    if (raw.chargeId != chargeId || parseIsoInstant(raw.startDate) != parseIsoInstant(startDate) ||
        parseIsoInstant(raw.endDate) != parseIsoInstant(endDate)) return null
    return runCatching { HistoryDetailIdentity.create(context, HistoryDetailIdentity.CHARGE,
        chargeId, raw.source, raw.sourceInstanceId, startDate, endDate, raw.sourceId, raw.sourceRecordId) }.getOrNull()
}

fun DriveSummary.verifiedDetailIdentity(context: VehicleContext): HistoryDetailIdentity? {
    if (carId != context.localHistoryCarId) return null
    val raw = toRawAnalysisDriveData()
    if (raw.driveId != driveId || parseIsoInstant(raw.startDate) != parseIsoInstant(startDate) ||
        parseIsoInstant(raw.endDate) != parseIsoInstant(endDate)) return null
    return runCatching { HistoryDetailIdentity.create(context, HistoryDetailIdentity.DRIVE,
        driveId, raw.source, raw.sourceInstanceId, startDate, endDate, raw.sourceId, raw.sourceRecordId) }.getOrNull()
}

/** A single old-style 2xx detail response proves receipt delivery, not provider-total coverage. */
data class HistoryDetailResponseCoverage(
    val responseComplete: Boolean = true,
    val providerPointCount: Int? = null,
    val nextCursor: String? = null
) {
    init {
        require(providerPointCount == null || providerPointCount >= 0)
        require(if (responseComplete) nextCursor == null else !nextCursor.isNullOrBlank())
    }
}

sealed interface HistoryDetailWriteResult {
    data class Stored(val receipt: HistoryDetailReceipt) : HistoryDetailWriteResult
    data class Rejected(val reason: String) : HistoryDetailWriteResult
}

data class HistoryDetailRead<T>(
    val detail: T?,
    val receipt: HistoryDetailReceipt?,
    val reason: String? = null
)

/** Transactional point storage shared by foreground detail reads and background sync. */
class HistoryDetailRepository(
    private val dao: HistoryDetailDao,
    private val codec: HistoryDetailCodec = HistoryDetailCodec(),
    private val limits: HistoryDetailStorageLimits = HistoryDetailStorageLimits(),
    private val now: () -> Long = System::currentTimeMillis
) {
    @Inject constructor(dao: HistoryDetailDao) : this(dao, HistoryDetailCodec(),
        HistoryDetailStorageLimits(), System::currentTimeMillis)

    suspend fun saveCharge(
        identity: HistoryDetailIdentity, detail: ChargeDetail,
        coverage: HistoryDetailResponseCoverage = HistoryDetailResponseCoverage(),
        ensureCurrent: suspend () -> Unit = {}
    ): HistoryDetailWriteResult {
        if (identity.kind != HistoryDetailIdentity.CHARGE || !identity.matches(detail.chargeId,
                detail.source, detail.sourceInstanceId, detail.startDate, detail.endDate, detail.sourceId, detail.sourceRecordId))
            return HistoryDetailWriteResult.Rejected("detail_identity_mismatch")
        val encoded = try { codec.encodeCharge(detail, limits) }
            catch (e: CancellationException) { throw e }
            catch (e: HistoryDetailLimitExceeded) {
                recordFailure(identity, "storage_limit")
                return HistoryDetailWriteResult.Rejected("storage_limit")
            } catch (_: IllegalArgumentException) {
                recordFailure(identity, "charge_points_missing")
                return HistoryDetailWriteResult.Rejected("charge_points_missing")
            }
        return store(identity, encoded, null, coverage, ensureCurrent)
    }

    suspend fun saveDrive(
        identity: HistoryDetailIdentity, detail: DriveDetail,
        coverage: HistoryDetailResponseCoverage = HistoryDetailResponseCoverage(),
        ensureCurrent: suspend () -> Unit = {}
    ): HistoryDetailWriteResult {
        if (identity.kind != HistoryDetailIdentity.DRIVE || !identity.matches(detail.driveId,
                detail.source, detail.sourceInstanceId, detail.startDate, detail.endDate, detail.sourceId, detail.sourceRecordId))
            return HistoryDetailWriteResult.Rejected("detail_identity_mismatch")
        val encoded = try { codec.encodeDrive(detail, limits) }
            catch (e: CancellationException) { throw e }
            catch (e: HistoryDetailLimitExceeded) {
                recordFailure(identity, "storage_limit")
                return HistoryDetailWriteResult.Rejected("storage_limit")
            } catch (_: IllegalArgumentException) {
                recordFailure(identity, "drive_points_missing")
                return HistoryDetailWriteResult.Rejected("drive_points_missing")
            }
        return store(identity, encoded, detail.lengthUnit, coverage, ensureCurrent)
    }

    private suspend fun store(
        identity: HistoryDetailIdentity, encoded: EncodedHistoryDetail, lengthUnit: String?,
        coverage: HistoryDetailResponseCoverage, ensureCurrent: suspend () -> Unit
    ): HistoryDetailWriteResult {
        if (coverage.providerPointCount != null && coverage.responseComplete &&
            coverage.providerPointCount != encoded.pointCount) {
            recordFailure(identity, "provider_point_count_mismatch")
            return HistoryDetailWriteResult.Rejected("provider_point_count_mismatch")
        }
        val totalVerified = coverage.responseComplete && coverage.providerPointCount == encoded.pointCount
        val state = when {
            !coverage.responseComplete -> HistoryDetailReceiptState.PARTIAL
            totalVerified -> HistoryDetailReceiptState.PROVIDER_COMPLETE_VERIFIED
            else -> HistoryDetailReceiptState.RESPONSE_COMPLETE_TOTAL_UNVERIFIED
        }
        val id = sha256Framed(identity.identityKey.toByteArray(Charsets.UTF_8), listOf(
            encoded.contentSha256.toByteArray(Charsets.UTF_8),
            state.toByteArray(Charsets.UTF_8),
            coverage.nextCursor.orEmpty().toByteArray(Charsets.UTF_8),
            coverage.providerPointCount?.toString().orEmpty().toByteArray(Charsets.UTF_8)))
        val receipt = HistoryDetailReceipt(
            receiptId = id, identityKey = identity.identityKey,
            localHistoryCarId = identity.localHistoryCarId, scopeFingerprint = identity.scopeFingerprint,
            kind = identity.kind, recordId = identity.recordId, sourceKey = identity.sourceKey,
            sourceInstanceKey = identity.sourceInstanceKey,
            sourceInstanceVerified = identity.sourceInstanceKey.isNotEmpty(),
            sourceIdKey = identity.sourceIdKey,
            sourceIdVerified = identity.sourceIdKey.isNotEmpty(),
            startInstant = identity.startInstant, endInstant = identity.endInstant,
            localRevision = 0L, state = state, responseComplete = coverage.responseComplete,
            providerTotalVerified = totalVerified, providerPointCount = coverage.providerPointCount,
            pointCount = encoded.pointCount, nextCursor = coverage.nextCursor,
            contentSha256 = encoded.contentSha256, encodedByteCount = encoded.encodedByteCount,
            codecVersion = 1, headerJson = encoded.headerJson, lengthUnit = lengthUnit,
            receivedAtEpochMs = now())
        var storedReceipt = receipt
        var finalInsert = HistoryDetailInsertResult.EXISTING_CORRUPT
        for (repair in 0..3) {
            val candidateId = if (repair == 0) id else sha256Framed(id.toByteArray(Charsets.UTF_8),
                listOf("repair:$repair".toByteArray(Charsets.UTF_8)))
            storedReceipt = receipt.copy(receiptId = candidateId)
            finalInsert = dao.insertBounded(storedReceipt,
                encoded.chunks.map { it.forReceipt(candidateId) }, limits.maxTotalBytes, ensureCurrent)
            if (finalInsert != HistoryDetailInsertResult.EXISTING_CORRUPT) break
        }
        if (finalInsert == HistoryDetailInsertResult.EXISTING_CORRUPT) {
            recordFailure(identity, "detail_cache_corrupt")
            return HistoryDetailWriteResult.Rejected("detail_cache_corrupt")
        }
        if (finalInsert == HistoryDetailInsertResult.BUDGET_EXCEEDED) {
            recordFailure(identity, "total_storage_limit")
            return HistoryDetailWriteResult.Rejected("total_storage_limit")
        }
        check(finalInsert == HistoryDetailInsertResult.STORED)
        return HistoryDetailWriteResult.Stored(dao.byReceiptId(storedReceipt.receiptId) ?: storedReceipt)
    }

    suspend fun readCharge(identity: HistoryDetailIdentity): HistoryDetailRead<ChargeDetail> {
        val result = read(identity, HistoryDetailIdentity.CHARGE) { receipt, chunks ->
            codec.decodeCharge(receipt, chunks)
        }
        val detail = result.detail ?: return result
        return if (identity.matches(detail.chargeId, detail.source, detail.sourceInstanceId,
                detail.startDate, detail.endDate, detail.sourceId, detail.sourceRecordId)) result
            else result.copy(detail = null, reason = "detail_header_identity_mismatch")
    }

    suspend fun readDrive(identity: HistoryDetailIdentity): HistoryDetailRead<DriveDetail> {
        val result = read(identity, HistoryDetailIdentity.DRIVE) { receipt, chunks ->
            codec.decodeDrive(receipt, chunks)
        }
        val detail = result.detail ?: return result
        return if (identity.matches(detail.driveId, detail.source, detail.sourceInstanceId,
                detail.startDate, detail.endDate, detail.sourceId, detail.sourceRecordId)) result
            else result.copy(detail = null, reason = "detail_header_identity_mismatch")
    }

    private suspend fun <T> read(
        identity: HistoryDetailIdentity, kind: String,
        decode: (HistoryDetailReceipt, List<com.matelink.data.local.entity.HistoryDetailChunk>) -> T?
    ): HistoryDetailRead<T> {
        if (identity.kind != kind) return HistoryDetailRead(null, null, "detail_kind_mismatch")
        val (receipt, chunks) = dao.readBundle(identity.identityKey)
            ?: return HistoryDetailRead(null, null, dao.latestAny(identity.identityKey)?.lastFailureCode ?: "detail_not_cached")
        if (receipt.localHistoryCarId != identity.localHistoryCarId ||
            receipt.scopeFingerprint != identity.scopeFingerprint || receipt.kind != kind ||
            receipt.recordId != identity.recordId || receipt.sourceKey != identity.sourceKey ||
            receipt.sourceInstanceKey != identity.sourceInstanceKey ||
            receipt.sourceIdKey != identity.sourceIdKey ||
            receipt.startInstant != identity.startInstant || receipt.endInstant != identity.endInstant)
            return HistoryDetailRead(null, receipt, "detail_identity_mismatch")
        val detail = runCatching { decode(receipt, chunks) }.getOrNull()
            ?: return HistoryDetailRead(null, receipt, "detail_cache_corrupt")
        val newest = dao.latestAny(identity.identityKey)
        val reason = if (newest != null && newest.localRevision > receipt.localRevision &&
            newest.state == HistoryDetailReceiptState.PARTIAL) "detail_partial_newer"
            else receipt.lastFailureCode
        return HistoryDetailRead(detail, receipt, reason)
    }

    suspend fun recordFailure(identity: HistoryDetailIdentity, code: String) {
        require(code.matches(Regex("[a-z][a-z0-9_]{0,63}")))
        val time = now()
        val failed = HistoryDetailReceipt(
            receiptId = sha256Framed(identity.identityKey.toByteArray(Charsets.UTF_8),
                listOf(code.toByteArray(Charsets.UTF_8), time.toString().toByteArray(Charsets.UTF_8))),
            identityKey = identity.identityKey, localHistoryCarId = identity.localHistoryCarId,
            scopeFingerprint = identity.scopeFingerprint, kind = identity.kind,
            recordId = identity.recordId, sourceKey = identity.sourceKey,
            sourceInstanceKey = identity.sourceInstanceKey,
            sourceInstanceVerified = identity.sourceInstanceKey.isNotEmpty(),
            sourceIdKey = identity.sourceIdKey,
            sourceIdVerified = identity.sourceIdKey.isNotEmpty(),
            startInstant = identity.startInstant, endInstant = identity.endInstant,
            localRevision = 0L, state = HistoryDetailReceiptState.FAILED,
            responseComplete = false, providerTotalVerified = false,
            providerPointCount = null, pointCount = 0, nextCursor = null,
            contentSha256 = null, encodedByteCount = 0, codecVersion = 1,
            headerJson = null, lengthUnit = null, receivedAtEpochMs = time,
            lastFailureCode = code, lastAttemptAtEpochMs = time)
        dao.noteFailure(failed)
    }
}

package com.matelink.data.repository

import com.matelink.data.api.models.ParkedDetailData
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.VehicleContext
import com.matelink.data.local.dao.HistoryDetailDao
import com.matelink.data.local.dao.HistoryDetailInsertResult
import com.matelink.data.local.entity.HistoryDetailReceipt
import com.matelink.data.local.entity.HistoryDetailReceiptState
import com.matelink.util.parseIsoInstant
import com.squareup.moshi.Moshi
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/** Created only after current Room receipts prove same-source, uninterrupted adjacency. */
internal data class ParkedReceiptBinding(
    val context: VehicleContext, val olderDriveId: Int, val newerDriveId: Int,
    val source: String, val sourceInstanceId: String, val startDate: String, val endDate: String,
    val olderReceipt: String, val newerReceipt: String
) {
    val start = requireNotNull(parseIsoInstant(startDate))
    val end = requireNotNull(parseIsoInstant(endDate))
    init {
        require(end.isAfter(start) && context.stableIdentity.isNotBlank())
        require(source.isNotBlank() && sourceInstanceId.isNotBlank() && olderDriveId != newerDriveId)
    }
    val scopeFingerprint = parkedHash(context.stableIdentity, context.connectionSource.name, context.serverIdentity)
    val identityKey = parkedHash("parked-v1", context.localHistoryCarId.toString(), scopeFingerprint,
        olderDriveId.toString(), newerDriveId.toString(), source, sourceInstanceId, start.toString(), end.toString(),
        parkedHash(olderReceipt, newerReceipt))
    val allowedSources: Set<String> get() = when {
        context.connectionSource == HistoryConnectionSource.SELF_HOSTED && source == "teslamate_archive" ->
            setOf("teslamate_archive", "database_latest")
        context.connectionSource == HistoryConnectionSource.CLOUD && source == "telemetry_mqtt" ->
            setOf("telemetry_mqtt", "fleet_telemetry")
        else -> setOf(source)
    }
    fun accepts(value: ParkedDetailData): Boolean = value.olderDriveId == olderDriveId && value.newerDriveId == newerDriveId &&
        parseIsoInstant(value.startDate) == start && parseIsoInstant(value.endDate) == end && value.source in allowedSources &&
        (value.carId == null || value.carId == context.remoteApiCarId) &&
        (value.sourceInstanceId == null || value.sourceInstanceId == sourceInstanceId)
}

/** Header-only versioned receipts reuse v23 tables without altering or evicting earlier evidence. */
class ParkedHistoryReceiptStore internal constructor(
    private val dao: HistoryDetailDao, private val limits: HistoryDetailStorageLimits
) {
    @Inject constructor(dao: HistoryDetailDao) : this(dao, HistoryDetailStorageLimits(maxReceiptBytes = 256L * 1024))
    private val adapter = Moshi.Builder().build().adapter(ParkedDetailData::class.java).serializeNulls()

    internal suspend fun save(binding: ParkedReceiptBinding, value: ParkedDetailData,
        ensureCurrent: suspend () -> Unit = {}): Boolean {
        ensureCurrent()
        if (!binding.accepts(value) || (value.linkedCharges?.size ?: if (value.linkedCharge != null) 1 else 0) > 100) return false
        val header = try { adapter.toJson(value) } catch (_: IllegalArgumentException) { return false }
        val bytes = header.toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > minOf(limits.maxReceiptBytes, 256L * 1024)) return false
        val digest = parkedHash(header)
        val id = "${binding.identityKey}:$digest"
        val previous = dao.byReceiptId(id)
        ensureCurrent()
        if (previous != null && (previous.headerJson != header || previous.contentSha256 != digest)) return false
        val receipt = HistoryDetailReceipt(id, binding.identityKey, binding.context.localHistoryCarId,
            binding.scopeFingerprint, "parked", binding.olderDriveId, value.source,
            value.sourceInstanceId.orEmpty(), value.sourceInstanceId != null, "", false,
            binding.start.toString(), binding.end.toString(), 0,
            HistoryDetailReceiptState.RESPONSE_COMPLETE_TOTAL_UNVERIFIED, true, false, null, 0, null,
            digest, bytes.size.toLong(), 1, header, null, System.currentTimeMillis())
        val stored = dao.insertBounded(receipt, emptyList(), limits.maxTotalBytes, ensureCurrent)
        ensureCurrent()
        return stored == HistoryDetailInsertResult.STORED
    }

    internal suspend fun read(binding: ParkedReceiptBinding, ensureCurrent: suspend () -> Unit = {}): ParkedDetailData? {
        ensureCurrent()
        val candidates = dao.latestCompleteCandidates(binding.identityKey, 5)
        ensureCurrent()
        for (receipt in candidates) {
            if (receipt.kind != "parked" || receipt.identityKey != binding.identityKey ||
                receipt.localHistoryCarId != binding.context.localHistoryCarId || receipt.scopeFingerprint != binding.scopeFingerprint ||
                receipt.recordId != binding.olderDriveId || receipt.startInstant != binding.start.toString() || receipt.endInstant != binding.end.toString() ||
                receipt.codecVersion != 1 || receipt.pointCount != 0 || !receipt.responseComplete) continue
            val header = receipt.headerJson ?: continue
            val size = header.toByteArray(Charsets.UTF_8).size.toLong()
            if (size != receipt.encodedByteCount || size > minOf(limits.maxReceiptBytes, 256L * 1024) ||
                parkedHash(header) != receipt.contentSha256) continue
            if (dao.chunks(receipt.receiptId).isNotEmpty()) continue
            ensureCurrent()
            val value = try { adapter.fromJson(header) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                ?: continue
            if (binding.accepts(value) && receipt.sourceKey == value.source &&
                receipt.sourceInstanceKey == value.sourceInstanceId.orEmpty()) return value
        }
        return null
    }
}

private fun parkedHash(vararg fields: String): String {
    val framed = fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(framed.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

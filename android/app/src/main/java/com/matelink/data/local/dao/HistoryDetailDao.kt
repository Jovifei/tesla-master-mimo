package com.matelink.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.matelink.data.local.entity.HistoryDetailChunk
import com.matelink.data.local.entity.HistoryDetailReceipt
import com.matelink.data.local.entity.HistoryDetailReceiptState

enum class HistoryDetailInsertResult { STORED, BUDGET_EXCEEDED, EXISTING_CORRUPT }

@Dao
interface HistoryDetailDao {
    @Query("SELECT * FROM history_detail_receipts WHERE receiptId = :receiptId LIMIT 1")
    suspend fun byReceiptId(receiptId: String): HistoryDetailReceipt?

    @Query("SELECT * FROM history_detail_receipts WHERE identityKey = :identityKey ORDER BY localRevision DESC LIMIT 1")
    suspend fun latestAny(identityKey: String): HistoryDetailReceipt?

    @Query("SELECT * FROM history_detail_receipts WHERE identityKey = :identityKey AND state != 'failed' ORDER BY CASE WHEN state = 'partial' THEN 0 ELSE 1 END DESC, localRevision DESC LIMIT 1")
    suspend fun latestReadable(identityKey: String): HistoryDetailReceipt?

    /** Bounded offline recovery of older complete receipts if a newer chunk is damaged. */
    @Query("SELECT * FROM history_detail_receipts WHERE identityKey = :identityKey AND state IN ('response_complete_total_unverified', 'provider_complete_verified') ORDER BY localRevision DESC LIMIT :limit")
    suspend fun latestCompleteCandidates(identityKey: String, limit: Int = 5): List<HistoryDetailReceipt>

    @Query("SELECT * FROM history_detail_chunks WHERE receiptId = :receiptId ORDER BY chunkIndex ASC")
    suspend fun chunks(receiptId: String): List<HistoryDetailChunk>

    @Query("SELECT COALESCE(SUM(encodedByteCount), 0) FROM history_detail_receipts WHERE state != 'failed'")
    suspend fun storedByteCount(): Long

    @Insert
    suspend fun insertReceipt(receipt: HistoryDetailReceipt)

    @Insert
    suspend fun insertChunks(chunks: List<HistoryDetailChunk>)

    @Query("UPDATE history_detail_receipts SET lastFailureCode = :reason, lastAttemptAtEpochMs = :attemptAt WHERE receiptId = :receiptId")
    suspend fun setFailure(receiptId: String, reason: String, attemptAt: Long)

    @Query("UPDATE history_detail_receipts SET lastFailureCode = NULL, lastAttemptAtEpochMs = :attemptAt WHERE receiptId = :receiptId")
    suspend fun clearFailure(receiptId: String, attemptAt: Long)

    /** No eviction: reaching the explicit budget rejects the new receipt and retains old data. */
    @Transaction
    suspend fun insertBounded(
        receipt: HistoryDetailReceipt,
        pointChunks: List<HistoryDetailChunk>,
        maxTotalBytes: Long,
        ensureCurrent: suspend () -> Unit = {}
    ): HistoryDetailInsertResult {
        ensureCurrent()
        require(receipt.state != HistoryDetailReceiptState.FAILED)
        require(pointChunks.all { it.receiptId == receipt.receiptId })
        require(receipt.encodedByteCount >= 0 && maxTotalBytes >= 0)
        byReceiptId(receipt.receiptId)?.let {
            require(it.identityKey == receipt.identityKey && it.contentSha256 == receipt.contentSha256)
            val existingChunks = chunks(it.receiptId)
            if (existingChunks.size != pointChunks.size || existingChunks.zip(pointChunks).any { (old, new) ->
                    old.chunkIndex != new.chunkIndex || old.firstPointIndex != new.firstPointIndex ||
                        old.pointCount != new.pointCount || old.sha256 != new.sha256 ||
                        !old.payload.contentEquals(new.payload)
                }) return HistoryDetailInsertResult.EXISTING_CORRUPT
            clearFailure(it.receiptId, receipt.receivedAtEpochMs)
            return HistoryDetailInsertResult.STORED
        }
        val used = storedByteCount()
        if (used > maxTotalBytes || receipt.encodedByteCount > maxTotalBytes - used)
            return HistoryDetailInsertResult.BUDGET_EXCEEDED
        val revision = (latestAny(receipt.identityKey)?.localRevision ?: 0L) + 1L
        insertReceipt(receipt.copy(localRevision = revision))
        ensureCurrent()
        insertChunks(pointChunks)
        ensureCurrent()
        return HistoryDetailInsertResult.STORED
    }

    @Transaction
    suspend fun noteFailure(failed: HistoryDetailReceipt) {
        require(failed.state == HistoryDetailReceiptState.FAILED && failed.pointCount == 0)
        val prior = latestReadable(failed.identityKey) ?: latestAny(failed.identityKey)
        if (prior != null) {
            setFailure(prior.receiptId, requireNotNull(failed.lastFailureCode), failed.receivedAtEpochMs)
            return
        }
        insertReceipt(failed.copy(localRevision = 1L))
    }

    @Transaction
    suspend fun readBundle(identityKey: String): Pair<HistoryDetailReceipt, List<HistoryDetailChunk>>? {
        val receipt = latestReadable(identityKey) ?: return null
        return receipt to chunks(receipt.receiptId)
    }
}

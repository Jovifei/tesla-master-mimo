package com.matelink.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One immutable set of received detail points. New revisions never erase older receipts. */
@Entity(
    tableName = "history_detail_receipts",
    indices = [
        Index(value = ["identityKey", "localRevision"], unique = true),
        Index(value = ["localHistoryCarId", "kind", "recordId"])
    ]
)
data class HistoryDetailReceipt(
    @PrimaryKey val receiptId: String,
    val identityKey: String,
    val localHistoryCarId: Int,
    /** Hash of the verified account/origin/stable-vehicle namespace, not a VIN. */
    val scopeFingerprint: String,
    val kind: String,
    val recordId: Int,
    val sourceKey: String,
    val sourceInstanceKey: String,
    val sourceInstanceVerified: Boolean,
    val sourceIdKey: String,
    val sourceIdVerified: Boolean,
    val startInstant: String,
    val endInstant: String,
    val localRevision: Long,
    /** See [HistoryDetailReceiptState]. A response is not proof of provider-total coverage. */
    val state: String,
    val responseComplete: Boolean,
    val providerTotalVerified: Boolean,
    val providerPointCount: Int?,
    val pointCount: Int,
    val nextCursor: String?,
    val contentSha256: String?,
    val encodedByteCount: Long,
    val codecVersion: Int,
    val headerJson: String?,
    /** Drive distance unit from the detail response envelope; null for charge. */
    val lengthUnit: String?,
    val receivedAtEpochMs: Long,
    /** Latest bounded error code; the saved payload and its state remain intact. */
    val lastFailureCode: String? = null,
    val lastAttemptAtEpochMs: Long? = null
)

object HistoryDetailReceiptState {
    const val RESPONSE_COMPLETE_TOTAL_UNVERIFIED = "response_complete_total_unverified"
    const val PROVIDER_COMPLETE_VERIFIED = "provider_complete_verified"
    const val PARTIAL = "partial"
    const val FAILED = "failed"
}

/** Ordered, bounded original typed point payload; nulls and same-time points are retained. */
@Entity(
    tableName = "history_detail_chunks",
    primaryKeys = ["receiptId", "chunkIndex"],
    foreignKeys = [ForeignKey(
        entity = HistoryDetailReceipt::class,
        parentColumns = ["receiptId"],
        childColumns = ["receiptId"],
        onDelete = ForeignKey.NO_ACTION
    )],
    indices = [Index(value = ["receiptId"])]
)
data class HistoryDetailChunk(
    val receiptId: String,
    val chunkIndex: Int,
    val firstPointIndex: Int,
    val pointCount: Int,
    val sha256: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val payload: ByteArray
)

package com.matelink.data.repository

import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DrivePosition
import com.matelink.data.local.entity.HistoryDetailChunk
import com.matelink.data.local.entity.HistoryDetailReceipt
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Explicit limits stop a large response from silently becoming a purported full archive. */
data class HistoryDetailStorageLimits(
    val maxPointsPerChunk: Int = 256,
    val maxChunkBytes: Int = 65_536,
    val maxPointsPerReceipt: Int = 100_000,
    val maxReceiptBytes: Long = 64L * 1024 * 1024,
    val maxTotalBytes: Long = 512L * 1024 * 1024
) {
    init {
        require(maxPointsPerChunk > 0 && maxChunkBytes >= 4 && maxPointsPerReceipt >= 0)
        require(maxReceiptBytes > 0 && maxTotalBytes > 0)
    }
}

class HistoryDetailLimitExceeded(message: String) : IllegalArgumentException(message)

data class EncodedHistoryDetailChunk(
    val chunkIndex: Int,
    val firstPointIndex: Int,
    val pointCount: Int,
    val sha256: String,
    val payload: ByteArray
) {
    fun forReceipt(receiptId: String) = HistoryDetailChunk(
        receiptId, chunkIndex, firstPointIndex, pointCount, sha256, payload)
}

data class EncodedHistoryDetail(
    val headerJson: String,
    val chunks: List<EncodedHistoryDetailChunk>,
    val pointCount: Int,
    val encodedByteCount: Long,
    val contentSha256: String
)

/** Stores the actual DTO point fields, preserving order, nulls, zero, negatives and duplicate times. */
class HistoryDetailCodec(private val moshi: Moshi = Moshi.Builder().build()) {
    private val chargeAdapter = moshi.adapter(ChargeDetail::class.java).serializeNulls()
    private val driveAdapter = moshi.adapter(DriveDetail::class.java).serializeNulls()
    private val chargePointAdapter = moshi.adapter(ChargePoint::class.java).serializeNulls()
    private val drivePointAdapter = moshi.adapter(DrivePosition::class.java).serializeNulls()
    private val chargeListAdapter: JsonAdapter<List<ChargePoint>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, ChargePoint::class.java))
    private val driveListAdapter: JsonAdapter<List<DrivePosition>> = moshi.adapter(
        Types.newParameterizedType(List::class.java, DrivePosition::class.java))

    fun encodeCharge(detail: ChargeDetail, limits: HistoryDetailStorageLimits): EncodedHistoryDetail {
        val points = detail.chargePoints ?: throw IllegalArgumentException("charge_points_missing")
        return encode(chargeAdapter.toJson(detail.copy(chargePoints = null)), points, chargePointAdapter, limits)
    }

    fun encodeDrive(detail: DriveDetail, limits: HistoryDetailStorageLimits): EncodedHistoryDetail {
        val points = detail.positions ?: throw IllegalArgumentException("drive_points_missing")
        return encode(driveAdapter.toJson(detail.copy(positions = null)), points, drivePointAdapter, limits)
    }

    fun decodeCharge(receipt: HistoryDetailReceipt, chunks: List<HistoryDetailChunk>): ChargeDetail? =
        decode(receipt, chunks, chargeListAdapter)?.let { points ->
            chargeAdapter.fromJson(requireNotNull(receipt.headerJson))?.copy(chargePoints = points)
        }

    fun decodeDrive(receipt: HistoryDetailReceipt, chunks: List<HistoryDetailChunk>): DriveDetail? =
        decode(receipt, chunks, driveListAdapter)?.let { points ->
            driveAdapter.fromJson(requireNotNull(receipt.headerJson))
                ?.copy(positions = points, lengthUnit = receipt.lengthUnit)
        }

    private fun <T> encode(
        headerJson: String, points: List<T>, pointAdapter: JsonAdapter<T>,
        limits: HistoryDetailStorageLimits
    ): EncodedHistoryDetail {
        if (points.size > limits.maxPointsPerReceipt) throw HistoryDetailLimitExceeded("point_limit")
        val headerBytes = headerJson.toByteArray(Charsets.UTF_8)
        if (headerBytes.size.toLong() > limits.maxReceiptBytes) throw HistoryDetailLimitExceeded("receipt_byte_limit")
        val chunks = mutableListOf<EncodedHistoryDetailChunk>()
        var current = ByteArrayOutputStream().apply { write('['.code) }
        var inChunk = 0
        var firstIndex = 0
        var totalBytes = headerBytes.size.toLong()
        fun flush() {
            if (inChunk == 0) return
            current.write(']'.code)
            val payload = current.toByteArray()
            totalBytes += payload.size
            if (totalBytes > limits.maxReceiptBytes) throw HistoryDetailLimitExceeded("receipt_byte_limit")
            chunks += EncodedHistoryDetailChunk(chunks.size, firstIndex, inChunk, sha256(payload), payload)
            firstIndex += inChunk
            inChunk = 0
            current = ByteArrayOutputStream().apply { write('['.code) }
        }
        points.forEach { point ->
            val bytes = pointAdapter.toJson(point).toByteArray(Charsets.UTF_8)
            if (bytes.size + 2 > limits.maxChunkBytes) throw HistoryDetailLimitExceeded("single_point_byte_limit")
            val separator = if (inChunk == 0) 0 else 1
            if (inChunk >= limits.maxPointsPerChunk || current.size() + separator + bytes.size + 1 > limits.maxChunkBytes) flush()
            if (inChunk > 0) current.write(','.code)
            current.write(bytes)
            inChunk++
        }
        flush()
        return EncodedHistoryDetail(headerJson, chunks, points.size, totalBytes,
            sha256Framed(headerBytes, chunks.map { it.payload }))
    }

    private fun <T> decode(
        receipt: HistoryDetailReceipt, chunks: List<HistoryDetailChunk>, adapter: JsonAdapter<List<T>>
    ): List<T>? {
        if (receipt.codecVersion != 1 || receipt.headerJson == null || receipt.contentSha256 == null ||
            receipt.pointCount < 0) return null
        val ordered = chunks.sortedBy { it.chunkIndex }
        val points = mutableListOf<T>()
        var byteCount = receipt.headerJson.toByteArray(Charsets.UTF_8).size.toLong()
        for ((index, chunk) in ordered.withIndex()) {
            if (chunk.chunkIndex != index || chunk.firstPointIndex != points.size || chunk.pointCount <= 0 ||
                sha256(chunk.payload) != chunk.sha256) return null
            val decoded = runCatching { adapter.fromJson(chunk.payload.toString(Charsets.UTF_8)) }.getOrNull()
                ?: return null
            if (decoded.size != chunk.pointCount) return null
            byteCount += chunk.payload.size
            points += decoded
        }
        if (points.size != receipt.pointCount || byteCount != receipt.encodedByteCount ||
            sha256Framed(receipt.headerJson.toByteArray(Charsets.UTF_8), ordered.map { it.payload }) != receipt.contentSha256)
            return null
        return points
    }
}

internal fun sha256Framed(first: ByteArray, rest: List<ByteArray>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    (listOf(first) + rest).forEach { bytes ->
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

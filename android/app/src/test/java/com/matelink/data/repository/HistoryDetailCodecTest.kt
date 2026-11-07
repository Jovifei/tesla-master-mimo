package com.matelink.data.repository

import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.ChargerDetails
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DrivePosition
import com.matelink.data.local.entity.HistoryDetailReceipt
import com.matelink.data.local.entity.HistoryDetailReceiptState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HistoryDetailCodecTest {
    private val codec = HistoryDetailCodec()
    private val at = "2026-10-20T10:00:00Z"
    private val end = "2026-10-20T10:39:00Z"

    private fun receipt(kind: String, encoded: EncodedHistoryDetail, unit: String? = null) =
        HistoryDetailReceipt(
            receiptId = "synthetic", identityKey = "synthetic", localHistoryCarId = -7,
            scopeFingerprint = "synthetic", kind = kind, recordId = 64,
            sourceKey = "teslamate_archive", sourceInstanceKey = "", sourceInstanceVerified = false,
            sourceIdKey = "", sourceIdVerified = false,
            startInstant = at, endInstant = end, localRevision = 1,
            state = HistoryDetailReceiptState.RESPONSE_COMPLETE_TOTAL_UNVERIFIED,
            responseComplete = true, providerTotalVerified = false, providerPointCount = null,
            pointCount = encoded.pointCount, nextCursor = null,
            contentSha256 = encoded.contentSha256, encodedByteCount = encoded.encodedByteCount,
            codecVersion = 1, headerJson = encoded.headerJson, lengthUnit = unit,
            receivedAtEpochMs = 1L)

    @Test fun chargeRoundTripKeepsNullZeroNegativeSameTimeAndOriginalOrder() {
        val points = listOf(
            ChargePoint(date = at, batteryLevel = 0, chargeEnergyAdded = 0.0,
                chargerDetails = ChargerDetails(chargerPower = 0.0)),
            ChargePoint(date = at, batteryLevel = null, chargeEnergyAdded = null,
                chargerDetails = ChargerDetails(chargerPower = -3.5)),
            ChargePoint(date = end, batteryLevel = 88, chargeEnergyAdded = 24.02)
        )
        val detail = ChargeDetail(64, startDate = at, endDate = end,
            source = "teslamate_archive", chargePoints = points)
        val encoded = codec.encodeCharge(detail, HistoryDetailStorageLimits(maxPointsPerChunk = 2))
        assertEquals(listOf(2, 1), encoded.chunks.map { it.pointCount })
        val manifest = receipt(HistoryDetailIdentity.CHARGE, encoded)
        assertEquals(detail, codec.decodeCharge(manifest,
            encoded.chunks.map { it.forReceipt(manifest.receiptId) }))
    }

    @Test fun driveRoundTripRetainsUnitAndRejectsCorruptOrTruncatedChunks() {
        val points = listOf(
            DrivePosition(date = at, latitude = 0.0, longitude = 0.0, power = -55.0),
            DrivePosition(date = at, latitude = null, longitude = null, speed = 0.0),
            DrivePosition(date = end, latitude = 1.0, longitude = 2.0, power = 10.0)
        )
        val detail = DriveDetail(64, startDate = at, endDate = end, source = "teslamate_archive",
            positions = points, lengthUnit = "mi")
        val encoded = codec.encodeDrive(detail, HistoryDetailStorageLimits(maxPointsPerChunk = 2))
        val manifest = receipt(HistoryDetailIdentity.DRIVE, encoded, "mi")
        val chunks = encoded.chunks.map { it.forReceipt(manifest.receiptId) }
        assertEquals(detail, codec.decodeDrive(manifest, chunks))
        assertNull(codec.decodeDrive(manifest, chunks.dropLast(1)))
        assertNull(codec.decodeDrive(manifest, chunks.mapIndexed { index, chunk ->
            if (index == 0) chunk.copy(payload = chunk.payload.clone().apply { this[1] = 'x'.code.toByte() })
            else chunk
        }))
    }

    @Test fun boundedEncoderRejectsOversizeInsteadOfSavingAnUnmarkedPrefix() {
        val detail = ChargeDetail(64, startDate = at, endDate = end,
            chargePoints = listOf(ChargePoint(date = at, chargeEnergyAdded = 0.0)))
        assertThrows(HistoryDetailLimitExceeded::class.java) {
            codec.encodeCharge(detail, HistoryDetailStorageLimits(maxChunkBytes = 8))
        }
        assertThrows(HistoryDetailLimitExceeded::class.java) {
            codec.encodeCharge(detail, HistoryDetailStorageLimits(maxPointsPerReceipt = 0))
        }
    }
}

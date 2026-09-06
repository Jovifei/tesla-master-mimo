package com.matelink.data.sync

import com.matelink.data.api.models.HistoryImportSession
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class BoundedHistoryUpload(
    val drives: List<HistoryImportSession>,
    val charges: List<HistoryImportSession>
)

object HistoryUploadFilter {
    /**
     * Extracts the UTC LocalDate for a timestamp string. Supports ISO-8601 instants,
     * offset datetimes, and local datetimes (assumed UTC). Returns null on invalid
     * or blank input.
     */
    fun extractUtcDate(timestamp: String?): LocalDate? {
        if (timestamp.isNullOrBlank()) return null
        return try {
            Instant.parse(timestamp).atZone(ZoneOffset.UTC).toLocalDate()
        } catch (e: Exception) {
            try {
                OffsetDateTime.parse(timestamp).atZoneSameInstant(ZoneOffset.UTC).toLocalDate()
            } catch (e2: Exception) {
                try {
                    LocalDateTime.parse(timestamp).atOffset(ZoneOffset.UTC).toLocalDate()
                } catch (e3: Exception) {
                    null
                }
            }
        }
    }

    /** Keeps the complete local archive while dropping only records with unusable timestamps. */
    fun keepValidatedArchive(
        drives: List<HistoryImportSession>,
        charges: List<HistoryImportSession>
    ): BoundedHistoryUpload {
        return BoundedHistoryUpload(
            drives = drives.filter { extractUtcDate(it.startedAt) != null },
            charges = charges.filter { extractUtcDate(it.startedAt) != null }
        )
    }

    /** Splits a complete archive into requests accepted by the server's 200-per-kind limit. */
    fun batchesForUpload(
        drives: List<HistoryImportSession>,
        charges: List<HistoryImportSession>
    ): List<BoundedHistoryUpload> {
        val driveBatches = drives.chunked(200)
        val chargeBatches = charges.chunked(200)
        val count = maxOf(driveBatches.size, chargeBatches.size)
        return (0 until count).map { index ->
            BoundedHistoryUpload(
                drives = driveBatches.getOrElse(index) { emptyList() },
                charges = chargeBatches.getOrElse(index) { emptyList() }
            )
        }
    }

    @Deprecated("Use keepValidatedArchive to preserve the complete local archive")
    fun boundToLatestTwoDataDays(
        drives: List<HistoryImportSession>,
        charges: List<HistoryImportSession>
    ): BoundedHistoryUpload = keepValidatedArchive(drives, charges)
}

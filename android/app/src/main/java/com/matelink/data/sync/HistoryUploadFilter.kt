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

    /**
     * Restricts the uploaded sessions to the most recent two UTC calendar days that
     * actually contain data across both drives and charges.
     *
     * If there are <= 2 days with data, all valid sessions are kept.
     * If there are > 2 days with data, only sessions falling on the latest two
     * calendar days with data are retained.
     */
    fun boundToLatestTwoDataDays(
        drives: List<HistoryImportSession>,
        charges: List<HistoryImportSession>
    ): BoundedHistoryUpload {
        val driveDates = drives.mapNotNull { extractUtcDate(it.startedAt) }
        val chargeDates = charges.mapNotNull { extractUtcDate(it.startedAt) }
        val distinctDates = (driveDates + chargeDates).distinct().sortedDescending()
        if (distinctDates.isEmpty()) {
            return BoundedHistoryUpload(emptyList(), emptyList())
        }
        val retainedDates = distinctDates.take(2).toSet()

        val filteredDrives = drives.filter {
            val date = extractUtcDate(it.startedAt)
            date != null && date in retainedDates
        }
        val filteredCharges = charges.filter {
            val date = extractUtcDate(it.startedAt)
            date != null && date in retainedDates
        }
        return BoundedHistoryUpload(filteredDrives, filteredCharges)
    }
}
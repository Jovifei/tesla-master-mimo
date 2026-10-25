package com.matelink.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds RFC3339 boundaries for a half-open *local* calendar day.
 *
 * The TeslaMate API expects RFC3339 (`2006-01-02T15:04:05Z` for UTC, or
 * `2006-01-02T15:04:05+nn:00` with an explicit offset for local time). Sending a hardcoded
 * `Z` makes "today" mean the UTC day, which is shifted from the user's local day by their UTC
 * offset — so date filters pulled in the adjacent day's drives/charges for users far from UTC.
 *
 * These helpers attach the device's local offset instead, computed *per boundary* so that days
 * containing a DST transition use the correct offset at each end.
 */
object LocalDayBoundaries {
    private val boundaryFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX")

    /** e.g. `2026-05-30T00:00:00+02:00` — midnight at the start of [date] in [zoneId]. */
    fun startOfDay(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): String =
        date.atStartOfDay(zoneId).format(boundaryFormat)

    /** Exclusive upper bound: next local midnight, including the selected day's final fraction. */
    fun endOfDay(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): String =
        date.plusDays(1).atStartOfDay(zoneId).format(boundaryFormat)
}

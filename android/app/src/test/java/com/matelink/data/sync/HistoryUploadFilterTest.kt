package com.matelink.data.sync

import com.matelink.data.api.models.HistoryImportSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HistoryUploadFilterTest {

    private fun makeSession(id: String, startedAt: String): HistoryImportSession {
        return HistoryImportSession(
            sessionId = id,
            startedAt = startedAt,
            endedAt = startedAt,
            odometerStart = null,
            odometerEnd = null,
            energyAdded = null,
            route = emptyList()
        )
    }

    @Test
    fun sameDayMultipleRecords_allRetained() {
        val drives = listOf(
            makeSession("d1", "2026-09-02T08:00:00Z"),
            makeSession("d2", "2026-09-02T14:00:00Z"),
            makeSession("d3", "2026-09-02T20:00:00Z")
        )
        val charges = listOf(
            makeSession("c1", "2026-09-02T22:00:00Z")
        )

        val bounded = HistoryUploadFilter.boundToLatestTwoDataDays(drives, charges)

        assertEquals(3, bounded.drives.size)
        assertEquals(1, bounded.charges.size)
        assertEquals(listOf("d1", "d2", "d3"), bounded.drives.map { it.sessionId })
        assertEquals(listOf("c1"), bounded.charges.map { it.sessionId })
    }

    @Test
    fun spansThreeDays_onlyLatestTwoDaysRetained() {
        val drives = listOf(
            makeSession("d1", "2026-09-01T10:00:00Z"), // day 1 (oldest)
            makeSession("d2", "2026-09-02T10:00:00Z"), // day 2
            makeSession("d3", "2026-09-03T10:00:00Z")  // day 3 (newest)
        )
        val charges = listOf(
            makeSession("c1", "2026-09-01T12:00:00Z"),
            makeSession("c2", "2026-09-03T12:00:00Z")
        )

        val bounded = HistoryUploadFilter.boundToLatestTwoDataDays(drives, charges)

        // Only 2026-09-03 and 2026-09-02 are retained; 2026-09-01 is evicted.
        assertEquals(listOf("d2", "d3"), bounded.drives.map { it.sessionId })
        assertEquals(listOf("c2"), bounded.charges.map { it.sessionId })
    }

    @Test
    fun gapsBetweenDates_retainsLatestTwoActualDataDaysNotNowMinus48h() {
        // Data on Aug 1, Aug 15, Sep 1. Gap of weeks between dates.
        val drives = listOf(
            makeSession("d-aug01", "2026-08-01T10:00:00Z"),
            makeSession("d-aug15", "2026-08-15T10:00:00Z"),
            makeSession("d-sep01", "2026-09-01T10:00:00Z")
        )
        val charges = emptyList<HistoryImportSession>()

        val bounded = HistoryUploadFilter.boundToLatestTwoDataDays(drives, charges)

        // Retains Aug 15 and Sep 1 (the two most recent calendar days with data), not strictly now - 48h.
        assertEquals(listOf("d-aug15", "d-sep01"), bounded.drives.map { it.sessionId })
    }

    @Test
    fun mixedDriveAndCharge_unifiesCalendarDaysAcrossBothTypes() {
        // Drive on Day 3, Charge on Day 2, Drive on Day 1.
        val drives = listOf(
            makeSession("d1", "2026-08-10T10:00:00Z"),
            makeSession("d3", "2026-08-30T10:00:00Z")
        )
        val charges = listOf(
            makeSession("c2", "2026-08-20T10:00:00Z")
        )

        val bounded = HistoryUploadFilter.boundToLatestTwoDataDays(drives, charges)

        // Distinct data days: 08-30, 08-20, 08-10. Top 2: 08-30 and 08-20.
        assertEquals(listOf("d3"), bounded.drives.map { it.sessionId })
        assertEquals(listOf("c2"), bounded.charges.map { it.sessionId })
    }

    @Test
    fun timezoneOffsetInput_correctlyParsedToUtcDate() {
        // 2026-09-02T01:00:00+08:00 is 2026-09-01T17:00:00Z in UTC.
        // 2026-09-02T10:00:00+08:00 is 2026-09-02T02:00:00Z in UTC.
        val date1 = HistoryUploadFilter.extractUtcDate("2026-09-02T01:00:00+08:00")
        val date2 = HistoryUploadFilter.extractUtcDate("2026-09-02T10:00:00+08:00")

        assertEquals(LocalDate.of(2026, 9, 1), date1)
        assertEquals(LocalDate.of(2026, 9, 2), date2)

        val drives = listOf(
            makeSession("d-offset-day1", "2026-09-02T01:00:00+08:00"),
            makeSession("d-offset-day2", "2026-09-02T10:00:00+08:00")
        )
        val bounded = HistoryUploadFilter.boundToLatestTwoDataDays(drives, emptyList())
        assertEquals(2, bounded.drives.size)
    }

    @Test
    fun invalidTimestamp_handledGracefullyWithoutCrash() {
        val drives = listOf(
            makeSession("d-valid", "2026-09-03T10:00:00Z"),
            makeSession("d-invalid1", "not-a-date"),
            makeSession("d-invalid2", ""),
            makeSession("d-invalid3", "2026-99-99T99:99:99")
        )
        val bounded = HistoryUploadFilter.boundToLatestTwoDataDays(drives, emptyList())

        assertEquals(1, bounded.drives.size)
        assertEquals("d-valid", bounded.drives[0].sessionId)
    }

    @Test
    fun emptyInput_returnsEmpty() {
        val bounded = HistoryUploadFilter.boundToLatestTwoDataDays(emptyList(), emptyList())
        assertTrue(bounded.drives.isEmpty())
        assertTrue(bounded.charges.isEmpty())
    }
}
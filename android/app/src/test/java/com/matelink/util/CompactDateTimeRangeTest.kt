package com.matelink.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactDateTimeRangeTest {
    @Test
    fun sameYearOmitsYearFromEnd() {
        assertEquals(
            "2026/07/09 18:52 \u2192 07/09 19:04",
            formatCompactDateTimeRange("2026-07-09T18:52:00+08:00", "2026-07-09T19:04:00+08:00")
        )
    }

    @Test
    fun crossYearKeepsBothYears() {
        assertEquals(
            "2026/12/31 23:55 \u2192 2027/01/01 00:05",
            formatCompactDateTimeRange("2026-12-31T23:55:00+08:00", "2027-01-01T00:05:00+08:00")
        )
    }

    @Test
    fun missingStartReturnsUnavailableMarker() {
        assertEquals("--", formatCompactDateTimeRange(null, "2026-07-09T19:04:00+08:00"))
    }

    @Test
    fun missingEndReturnsFormattedStartOnly() {
        assertEquals(
            "2026/07/09 18:52",
            formatCompactDateTimeRange("2026-07-09T18:52:00+08:00", null)
        )
    }

    @Test
    fun bothDatesMissingReturnsUnavailableMarker() {
        assertEquals("--", formatCompactDateTimeRange(null, null))
    }

    @Test
    fun blankInputsDoNotProduceAnEpochDate() {
        assertEquals("--", formatCompactDateTimeRange("", ""))
        assertEquals("--", formatCompactDateTimeRange("   ", "   "))
        assertEquals("--", formatCompactDateTimeRange("", "2026-07-09T19:04:00+08:00"))
        assertEquals("--", formatCompactDateTimeRange("   ", "2026-07-09T19:04:00+08:00"))
        assertEquals(
            "2026/07/09 18:52",
            formatCompactDateTimeRange("2026-07-09T18:52:00+08:00", "")
        )
        assertEquals(
            "2026/07/09 18:52",
            formatCompactDateTimeRange("2026-07-09T18:52:00+08:00", "   ")
        )
    }

    @Test
    fun invalidInputsReturnHonestFallbacks() {
        assertEquals("--", formatCompactDateTimeRange("not-a-date", "2026-07-09T19:04:00+08:00"))
        assertEquals(
            "2026/07/09 18:52",
            formatCompactDateTimeRange("2026-07-09T18:52:00+08:00", "not-a-date")
        )
    }

    @Test
    fun crossDayWithinYearKeepsDateOnEnd() {
        assertEquals(
            "2026/07/09 23:55 \u2192 07/10 00:05",
            formatCompactDateTimeRange("2026-07-09T23:55:00+08:00", "2026-07-10T00:05:00+08:00")
        )
    }

    @Test
    fun earlierEndIsFormattedWithoutCrashing() {
        assertEquals(
            "2026/07/10 10:00 \u2192 07/09 09:00",
            formatCompactDateTimeRange("2026-07-10T10:00:00+08:00", "2026-07-09T09:00:00+08:00")
        )
    }
}

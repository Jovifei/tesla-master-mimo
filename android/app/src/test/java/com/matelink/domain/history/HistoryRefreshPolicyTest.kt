package com.matelink.domain.history

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class HistoryRefreshPolicyTest {
    private fun day(s: String) = LocalDate.parse(s)
    private fun clock(s: String, zone: String = "UTC") = Clock.fixed(Instant.parse(s), ZoneId.of(zone))
    @Test fun relativeWindowMovesAcrossMidnight() {
        val result = refreshedHistoryWindow(7, false, day("2026-09-25"), day("2026-10-01"), clock("2026-10-04T01:00:00Z"))
        assertEquals(HistoryDateWindow(day("2026-09-28"), day("2026-10-04")), result)
    }
    @Test fun todayUsesInjectedLocalZoneNotUtc() {
        val result = refreshedHistoryWindow(0, false, day("2026-10-01"), day("2026-10-01"), clock("2026-10-03T17:00:00Z", "Asia/Shanghai"))
        assertEquals(HistoryDateWindow(day("2026-10-04"), day("2026-10-04")), result)
    }
    @Test fun timezoneChangeWhileAliveUsesNewLocalDateAndMatchingBoundaryOffset() {
        var zone = ZoneId.of("UTC")
        val liveClock = CurrentLocalClock({ zone }, { Instant.parse("2026-10-03T17:00:00Z") })
        assertEquals(day("2026-10-03"), refreshedHistoryWindow(0, false, null, null, liveClock).end)
        zone = ZoneId.of("Asia/Shanghai")
        val window = refreshedHistoryWindow(0, false, null, null, liveClock)
        assertEquals(day("2026-10-04"), window.end)
        assertEquals("2026-10-04T00:00:00+08:00", com.matelink.domain.LocalDayBoundaries.startOfDay(window.start!!, liveClock.zone))
    }
    @Test fun customDoesNotMove() {
        val result = refreshedHistoryWindow(-1, true, day("2026-09-01"), day("2026-10-01"), clock("2026-10-04T01:00:00Z"))
        assertEquals(HistoryDateWindow(day("2026-09-01"), day("2026-10-01")), result)
    }
    @Test fun allTimeRemainsUnbounded() {
        assertEquals(HistoryDateWindow(null, null), refreshedHistoryWindow(null, false, null, null, clock("2026-10-04T01:00:00Z")))
    }
    @Test fun foregroundReturnRefreshesOnlyOnceAndNotAtFirstComposition() {
        val gate = HistoryResumeGate()
        assertFalse(gate.onResume()); assertFalse(gate.onResume())
        gate.onStop(); assertTrue(gate.onResume()); assertFalse(gate.onResume())
        gate.onStop(); gate.onStop(); assertTrue(gate.onResume()); assertFalse(gate.onResume())
    }
}

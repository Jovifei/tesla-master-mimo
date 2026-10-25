package com.matelink.ui.screens.charges

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeHistoryWindowTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 25)

    @Test fun selectedDayIncludesItsFinalMillisecondButExcludesNextMidnight() {
        assertTrue(chargeInHistoryWindow("2026-10-25T00:00:00+08:00", day, day, shanghai))
        assertTrue(chargeInHistoryWindow("2026-10-25T23:59:59.999+08:00", day, day, shanghai))
        assertFalse(chargeInHistoryWindow("2026-10-26T00:00:00+08:00", day, day, shanghai))
    }

    @Test fun historicalDayUsesItsLocalZoneAndUnknownTimeDoesNotInventMembership() {
        val instant = "2026-10-24T16:30:00Z"
        assertTrue(chargeInHistoryWindow(instant, day, day, shanghai))
        assertFalse(chargeInHistoryWindow(instant, day, day, ZoneId.of("America/Los_Angeles")))
        assertFalse(chargeInHistoryWindow(null, day, day, shanghai))
        assertTrue(chargeInHistoryWindow(null, null, null, shanghai))
    }
}

package com.matelink.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonthDayTimeFormatterTest {

    @Test
    fun formatsChineseMonthDayAnd24HourTimeWithoutYear() {
        assertEquals(
            "7月30日 19:05",
            formatMonthDayTime(
                "2026-07-30T19:05:00+08:00",
                locale = Locale.SIMPLIFIED_CHINESE,
                is24Hour = true
            )
        )
    }

    @Test
    fun formatsEnglishMonthDayAnd12HourTimeWithoutYear() {
        assertEquals(
            "Jul 30 07:05 PM",
            formatMonthDayTime(
                "2026-07-30T19:05:00+08:00",
                locale = Locale.US,
                is24Hour = false
            )
        )
    }

    @Test
    fun invalidValueReturnsNull() {
        assertNull(formatMonthDayTime("not-a-date"))
        assertNull(formatMonthDayTime(null))
    }
}

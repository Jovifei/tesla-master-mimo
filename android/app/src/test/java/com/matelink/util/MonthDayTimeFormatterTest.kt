package com.matelink.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.util.TimeZone

class MonthDayTimeFormatterTest {
    @get:Rule
    val systemTimeZone = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val original = TimeZone.getDefault()
                try {
                    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
                    base.evaluate()
                } finally {
                    TimeZone.setDefault(original)
                }
            }
        }
    }


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

    @Test
    fun utcConversionPreservesLocaleAndHourPreferences() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals(
            "7月30日 11:05",
            formatMonthDayTime(
                "2026-07-30T19:05:00+08:00",
                locale = Locale.SIMPLIFIED_CHINESE,
                is24Hour = true
            )
        )
        assertEquals(
            "Jul 30 11:05 AM",
            formatMonthDayTime(
                "2026-07-30T19:05:00+08:00",
                locale = Locale.US,
                is24Hour = false
            )
        )
    }
}

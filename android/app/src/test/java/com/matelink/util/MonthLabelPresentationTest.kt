package com.matelink.util

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class MonthLabelPresentationTest {
    @Test fun chineseMonthlyLabelsHaveNoInventedDay() {
        assertEquals(listOf("七月", "八月", "九月", "十月"), (7..10).map {
            LocalDate.of(2026, it, 1).formatMonthYear(Locale.SIMPLIFIED_CHINESE, false)
        })
    }
    @Test fun crossYearIsUnambiguous() {
        assertEquals("2025年十二月", LocalDate.of(2025, 12, 1).formatMonthYear(Locale.CHINA))
        assertEquals("2026年一月", LocalDate.of(2026, 1, 1).formatMonthYear(Locale.CHINA))
    }
    @Test fun dayWithinMonthDoesNotChangeLabel() {
        assertEquals(LocalDate.of(2026, 7, 1).formatMonthYear(Locale.CHINA),
            LocalDate.of(2026, 7, 31).formatMonthYear(Locale.CHINA))
    }
    @Test fun englishCompatibility() {
        assertEquals("Jul 26", LocalDate.of(2026, 7, 1).formatMonthYear(Locale.US))
        assertEquals("Jul", LocalDate.of(2026, 7, 1).formatMonthYear(Locale.US, false))
    }
}

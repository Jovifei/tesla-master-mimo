package com.matelink.ui.components

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeActivityGridTest {
    @Test
    fun mondayColumnsKeepTheSameWeekAcrossNewYear() {
        val monday = LocalDate.parse("2025-12-29")
        assertEquals(monday, chargeActivityWeekStart(LocalDate.parse("2026-01-01")))
        assertEquals(monday, chargeActivityWeekStart(LocalDate.parse("2026-01-04")))
        assertEquals(monday.plusWeeks(1), chargeActivityWeekStart(LocalDate.parse("2026-01-05")))
    }

    @Test
    fun colorScaleUsesSameFullColorAcrossWindows() {
        assertEquals(0f, chargeActivityIntensity(null), 0f)
        assertEquals(0f, chargeActivityIntensity(0.0), 0f)
        assertTrue(chargeActivityIntensity(40.0) < chargeActivityIntensity(60.0))
        assertEquals(chargeActivityIntensity(60.0), chargeActivityIntensity(120.0), 0f)
    }
}

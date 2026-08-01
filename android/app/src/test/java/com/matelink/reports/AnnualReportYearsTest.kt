package com.matelink.reports

import com.matelink.ui.screens.reports.availableReportYears
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnualReportYearsTest {
    @Test
    fun currentAndPreviousYearAreAlwaysPresent() {
        assertEquals(listOf(2026, 2025, 2024), availableReportYears(2026, listOf(2024, 2025)))
    }

    @Test
    fun duplicateAndInvalidYearsAreRemoved() {
        assertEquals(listOf(2026, 2025), availableReportYears(2026, listOf(0, -1, 2025, 2026, 2026)))
    }
}

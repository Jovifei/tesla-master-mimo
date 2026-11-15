package com.matelink.ui.components

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalendarPresentationTest {
    @Test fun aWeekContainsSevenDatesWithoutManufacturingRecords() {
        val end = LocalDate.of(2026, 11, 12)
        val source = listOf(HistoryDateGroup(end, end.toString(), listOf("drive", "parked")))
        val days = calendarHistoryGroups(source, end.minusDays(6), end)
        assertEquals(7, days.size)
        assertEquals(end, days.first().date)
        assertEquals(end.minusDays(6), days.last().date)
        assertEquals(listOf("drive", "parked"), days.flatMap { it.items })
        assertTrue(days.drop(1).all { it.items.isEmpty() })
    }
    @Test fun presentationRetainsUndatedAndBoundaryRecordsAndBoundsCalendarExpansion() {
        val end = LocalDate.of(2026, 11, 12)
        val source = listOf(HistoryDateGroup<String>(null, "unknown", listOf("undated")),
            HistoryDateGroup(end.minusDays(7), "boundary", listOf("parked")))
        assertEquals(listOf("undated", "parked"), calendarHistoryGroups(source, end.minusDays(6), end).flatMap { it.items })
        assertEquals(source, calendarHistoryGroups(source, end.minusYears(30), end))
        assertEquals(source, calendarHistoryGroups(source, end, end.minusDays(1)))
    }
    @Test fun energyColorScaleDoesNotRescaleWithOtherRecords() {
        assertEquals(0f, chargeEnergyShade(null), 0f)
        assertEquals(0f, chargeEnergyShade(0.0), 0f)
        assertEquals(0f, chargeEnergyShade(Double.NaN), 0f)
        assertEquals(2f / 3f, chargeEnergyShade(40.0), .0001f)
        assertEquals(1f, chargeEnergyShade(60.0), 0f)
        assertEquals(1f, chargeEnergyShade(120.0), 0f)
    }
}

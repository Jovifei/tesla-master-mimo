package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsWindowPolicyTest {

    @Test
    fun fewerThanSevenDays_reportsAvailableDaysAndEstimatedSevenDayWindow() {
        val policy = StatsWindowPolicy.resolve(6)

        assertEquals(6, policy.availableDays)
        assertTrue(policy.estimated7Day)
        assertEquals(listOf(StatsWindow.ALL), policy.supportedWindows)
    }

    @Test
    fun sevenThroughTwentyNineDays_supportsSevenDaysAndAll() {
        val sevenDayPolicy = StatsWindowPolicy.resolve(7)
        val twentyNineDayPolicy = StatsWindowPolicy.resolve(29)

        assertFalse(sevenDayPolicy.estimated7Day)
        assertEquals(listOf(StatsWindow.DAYS_7, StatsWindow.ALL), sevenDayPolicy.supportedWindows)
        assertFalse(twentyNineDayPolicy.estimated7Day)
        assertEquals(listOf(StatsWindow.DAYS_7, StatsWindow.ALL), twentyNineDayPolicy.supportedWindows)
    }

    @Test
    fun thirtyDaysOrMore_supportsSevenThirtyAndAll() {
        val policy = StatsWindowPolicy.resolve(30)

        assertFalse(policy.estimated7Day)
        assertEquals(
            listOf(StatsWindow.DAYS_7, StatsWindow.DAYS_30, StatsWindow.ALL),
            policy.supportedWindows
        )
    }
}

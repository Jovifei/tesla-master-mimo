package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginationGuardTest {

    @Test
    fun emptyPage_stopsWithoutChangingSeenIds() {
        val decision = PaginationGuard.evaluate(
            pageSize = 2,
            seenIds = setOf(1L),
            pageIds = emptyList()
        )

        assertTrue(decision.stop)
        assertEquals(setOf(1L), decision.seenIds)
    }

    @Test
    fun shortPage_stopsWithoutChangingSeenIds() {
        val decision = PaginationGuard.evaluate(
            pageSize = 3,
            seenIds = setOf(1L),
            pageIds = listOf(2L, 3L)
        )

        assertTrue(decision.stop)
        assertEquals(setOf(1L), decision.seenIds)
    }

    @Test
    fun repeatedLastItem_stopsToPreventInfiniteOverflowSync() {
        val decision = PaginationGuard.evaluate(
            pageSize = 1,
            seenIds = setOf(42L),
            pageIds = listOf(42L)
        )

        assertTrue(decision.stop)
        assertEquals(setOf(42L), decision.seenIds)
    }

    @Test
    fun fullPageWithNewIds_continuesAndRecordsAllNewIds() {
        val decision = PaginationGuard.evaluate(
            pageSize = 2,
            seenIds = setOf(1L),
            pageIds = listOf(2L, 3L)
        )

        assertFalse(decision.stop)
        assertEquals(setOf(1L, 2L, 3L), decision.seenIds)
    }
}

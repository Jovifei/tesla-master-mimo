package com.matelink.domain.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveReportPaginationPolicyTest {
    @Test
    fun initialNonEmptyRunStopsAfterNewestPage() {
        assertFalse(
            DriveReportPaginationPolicy.shouldRequestNextPage(
                currentCursor = null,
                candidateIds = (51..100).toList(),
                resultSize = 50,
                pageSize = 50
            )
        )
    }

    @Test
    fun activationCursorZeroContinuesAcrossFullPages() {
        assertTrue(
            DriveReportPaginationPolicy.shouldRequestNextPage(
                currentCursor = 0,
                candidateIds = (51..100).toList(),
                resultSize = 50,
                pageSize = 50
            )
        )
    }

    @Test
    fun positiveCursorContinuesWhileAllRowsAreNewer() {
        assertTrue(
            DriveReportPaginationPolicy.shouldRequestNextPage(
                currentCursor = 40,
                candidateIds = (51..100).toList(),
                resultSize = 50,
                pageSize = 50
            )
        )
    }

    @Test
    fun positiveCursorStopsWhenPageReachesExistingWatermark() {
        assertFalse(
            DriveReportPaginationPolicy.shouldRequestNextPage(
                currentCursor = 75,
                candidateIds = (51..100).toList(),
                resultSize = 50,
                pageSize = 50
            )
        )
    }

    @Test
    fun shortPageStopsForAnyCursor() {
        assertFalse(
            DriveReportPaginationPolicy.shouldRequestNextPage(
                currentCursor = 0,
                candidateIds = listOf(3, 2, 1),
                resultSize = 3,
                pageSize = 50
            )
        )
    }
}

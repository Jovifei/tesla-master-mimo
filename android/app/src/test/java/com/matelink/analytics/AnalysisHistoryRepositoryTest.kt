package com.matelink.analytics

import com.matelink.domain.analytics.AnalysisWindow
import com.matelink.domain.analytics.HistoryCoverage
import com.matelink.domain.analytics.NoDataReason
import com.matelink.domain.analytics.selectWindow
import com.matelink.domain.analytics.uniqueBySourceId
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisHistoryRepositoryTest {
    @Test
    fun allHistoryDoesNotCollapseToTheDefaultSevenDayWindow() {
        val records = listOf(
            TimedRecord(1, LocalDate.of(2025, 1, 1)),
            TimedRecord(2, LocalDate.now().minusDays(1))
        )

        assertEquals(2, selectWindow(records, AnalysisWindow.ALL_TIME, LocalDate.now()).size)
    }

    @Test
    fun sourceIdsAreDeduplicatedWithoutDroppingDistinctRecords() {
        val records = listOf(
            TimedRecord(1, LocalDate.of(2025, 1, 1)),
            TimedRecord(1, LocalDate.of(2025, 1, 1)),
            TimedRecord(2, LocalDate.of(2025, 1, 2))
        )

        assertEquals(listOf(1, 2), uniqueBySourceId(records).map { it.id })
    }

    @Test
    fun emptyHistoryExplainsWhyMetricsAreUnavailable() {
        val coverage = HistoryCoverage(
            driveCount = 0,
            chargeCount = 0,
            reason = NoDataReason.NO_RECORDS
        )

        assertTrue(coverage.isEmpty)
        assertEquals(NoDataReason.NO_RECORDS, coverage.reason)
    }

    private data class TimedRecord(
        override val id: Int,
        override val date: LocalDate
    ) : com.matelink.domain.analytics.DatedSourceRecord
}

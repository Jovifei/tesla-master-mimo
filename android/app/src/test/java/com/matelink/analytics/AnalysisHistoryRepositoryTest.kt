package com.matelink.analytics

import com.matelink.domain.analytics.AnalysisWindow
import com.matelink.domain.analytics.HistoryCoverage
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.analytics.NoDataReason
import com.matelink.domain.analytics.selectWindow
import com.matelink.domain.analytics.uniqueBySourceId
import com.matelink.domain.analytics.AnalysisHistorySnapshot
import com.matelink.domain.analytics.AnalysisHistorySnapshotCache
import com.matelink.domain.analytics.buildPersistedHistorySnapshot
import com.matelink.domain.analytics.classifyEmptyHistory
import com.matelink.domain.analytics.classifyMetricNoData
import com.matelink.data.local.entity.DriveSummary
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
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

    @Test
    fun emptyHistoryPreservesProviderCollectionState() {
        assertEquals(
            NoDataReason.COLLECTING,
            classifyEmptyHistory(0, 0, driveAvailability = "collecting")
        )
        assertEquals(
            NoDataReason.SOURCE_UNAVAILABLE,
            classifyEmptyHistory(0, 0, "unsupported", "unsupported")
        )
        assertEquals(
            NoDataReason.NO_RECORDS,
            classifyEmptyHistory(0, 0, "available", "available")
        )
    }

    @Test
    fun providerStateDoesNotOverrideNonEmptyHistory() {
        assertEquals(
            null,
            classifyEmptyHistory(1, 0, driveAvailability = "collecting")
        )
    }

    @Test
    fun metricStatesDistinguishFilterAndCoverageGaps() {
        assertEquals(
            NoDataReason.FILTER_EMPTY,
            classifyMetricNoData(null, sourceRecordCount = 3, selectedRecordCount = 0, validSampleCount = 0)
        )
        assertEquals(
            NoDataReason.INSUFFICIENT_COVERAGE,
            classifyMetricNoData(null, sourceRecordCount = 3, selectedRecordCount = 2, validSampleCount = 0)
        )
        assertEquals(
            NoDataReason.COLLECTING,
            classifyMetricNoData(NoDataReason.COLLECTING, 0, 0, 0)
        )
        assertNull(classifyMetricNoData(null, 3, 2, 1))
    }

    @Test
    fun customWindowIncludesBothBoundaries() {
        val records = listOf(
            TimedRecord(1, LocalDate.of(2026, 1, 1)),
            TimedRecord(2, LocalDate.of(2026, 1, 15)),
            TimedRecord(3, LocalDate.of(2026, 2, 1))
        )

        val selected = selectWindow(
            records,
            AnalysisWindow.CUSTOM,
            asOf = LocalDate.of(2026, 2, 10),
            customStart = LocalDate.of(2026, 1, 1),
            customEnd = LocalDate.of(2026, 1, 15)
        )

        assertEquals(listOf(1, 2), selected.map { it.id })
    }

    @Test
    fun cachedSnapshotIsMarkedStaleAfterARefreshFailure() {
        val cache = AnalysisHistorySnapshotCache()
        val snapshot = AnalysisHistorySnapshot(
            drives = emptyList(),
            charges = emptyList(),
            fetchedAt = Instant.parse("2026-08-21T00:00:00Z"),
            coverage = HistoryCoverage(0, 0, NoDataReason.NO_RECORDS)
        )

        cache.put(7, snapshot)

        val stale = cache.stale(7, "network unavailable")
        assertEquals(HistoryFreshness.STALE, stale?.freshness)
        assertEquals(snapshot.fetchedAt, stale?.fetchedAt)
        assertEquals("network unavailable", stale?.staleReason)
    }

    @Test
    fun persistedRoomHistoryIsUsableButMarkedStale() {
        val snapshot = buildPersistedHistorySnapshot(
            drives = listOf(
                DriveSummary(
                    driveId = 9,
                    carId = 7,
                    startDate = "2026-08-20T10:00:00Z",
                    endDate = "2026-08-20T10:20:00Z",
                    durationMin = 20,
                    startAddress = "A",
                    endAddress = "B",
                    distance = 10.0,
                    speedMax = 80,
                    speedAvg = 50,
                    powerMax = 100,
                    powerMin = -20,
                    startBatteryLevel = 60,
                    endBatteryLevel = 55,
                    outsideTempAvg = 22.0,
                    insideTempAvg = 21.0,
                    energyConsumed = 2.0,
                    efficiency = 200.0
                )
            ),
            charges = emptyList(),
            fetchedAt = Instant.parse("2026-08-21T00:00:00Z"),
            reason = "offline"
        )

        assertEquals(HistoryFreshness.STALE, snapshot?.freshness)
        assertEquals(1, snapshot?.coverage?.driveCount)
        assertEquals(10.0, snapshot?.drives?.single()?.distance ?: -1.0, 0.0)
        assertEquals("offline", snapshot?.staleReason)
    }

    private data class TimedRecord(
        override val id: Int,
        override val date: LocalDate
    ) : com.matelink.domain.analytics.DatedSourceRecord
}

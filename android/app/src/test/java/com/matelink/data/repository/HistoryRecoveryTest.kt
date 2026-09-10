package com.matelink.data.repository

import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.HistoryImportSession
import com.matelink.data.sync.HistoryUploadFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HistoryRecoveryTest {
    @Test fun everyPageIsRecoveredAndDuplicateIdsAreIdempotent() = runBlocking {
        val calls = mutableListOf<Int>()
        val result = loadHistoryPages(pageSize = 2, id = { it: Int -> it }) { page ->
            calls += page
            ApiResult.Success(when(page) { 1 -> listOf(1, 2); 2 -> listOf(2, 3); else -> emptyList() })
        }
        assertEquals(listOf(1, 2, 3), result.items)
        assertEquals(listOf(1, 2, 3), calls)
        assertNull(result.error)
    }

    @Test fun failedLaterPagePreservesEarlierRowsAndReportsPartial() = runBlocking {
        val result = loadHistoryPages(pageSize = 2, id = { it: Int -> it }) { page ->
            if (page == 1) ApiResult.Success(listOf(1, 2)) else ApiResult.Error("offline")
        }
        assertEquals(listOf(1, 2), result.items)
        assertNotNull(result.error)
    }

    @Test fun repeatedPageIsNotReportedAsACompleteArchive() = runBlocking {
        val result = loadHistoryPages(pageSize = 2, id = { it: Int -> it }) { ApiResult.Success(listOf(1, 2)) }
        assertEquals(2, result.items.size)
        assertEquals("history_repeated_page", result.error?.message)
    }

    @Test fun cancellationIsNotSwallowedAsOfflineHistory() = runBlocking {
        try {
            loadHistoryPages<Int>(id = { it }) { throw CancellationException("cancel") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun incompleteCloudSummaryCannotDowngradeMeasuredLocalRecord() {
        val measured = DriveData(10, startDate = "2026-09-01T00:00:00Z", endDate = "2026-09-01T01:00:00Z",
            energyConsumedNet = 8.0, source = "telemetry_mqtt", qualityState = "observed")
        val weak = measured.copy(energyConsumedNet = null, source = "local_import", qualityState = "incomplete")
        val result = UnifiedHistoryRepository.mergeDrives(listOf(weak), listOf(measured)).single()
        assertEquals(8.0, result.energyConsumedNet!!, 0.0)
        assertEquals("observed", result.qualityState)
        assertEquals("telemetry_mqtt", result.source)
    }

    @Test fun measuredRemoteDoesNotLaunderUnverifiedLocalFields() {
        val local = DriveData(10, energyConsumedNet = 8.0, source = "local_import", qualityState = "incomplete")
        val measured = local.copy(energyConsumedNet = null, source = "telemetry_mqtt", qualityState = "observed")
        val result = UnifiedHistoryRepository.mergeDrives(listOf(measured), listOf(local)).single()
        assertNull(result.energyConsumedNet)
    }

    @Test fun emptyCloudResponsePreservesOldPhoneHistory() {
        val local = ChargeData(1, startDate = "2025-01-01T00:00:00Z", qualityState = "incomplete", source = "local_import")
        assertEquals(listOf(local), UnifiedHistoryRepository.mergeCharges(emptyList(), listOf(local)))
    }

    @Test fun normalizedSessionTimesDeduplicateButMissingTimesDoNot() {
        val cached = DriveData(1, startDate = "2026-09-01T08:00:00+08:00", endDate = "2026-09-01T09:00:00+08:00")
        val remote = DriveData(2, startDate = "2026-09-01T00:00:00Z", endDate = "2026-09-01T01:00:00Z")
        assertEquals(1, UnifiedHistoryRepository.mergeDrives(listOf(remote), listOf(cached)).size)
        assertFalse(sameHistorySession(null, null, null, null))
    }

    @Test fun regularWindowUsesTwoDistinctUtcDataDaysWithoutDeletingArchive() {
        fun row(id: String, date: String) = HistoryImportSession(id, date, date)
        val drives = listOf(row("old", "2026-07-01T00:00:00Z"), row("new", "2026-09-01T00:00:00Z"))
        val charges = listOf(row("gap", "2026-08-01T08:00:00+08:00"))
        val window = HistoryUploadFilter.boundToLatestTwoDataDays(drives, charges)
        assertEquals(listOf("new"), window.drives.map { it.sessionId })
        assertEquals(listOf("gap"), window.charges.map { it.sessionId })
        assertEquals(2, drives.size)
        assertEquals(2, HistoryUploadFilter.keepValidatedArchive(drives, charges).drives.size)
    }

    @Test fun localIncompleteSummariesAreNotFilteredOutOfHistoryDateRange() {
        assertTrue(historyInRange("2026-09-01T08:00:00+08:00", "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z"))
        assertFalse(historyInRange("2026-09-02T00:00:00Z", "2026-09-01T00:00:00Z", "2026-09-02T00:00:00Z"))
    }
}

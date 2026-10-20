package com.matelink.ui.screens.updates

import com.matelink.data.api.models.UpdateData
import com.matelink.ui.screens.stats.ScopedUiLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class SoftwareVersionsProjectionTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test fun offsetAndUtcReceiptsSortByInstantAndShowLocalMonth() {
        val projection = projectSoftwareVersions(listOf(
            UpdateData(1, "2025.44", "2025-12-31T15:30:00Z", "2025-12-31T15:40:00Z"),
            UpdateData(2, "2026.2", "2026-01-01T00:30:00+08:00", "2026-01-01T00:30:00+08:00")
        ), null, Instant.parse("2026-01-03T00:00:00Z"), shanghai)

        assertEquals(listOf(2, 1), projection.items.map { it.id })
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 30), projection.items[0].installDate)
        assertEquals(LocalDateTime.of(2025, 12, 31, 23, 30), projection.items[1].installDate)
        assertTrue(projection.items[0].isCurrent)
        assertFalse(projection.items[1].isCurrent)
        assertEquals(0L, projection.items[0].updateDurationMinutes)
        assertEquals(10L, projection.items[1].updateDurationMinutes)
        assertEquals(1, projection.monthlyData.first { it.yearMonth == YearMonth.of(2026, 1) }.count)
        assertEquals(1, projection.monthlyData.first { it.yearMonth == YearMonth.of(2025, 12) }.count)
    }

    @Test fun sameInstantDifferentOffsetsKeepStableOrderAndRealZeroInterval() {
        val projection = projectSoftwareVersions(listOf(
            UpdateData(4, "a", "2026-10-21T14:30:00Z"),
            UpdateData(5, "b", "2026-10-21T22:30:00+08:00")
        ), null, Instant.parse("2026-10-22T00:00:00Z"), shanghai)
        assertEquals(listOf(4, 5), projection.items.map { it.id })
        assertEquals(projection.items[0].installDate, projection.items[1].installDate)
        assertEquals(0.0, projection.stats.meanDaysBetweenUpdates!!, 0.0)
        assertEquals(0L, projection.items[1].daysInstalled)
    }

    @Test fun missingOrInvalidEvidenceStaysUnknownAndDoesNotEnterDateFilter() {
        val inputs = listOf(
            UpdateData(1, "known", "2026-10-21T14:30:00Z", "2026-10-21T14:00:00Z"),
            UpdateData(2, "bad", "invalid", null)
        )
        val now = Instant.parse("2026-10-22T00:00:00Z")
        val all = projectSoftwareVersions(inputs, null, now, shanghai)
        assertEquals(2, all.stats.totalUpdates)
        assertNull(all.stats.meanDaysBetweenUpdates)
        assertEquals("known", all.stats.oldestVersion)
        assertNull(all.items[0].updateDurationMinutes)
        assertNull(all.items[1].installDate)
        assertNull(all.items[1].daysInstalled)
        val recent = projectSoftwareVersions(inputs, 6, now, shanghai)
        assertEquals(listOf(1), recent.items.map { it.id })
        assertEquals(1, recent.stats.totalUpdates)
        assertNull(recent.stats.meanDaysBetweenUpdates)
    }

    @Test fun halfDayIntervalIsNotRoundedToZeroDays() {
        val projection = projectSoftwareVersions(listOf(
            UpdateData(1, "older", "2026-10-20T00:00:00Z"),
            UpdateData(2, "newer", "2026-10-20T12:00:00Z")
        ), null, Instant.parse("2026-10-21T00:00:00Z"), shanghai)
        assertEquals(0.5, projection.stats.meanDaysBetweenUpdates!!, 0.0)
    }

    @Test fun lateOldAccountResponseCannotReplaceCurrentSoftwarePage() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val loader = ScopedUiLoader(scope)
        val state = MutableStateFlow(SoftwareVersionsUiState())
        val gate = CompletableDeferred<Unit>()
        var account = "A"
        val now = Instant.parse("2026-10-22T00:00:00Z")
        fun load(version: String, waiting: Boolean) {
            val captured = account
            loader.load(
                proof = { suspend { account == captured } },
                fetch = { request ->
                    if (waiting) withContext(NonCancellable) { gate.await(); request.requireCurrent() }
                    projectSoftwareVersions(listOf(UpdateData(7, version, "2026-10-21T14:30:00Z")),
                        null, now, shanghai)
                },
                onSuccess = { state.value = state.value.withProjection(it) },
                onFailure = { error("Unexpected current failure") }
            )
        }
        try {
            load("stale", true)
            advanceUntilIdle()
            account = "B"
            load("B", false)
            advanceUntilIdle()
            account = "A"
            load("current", false)
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("current", state.value.updates.single().version)
            assertFalse(state.value.isLoading)
        } finally { scope.cancel() }
    }
}

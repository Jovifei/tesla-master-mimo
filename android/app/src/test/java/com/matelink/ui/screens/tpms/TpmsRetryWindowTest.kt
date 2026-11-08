package com.matelink.ui.screens.tpms

import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.TpmsPressureSample
import com.matelink.domain.analytics.TpmsTrendAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class TpmsRetryWindowTest {
    private class Source : TpmsTrendHistorySource {
        val requests = mutableListOf<Triple<Int, TpmsTrendWindow, Long>>()
        var fail = false
        override suspend fun load7DaySamples(carId: Int, now: Long) = emptyList<TpmsPressureSample>()
        override suspend fun load30DaySamples(carId: Int, now: Long) = emptyList<TpmsPressureSample>()
        override suspend fun loadDrives(carId: Int) = emptyList<DriveSummary>()
        override suspend fun loadSnapshot(carId: Int, window: TpmsTrendWindow, now: Long): TpmsTrendHistorySnapshot {
            requests += Triple(carId, window, now)
            if (fail) { fail = false; error("synthetic unavailable history") }
            return TpmsTrendHistorySnapshot(emptyList(), emptyList())
        }
    }
    @Test fun defaultRetryAfterFailureUsesTheCurrentClockRatherThanTheFailedCutoff() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val source = Source().apply { fail = true }
        var failures = 0
        var successes = 0
        try {
            val controller = TpmsTrendRefreshController(source, TpmsTrendAnalyzer(), scope, { _, _ -> successes++ }, { failures++ })
            val old = System.currentTimeMillis() - 3600000L
            controller.refresh(7, TpmsTrendWindow.THIRTY, old)
            val beforeRetry = System.currentTimeMillis()
            controller.retry()
            val afterRetry = System.currentTimeMillis()
            assertEquals(1, failures); assertEquals(1, successes)
            assertEquals(old, source.requests[0].third)
            assertTrue(source.requests[1].third in beforeRetry..afterRetry)
            assertEquals(7, source.requests[1].first)
            assertEquals(TpmsTrendWindow.THIRTY, source.requests[1].second)
        } finally { scope.cancel() }
    }
    @Test fun repeatedRetriesKeepTheLatestCarAndWindowButAdvanceEachCutoff() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val source = Source()
        try {
            val controller = TpmsTrendRefreshController(source, TpmsTrendAnalyzer(), scope, { _, _ -> }, { error("unexpected failure") })
            controller.refresh(1, TpmsTrendWindow.THIRTY, 100L)
            controller.refresh(2, TpmsTrendWindow.SEVEN, 200L)
            controller.retry(300L)
            controller.retry(400L)
            assertEquals(listOf(Triple(1, TpmsTrendWindow.THIRTY, 100L), Triple(2, TpmsTrendWindow.SEVEN, 200L),
                Triple(2, TpmsTrendWindow.SEVEN, 300L), Triple(2, TpmsTrendWindow.SEVEN, 400L)), source.requests)
        } finally { scope.cancel() }
    }
    @Test fun retryWithoutAnInitialRequestDoesNotSelectAnArbitraryVehicle() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val source = Source()
        try {
            TpmsTrendRefreshController(source, TpmsTrendAnalyzer(), scope, { _, _ -> error("unexpected history") }, { error("unexpected failure") }).retry(10L)
            assertTrue(source.requests.isEmpty())
        } finally { scope.cancel() }
    }
}

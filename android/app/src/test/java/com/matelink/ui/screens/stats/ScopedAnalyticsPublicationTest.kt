package com.matelink.ui.screens.stats

import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.model.CarStats
import com.matelink.domain.model.QuickStats
import com.matelink.domain.model.YearFilter
import com.matelink.ui.screens.reports.AnnualReportResult
import com.matelink.ui.screens.reports.AnnualReportUiState
import com.matelink.ui.screens.reports.publishReport
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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScopedAnalyticsPublicationTest {
    @Test fun statsOldAccountAResponseCannotPublishAfterAtoBtoAWithSameRemoteCarId() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val loader = ScopedUiLoader(scope)
        val state = MutableStateFlow(StatsUiState())
        val oldGate = CompletableDeferred<Unit>()
        var account = "A"
        fun request(label: String, gate: CompletableDeferred<Unit>? = null) {
            val captured = account
            loader.load(proof = { suspend { account == captured } },
                fetch = { request ->
                    if (gate != null) withContext(NonCancellable) { gate.await(); request.requireCurrent() }
                    StatsLoadResult(null, listOf(label.toInt()), label.toFloat())
                },
                onSuccess = { state.value = state.value.publishStats(it) },
                onFailure = { error("Current request unexpectedly failed") })
        }
        try {
            request("1", oldGate)
            advanceUntilIdle()
            account = "B"
            request("2")
            advanceUntilIdle()
            account = "A"
            request("3")
            advanceUntilIdle()
            oldGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(3), state.value.availableYears)
            assertEquals(3f, state.value.deepSyncProgress, 0.0001f)
        } finally { scope.cancel() }
    }

    @Test fun annualOldResponseCannotReplaceNewSameRemoteIdAndYearAfterAccountSwitch() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val loader = ScopedUiLoader(scope)
        val state = MutableStateFlow(AnnualReportUiState(year = 2026))
        val oldGate = CompletableDeferred<Unit>()
        var account = "account-a"
        fun request(label: String, gate: CompletableDeferred<Unit>? = null) {
            val captured = account
            loader.load(proof = { suspend { account == captured && state.value.year == 2026 } },
                fetch = { request ->
                    if (gate != null) withContext(NonCancellable) { gate.await(); request.requireCurrent() }
                    AnnualReportResult(stats(2026, if (label == "old") 1.0 else 2.0),
                        emptyList(), emptyList(), null, HistoryFreshness.FRESH, label)
                },
                onSuccess = { state.value = state.value.publishReport(it) },
                onFailure = { error("Current request unexpectedly failed") })
        }
        try {
            request("old", oldGate)
            advanceUntilIdle()
            account = "account-b"
            request("new")
            advanceUntilIdle()
            oldGate.complete(Unit)
            advanceUntilIdle()

            assertEquals("new", state.value.currencySymbol)
            assertEquals(2.0, state.value.effectiveCost!!, 0.0001)
            assertTrue(!state.value.isLoading)
        } finally { scope.cancel() }
    }

    @Test fun oldCompletionCannotCloseNewerLoadingIndicator() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val loader = ScopedUiLoader(scope)
        val state = MutableStateFlow(StatsUiState(isLoading = true))
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        try {
            loader.load(proof = { suspend { true } },
                fetch = { request -> withContext(NonCancellable) { oldGate.await(); request.requireCurrent() }; 1 },
                onSuccess = { state.value = state.value.copy(isLoading = false) }, onFailure = {})
            advanceUntilIdle()
            loader.load(proof = { suspend { true } },
                fetch = { request -> newGate.await(); request.requireCurrent(); 2 },
                onSuccess = { state.value = state.value.copy(isLoading = false) }, onFailure = {})
            advanceUntilIdle()
            oldGate.complete(Unit)
            advanceUntilIdle()
            assertTrue(state.value.isLoading)
            newGate.complete(Unit)
            advanceUntilIdle()
            assertTrue(!state.value.isLoading)
        } finally { scope.cancel() }
    }

    @Test fun staleProofDropsLateFailureBeforeScopeFlowDeliversItsChange() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val loader = ScopedUiLoader(scope)
        val gate = CompletableDeferred<Unit>()
        var account = "account-a"
        var failures = 0
        try {
            val captured = account
            loader.load<Int>(proof = { suspend { account == captured } },
                fetch = { withContext(NonCancellable) { gate.await(); throw IllegalStateException("late") } },
                onSuccess = { error("A failed request must not succeed") },
                onFailure = { failures++ })
            advanceUntilIdle()
            account = "account-b" // scope flow has not emitted yet
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(0, failures)
        } finally { scope.cancel() }
    }

    private fun stats(year: Int, cost: Double) = CarStats(7, YearFilter.Year(year),
        QuickStats(0, 0.0, null, null, null, null, null,
            0, 0.0, cost, null, null,
            null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null),
        null, null)
}

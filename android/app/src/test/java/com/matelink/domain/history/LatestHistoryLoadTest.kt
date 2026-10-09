package com.matelink.domain.history

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class LatestHistoryLoadTest {
    @Test fun latePreviousVehicleCannotOverwriteNewVehicle() = runTest {
        val loader = LatestHistoryLoad()
        val late = CompletableDeferred<Unit>()
        var display = ""
        loader.launch(this) {
            withContext(NonCancellable) { late.await() }
            ensureCurrent()
            display = "car-a"
        }
        runCurrent()
        loader.launch(this) { ensureCurrent(); display = "car-b" }
        runCurrent()
        late.complete(Unit); runCurrent()
        assertEquals("car-b", display)
    }
    @Test fun latePreviousAccountErrorCannotReplaceNewAccountResult() = runTest {
        val loader = LatestHistoryLoad()
        val late = CompletableDeferred<Unit>()
        var display = ""
        loader.launch(this) {
            withContext(NonCancellable) { late.await() }
            ensureCurrent()
            display = "account-a-error"
        }
        runCurrent()
        loader.launch(this) { ensureCurrent(); display = "account-b" }
        runCurrent(); late.complete(Unit); runCurrent()
        assertEquals("account-b", display)
    }
    @Test fun cancellationBeforeLocalEnrichmentMustNotPublish() = runTest {
        val loader = LatestHistoryLoad()
        var updates = 0
        loader.launch(this) { delay(100); ensureCurrent(); updates += 100 }
        runCurrent()
        loader.launch(this) { ensureCurrent(); updates++ }
        runCurrent()
        assertEquals(1, updates)
    }
}

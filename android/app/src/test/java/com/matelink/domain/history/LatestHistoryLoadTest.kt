package com.matelink.domain.history

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    @Test fun identityInvalidationRejectsLateResultWithoutStartingAnotherLoad() = runTest {
        val loader = LatestHistoryLoad()
        val release = CompletableDeferred<Unit>()
        var published = false
        loader.launch(this) {
            withContext(NonCancellable) { release.await() }
            ensureCurrent()
            published = true
        }
        runCurrent()
        loader.invalidate()
        release.complete(Unit)
        runCurrent()
        assertFalse(published)
    }

    @Test fun inlinePublicationStartingAnotherLoadKeepsTheNewJobAsCancellationOwner() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val loader = LatestHistoryLoad()
        var entered = false
        var cancelled = false
        try {
            loader.launch(scope) {
                loader.launch(scope) {
                    try { entered = true; awaitCancellation() } finally { cancelled = true }
                }
            }
            assertTrue(entered)
            loader.invalidate()
            assertTrue("The latest reentrant request, not the completed parent, must be cancelled", cancelled)
        } finally { scope.cancel() }
    }
}

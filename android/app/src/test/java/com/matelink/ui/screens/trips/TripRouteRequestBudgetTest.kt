package com.matelink.ui.screens.trips

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRouteRequestBudgetTest {
    @Test fun moreThanTwelveUncachedDrivesNeverStartAProviderDetailRequest() = runTest {
        var calls = 0
        val result = fetchBoundedTripRoute((1..13).toList(), { true }) { id ->
            calls++
            id
        }
        assertTrue(result.isEmpty())
        assertEquals(0, calls)
    }

    @Test fun twelveDrivesReturnInOrderWithAtMostTwoRequestsInFlight() = runTest {
        var active = 0
        var peak = 0
        val result = fetchBoundedTripRoute((1..12).toList(), { true }) { id ->
            active++
            peak = maxOf(peak, active)
            delay(10)
            active--
            id
        }
        assertEquals((1..12).toList(), result)
        assertEquals(2, peak)
        assertEquals(0, active)
    }

    @Test fun scopeChangeStopsAdmissionOfQueuedRequests() = runTest {
        var current = true
        val requested = mutableListOf<Int>()
        val result = fetchBoundedTripRoute((1..12).toList(), { current }) { id ->
            requested += id
            if (id == 1) current = false
            delay(10)
            id
        }
        assertTrue(result.isEmpty())
        assertTrue(requested.isNotEmpty())
        assertTrue(requested.size <= 2)
        assertTrue(requested.all { it <= 2 })
    }

    @Test fun cancellingTheOwningLoadCancelsItsInFlightChildren() = runTest {
        val started = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<Unit>()
        var completed = 0
        val job = launch {
            fetchBoundedTripRoute((1..12).toList(), { true }) { id ->
                started.complete(Unit)
                pending.await()
                completed++
                id
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        pending.complete(Unit)
        yield()
        assertEquals(0, completed)
    }
}

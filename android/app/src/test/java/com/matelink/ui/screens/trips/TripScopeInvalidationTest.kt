package com.matelink.ui.screens.trips

import com.matelink.domain.model.Trip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripScopeInvalidationTest {
    private fun oldTrip() = Trip(
        drives = emptyList(), charges = emptyList(), totalDistance = 10.0,
        totalDrivingDurationMin = 20, totalDurationMin = 20, totalEnergyConsumed = null,
        totalEnergyCharged = 0.0, totalChargeCost = 8.0, avgEfficiency = null,
        maxSpeed = 80, startAddress = "old account", endAddress = "old car",
        startDate = "2026-10-14T10:00:00Z", endDate = "2026-10-14T10:20:00Z",
        startBatteryLevel = 80, endBatteryLevel = 75)

    @Test fun sameRemoteCarIdWithNewAccountScopeClearsAllOldDetailAndRejectsLateResult() = runTest {
        val remoteCarId = 1
        val changes = MutableSharedFlow<Int?>(extraBufferCapacity = 1)
        var proofCurrent = true
        var generation = 1
        var state = TripDetailUiState(isLoading = false, trip = oldTrip(),
            routeSegments = listOf(TripRouteSegment(listOf(TripRoutePoint(1.0, 2.0)))),
            markers = listOf(TripMapMarker(1.0, 2.0, TripMapPointType.START, "old")),
            countries = listOf(TripCountry("CN", "🇨🇳")),
            chargeCosts = TripChargeCostProjection(emptyList(), 8.0, null, false, 0, 0))
        val oldGeneration = generation
        val late = CompletableDeferred<TripDetailUiState>()
        val oldLoad = backgroundScope.launch {
            val arrived = late.await()
            if (generation == oldGeneration && proofCurrent) state = arrived
        }
        backgroundScope.launch {
            observeTripScopeInvalidation(changes, { proofCurrent }, {
                generation++
                oldLoad.cancel()
                state = TripDetailUiState(isLoading = false, isMapLoading = false)
            })
        }
        runCurrent()

        proofCurrent = false
        changes.emit(102) // Remote numeric carId remains 1; account/origin proof changed.
        runCurrent()
        assertEquals(1, remoteCarId)
        assertNull(state.trip)
        assertNull(state.chargeCosts)
        assertTrue(state.routeSegments.isEmpty())
        assertTrue(state.markers.isEmpty())
        assertTrue(state.countries.isEmpty())
        assertFalse(state.isMapLoading)

        late.complete(TripDetailUiState(isLoading = false, trip = oldTrip()))
        runCurrent()
        assertNull(state.trip)
    }

    @Test fun unavailableTransitionStaysEmptyUntilAValidScopeCanRetry() = runTest {
        val changes = MutableSharedFlow<Int?>(extraBufferCapacity = 1)
        var proofCurrent = true
        var pendingReload = false
        var reloads = 0
        var visible = true
        backgroundScope.launch {
            observeTripScopeInvalidation(changes, { proofCurrent }, {
                visible = false
                pendingReload = true
            }, onAvailable = {
                if (pendingReload) { reloads++; pendingReload = false }
            })
        }
        runCurrent()

        proofCurrent = false
        changes.emit(null)
        runCurrent()
        assertFalse(visible)
        assertEquals(0, reloads)

        proofCurrent = true // New account has a verified cached vehicle mapping.
        changes.emit(202)
        runCurrent()
        assertEquals(1, reloads)
        assertFalse(visible) // New data is displayed only after its own read completes.
    }
}

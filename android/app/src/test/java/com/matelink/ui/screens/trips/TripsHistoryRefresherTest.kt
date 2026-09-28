package com.matelink.ui.screens.trips

import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.VehicleContext
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.UnifiedHistory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TripsHistoryRefresherTest {
    @Test
    fun refreshesRemoteHistoryBeforeReadingTripsFromTheScopedLocalVehicle() = runTest {
        val calls = mutableListOf<String>()
        val context = VehicleContext(
            remoteApiCarId = 7,
            stableIdentity = "vehicle-7",
            localHistoryCarId = 701,
            connectionSource = HistoryConnectionSource.SELF_HOSTED,
            serverIdentity = "http://adapter"
        )
        val refresher = TripsHistoryRefresher(
            refreshHistory = {
                calls += "remote:$it"
                ApiResult.Success(UnifiedHistory(context, emptyList(), emptyList(), true, true))
            },
            resolveCachedLocalId = { error("fallback must not run after a successful refresh") },
            loadTrips = {
                calls += "trips:$it"
                emptyList()
            },
            loadDcChargeIds = {
                calls += "charges:$it"
                setOf(41)
            }
        )

        val result = refresher.refresh(7)

        assertEquals(701, result.localHistoryCarId)
        assertEquals(setOf(41), result.dcChargeIds)
        assertEquals(listOf("remote:7", "trips:701", "charges:701"), calls)
        assertEquals(null, result.historySyncWarning)
    }

    @Test
    fun remoteFailureKeepsReadingTheExistingScopedCache() = runTest {
        val calls = mutableListOf<String>()
        val refresher = TripsHistoryRefresher(
            refreshHistory = {
                calls += "remote:$it"
                ApiResult.Error("offline")
            },
            resolveCachedLocalId = {
                calls += "resolve:$it"
                901
            },
            loadTrips = {
                calls += "trips:$it"
                emptyList()
            },
            loadDcChargeIds = {
                calls += "charges:$it"
                emptySet()
            }
        )

        val result = refresher.refresh(9)

        assertEquals(901, result.localHistoryCarId)
        assertEquals("offline", result.historySyncWarning)
        assertEquals(listOf("remote:9", "resolve:9", "trips:901", "charges:901"), calls)
    }

    @Test
    fun missingVehicleScopeNeverFallsBackToTheRemoteNumericCarId() = runTest {
        val localReads = mutableListOf<Int>()
        val refresher = TripsHistoryRefresher(
            refreshHistory = { ApiResult.Error("offline") },
            resolveCachedLocalId = { null },
            loadTrips = {
                localReads += it
                emptyList()
            },
            loadDcChargeIds = {
                localReads += it
                emptySet()
            }
        )

        val result = refresher.refresh(9)

        assertEquals(null, result.localHistoryCarId)
        assertEquals(emptyList<Int>(), localReads)
        assertEquals("history_identity_unavailable", result.historySyncWarning)
    }
}

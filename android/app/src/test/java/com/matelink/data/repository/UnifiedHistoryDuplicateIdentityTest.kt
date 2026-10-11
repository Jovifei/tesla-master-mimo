package com.matelink.data.repository

import com.matelink.data.api.models.CarData
import com.matelink.data.local.HistoryConnectionSource
import com.matelink.data.local.HistoryReadScope
import com.matelink.data.local.VehicleContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UnifiedHistoryDuplicateIdentityTest {
    @Test fun conflictingDuplicateNumericCarIdsNeverAuthorizeRemoteReadsOrCreateMapping() = runBlocking {
        val fixture = Fixture()
        val result = fixture.repository().load(7)
        assertTrue(result is ApiResult.Error)
        assertEquals(ApiErrorKind.INVALID_RESPONSE, (result as ApiResult.Error).kind)
        assertEquals(1, fixture.carsCalls)
        assertEquals(0, fixture.identityAllocations)
        assertEquals(0, fixture.remoteReads)
    }

    @Test fun duplicateDiscoveryCanOnlyRestorePreviouslyVerifiedOfflineOriginCache() = runBlocking {
        val fixture = Fixture()
        fixture.cached = VehicleContext(7, "verified-opaque", -7, fixture.scope.source, "cloud")
        val result = fixture.repository().resolveContext(7) as ApiResult.Success
        assertEquals(-7, result.data.context.localHistoryCarId)
        assertFalse(result.data.remoteAuthorized)
        assertEquals(ApiErrorKind.INVALID_RESPONSE, result.data.identityError?.kind)
        assertEquals(0, fixture.identityAllocations)
        assertEquals(0, fixture.remoteReads)
    }

    @Test fun non404IdentityFailureNeverFallsBackToNumericCarsDiscovery() = runBlocking {
        val fixture = Fixture()
        fixture.identityError = ApiResult.Error("synthetic-denied", 403)
        assertTrue(fixture.repository().load(7) is ApiResult.Error)
        assertEquals(0, fixture.carsCalls)
        assertEquals(0, fixture.identityAllocations)
    }

    private class Fixture {
        val scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "synthetic-account", "https://a.invalid")
        var identityError = ApiResult.Error("synthetic-not-supported", 404)
        var cached: VehicleContext? = null
        var carsCalls = 0
        var identityAllocations = 0
        var remoteReads = 0
        fun repository() = UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { scope },
            getCars = { carsCalls++; ApiResult.Success(listOf(CarData(7, vehicleUid = "provider-a"),
                CarData(7, vehicleUid = "provider-b"))) },
            getHistoryContext = { identityError },
            resolveCar = { _, _ -> identityAllocations++; error("No ambiguous identity may be allocated") },
            cachedContext = { _, _ -> cached },
            localDrives = { emptyList() }, localCharges = { emptyList() },
            getDrives = { _, _, _, _ -> remoteReads++; error("No ambiguous remote history read") },
            getCharges = { _, _, _, _ -> remoteReads++; error("No ambiguous remote history read") },
            persistDrives = { error("No writes expected") }, persistCharges = { error("No writes expected") }
        ))
    }
}

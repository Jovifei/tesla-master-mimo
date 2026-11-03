package com.matelink.data.repository

import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.HistoryFreshness
import com.matelink.domain.analytics.analysisFreshness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual repository orchestration, including forbidden opposite-family I/O. */
class HistoryFamilyReadTest {
    private class Fixture {
        var scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "account-a", "https://a.invalid")
        val context = VehicleContext(7, "verified-a", -7, HistoryConnectionSource.CLOUD, "cloud")
        val drive = DriveData(1, startDate = "2026-10-01T00:00:00Z", endDate = "2026-10-01T01:00:00Z",
            source = "teslamate_archive", qualityState = "observed", qualityReason = "api")
        val charge = ChargeData(2, startDate = "2026-10-01T02:00:00Z", endDate = "2026-10-01T03:00:00Z",
            source = "teslamate_archive", qualityState = "observed", qualityReason = "api", chargeEnergyAdded = 0.0)
        var localDrives: List<DriveSummary> = emptyList()
        var localCharges: List<ChargeSummary> = emptyList()
        var requestedDrives = 0; var requestedCharges = 0
        var driveReads = 0; var chargeReads = 0; var driveWrites = 0; var chargeWrites = 0
        var drives: suspend () -> ApiResult<List<DriveData>> = { ApiResult.Success(listOf(drive)) }
        var charges: suspend () -> ApiResult<List<ChargeData>> = { ApiResult.Success(listOf(charge)) }
        fun repository() = UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { scope }, getCars = { error("unneeded cars fallback") },
            getHistoryContext = { ApiResult.Success(HistoryContextData(1, 7, "vehicle-a")) },
            resolveCar = { _, _ -> context }, cachedContext = { _, _ -> context },
            localDrives = { driveReads++; localDrives }, localCharges = { chargeReads++; localCharges },
            getDrives = { _, _, _, _ -> requestedDrives++; drives() },
            getCharges = { _, _, _, _ -> requestedCharges++; charges() },
            persistDrives = { driveWrites++ }, persistCharges = { chargeWrites++ }))
    }

    @Test fun chargePageNeverWaitsForOrWritesDrives() = runTest {
        val f = Fixture(); f.drives = { error("unrelated drive provider must not run") }
        val result = (f.repository().loadCharges(7) as ApiResult.Success).data
        assertEquals(listOf(2), result.charges.map { it.chargeId })
        assertEquals(0.0, result.charges.single().batteryInputKwh!!, 0.0)
        assertEquals(0, f.requestedDrives); assertEquals(0, f.driveReads); assertEquals(0, f.driveWrites)
        assertEquals(1, f.requestedCharges); assertEquals(1, f.chargeWrites)
        assertEquals(HistoryFreshness.FRESH, result.analysisFreshness(HistoryFamilies.CHARGES))
        assertEquals(HistoryFreshness.STALE, result.analysisFreshness(HistoryFamilies.ALL))
    }

    @Test fun drivePageNeverWaitsForOrWritesCharges() = runTest {
        val f = Fixture(); f.charges = { error("unrelated charge provider must not run") }
        val result = (f.repository().loadDrives(7) as ApiResult.Success).data
        assertEquals(listOf(1), result.drives.map { it.driveId })
        assertEquals(0, f.requestedCharges); assertEquals(0, f.chargeReads); assertEquals(0, f.chargeWrites)
        assertEquals(1, f.driveWrites)
        assertEquals(HistoryFreshness.FRESH, result.analysisFreshness(HistoryFamilies.DRIVES))
    }

    @Test fun successfulEmptyResponsePreservesArchiveButCannotMarkRetainedRecordsFresh() = runTest {
        val f = Fixture(); f.localCharges = listOf(f.charge.toSyncSummary(-7)!!)
        f.charges = { ApiResult.Success(emptyList()) }
        val result = (f.repository().loadCharges(7) as ApiResult.Success).data
        assertEquals(listOf(2), result.charges.map { it.chargeId })
        assertTrue(result.chargesFromRemote)
        assertTrue(result.chargesIncludeCachedEvidence)
        assertEquals(HistoryFreshness.STALE, result.analysisFreshness(HistoryFamilies.CHARGES))
        assertEquals(0, f.driveReads)
    }

    @Test fun cachedChargeReadDoesNotTouchDriveDaoOrProviderAndNeverClaimsFresh() = runTest {
        val f = Fixture(); f.localCharges = listOf(f.charge.toSyncSummary(-7)!!)
        val result = (f.repository().loadCached(7, HistoryFamilies.CHARGES) as ApiResult.Success).data
        assertEquals(1, result.charges.size)
        assertEquals(0, f.requestedCharges); assertEquals(0, f.requestedDrives); assertEquals(0, f.driveReads)
        assertEquals(0, f.chargeWrites)
        assertEquals(HistoryFreshness.STALE, result.analysisFreshness(HistoryFamilies.CHARGES))
    }

    @Test fun identityChangeDuringChargeReadCannotPublishOrPersistTheResponse() = runTest {
        val f = Fixture(); f.charges = {
            f.scope = f.scope.copy(accountNamespace = "account-b")
            ApiResult.Success(listOf(f.charge))
        }
        assertTrue(f.repository().loadCharges(7) is ApiResult.Error)
        assertEquals(0, f.chargeWrites)
        assertEquals(0, f.driveWrites)
    }

    @Test fun cancellationIsNotConvertedIntoAnEmptySuccessfulRefresh() = runTest {
        val f = Fixture(); f.charges = { throw CancellationException("cancelled") }
        var cancelled = false
        try { f.repository().loadCharges(7) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled); assertEquals(0, f.chargeWrites); assertEquals(0, f.driveWrites)
    }
}

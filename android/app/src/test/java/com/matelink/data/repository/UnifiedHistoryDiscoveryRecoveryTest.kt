package com.matelink.data.repository

import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.HistoryContextData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.ChargeData
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.withResolvedDriveEnergy
import com.matelink.domain.analytics.resolveDriveEnergy
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DrivePosition
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.domain.analytics.toRawAnalysisDriveData
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.analytics.withDetailEvidence
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.ChargeBatteryDetails
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.local.*
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.ChargeSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UnifiedHistoryDiscoveryRecoveryTest {
    private class Fixture {
        val original = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "account-a", "https://api.example")
        var scope: HistoryReadScope? = original
        val context = VehicleContext(7, "opaque-verified-vehicle", -7, HistoryConnectionSource.CLOUD, "cloud")
        var cached: VehicleContext? = context
        var cars: ApiResult<List<CarData>> = ApiResult.Error("provider unavailable", 503)
        var identity: ApiResult<HistoryContextData> = ApiResult.Success(HistoryContextData(1, 7, "provider-a"))
        var carsCalls = 0
        var identityCalls = 0
        var localReads = 0
        var legacyPending = false
        var localDriveOverride: List<DriveSummary>? = null
        var localChargeOverride: List<ChargeSummary>? = null
        val oldDrive = DriveData(1, startDate = "2026-10-01T00:00:00Z", endDate = "2026-10-01T01:00:00Z", source = "teslamate_archive", qualityState = "observed")
        val newDrive = oldDrive.copy(driveId = 2, startDate = "2026-10-04T00:00:00Z", endDate = "2026-10-04T01:00:00Z")
        val oldCharge = ChargeData(1, startDate = "2026-09-29T00:00:00Z", endDate = "2026-09-29T01:00:00Z", source = "teslamate_archive", qualityState = "observed")
        val newCharge = oldCharge.copy(chargeId = 2, startDate = "2026-10-03T00:00:00Z", endDate = "2026-10-03T01:00:00Z")
        val driveCalls = mutableListOf<Int>()
        val chargeCalls = mutableListOf<Int>()
        val persistedDrives = mutableListOf<DriveSummary>()
        val persistedCharges = mutableListOf<ChargeSummary>()
        val diagnostics = mutableListOf<Pair<String, Int?>>()
        val diagnosticErrors = mutableListOf<ApiResult.Error>()
        var fetchDrives: suspend (Int) -> ApiResult<List<DriveData>> = { ApiResult.Success(listOf(newDrive)) }
        var fetchCharges: suspend (Int) -> ApiResult<List<ChargeData>> = { ApiResult.Success(listOf(newCharge)) }
        var afterDrivePersist: () -> Unit = {}
        fun repository() = UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { scope ?: throw HistoryIdentityUnavailableException() },
            getCars = { carsCalls++; cars },
            getHistoryContext = { id -> check(id == 7); identityCalls++; identity },
            resolveCar = { car, expected -> check(car.carId == 7 && car.vehicleUid == "provider-a"); check(expected == scope); cached = context; context },
            cachedContext = { id, expected -> if (id == 7 && expected == original && scope == expected) cached else null },
            localDrives = { id -> localReads++; check(id == -7); localDriveOverride ?: listOf(oldDrive.toLocalSummary(id)!!) },
            localCharges = { id -> localReads++; check(id == -7); localChargeOverride ?: listOf(oldCharge.toLocalSummary(id)!!) },
            getDrives = { id, _, _, page -> check(id == 7); driveCalls += page; fetchDrives(page) },
            getCharges = { id, _, _, page -> check(id == 7); chargeCalls += page; fetchCharges(page) },
            persistDrives = { rows -> persistedDrives += rows; afterDrivePersist() },
            persistCharges = { rows -> persistedCharges += rows },
            reportFailure = { stage, _, error -> diagnostics += stage to error.code; diagnosticErrors += error },
            legacyLinkPending = { _, _, _ -> legacyPending }
        ))
    }
    @Test fun actualOfflineHistoryLoadProjectsUnknownYetPersistsRawSourceReceipt() = runTest {
        val f = Fixture()
        val old = DriveData(1, startDate = "2026-10-01T00:00:00Z",
            endDate = "2026-10-01T01:00:00Z",
            source = "telemetry_mqtt", qualityState = "observed",
            energyConsumedNet = 8.0)
        val stored = old.toSyncSummary(-7)!!.copy(
            energyConsumed = 8.0, energySource = "api")
        val originalJson = requireNotNull(stored.apiEvidence)
        f.localDriveOverride = listOf(stored)
        f.fetchDrives = { ApiResult.Success(emptyList()) }
        f.fetchCharges = { ApiResult.Success(emptyList()) }
        val result = f.repository().load(7) as ApiResult.Success
        assertEquals(1, result.data.drives.size)
        assertNull(result.data.drives.single().netEnergyKwh)
        assertNull(result.data.drives.single().energyConsumedNet)
        assertEquals("telemetry_mqtt", result.data.drives.single().source)
        val toPersist = f.persistedDrives.single()
        assertEquals(-7, toPersist.carId)
        assertEquals(1, toPersist.driveId)
        assertNull(toPersist.energyConsumed)
        assertEquals(originalJson, toPersist.apiEvidence)
        assertEquals(8.0,
            HistorySummaryEvidenceCodec.decodeDrive(toPersist.apiEvidence!!)!!.energyConsumedNet!!,
            0.0)
    }

    @Test fun offlineLoadRetainsVerifiedDetailInDisplayAndOriginalFleetScalarInRawReceipt() = runTest {
        val f = Fixture()
        val start = "2026-10-01T00:00:00Z"
        val end = "2026-10-01T00:00:10Z"
        val raw = DriveData(1, startDate = start, endDate = end,
            source = "telemetry_mqtt", qualityState = "observed",
            energyConsumedNet = 8.0,
            odometerDetails = DriveOdometerDetails(distance = 10.0))
        val original = raw.toSyncSummary(-7)!!
        val detail = DriveDetail(1, source = "telemetry_mqtt",
            startDate = start, endDate = end,
            odometerDetails = DriveOdometerDetails(distance = 10.0),
            positions = listOf(DrivePosition(date = start, power = 360.0),
                DrivePosition(date = end, power = 360.0)))
        val enriched = original.withResolvedDriveEnergy(detail, detail.resolveDriveEnergy())
        f.localDriveOverride = listOf(enriched)
        f.fetchDrives = { ApiResult.Success(emptyList()) }
        f.fetchCharges = { ApiResult.Success(emptyList()) }
        val returned = (f.repository().load(7) as ApiResult.Success).data
        assertEquals(1, returned.drives.size)
        assertEquals(1.0, returned.drives.single().netEnergyKwh!!, 1e-12)
        val persisted = f.persistedDrives.single()
        assertEquals(1.0, persisted.toAnalysisDriveData().netEnergyKwh!!, 1e-12)
        assertEquals(8.0, persisted.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
        assertEquals(HistorySummaryEvidenceCodec.sourceJson(original.apiEvidence),
            HistorySummaryEvidenceCodec.sourceJson(persisted.apiEvidence))
        assertTrue(returned.drivesFromRemote)
        // Empty remote is still not a genuine fresh history event.
    }

    @Test fun verifiedDetailPresentationSurvivesRealHistoryLoadAndWeakCloudPage() = runTest {
        val f = Fixture()
        val start = "2026-10-01T00:00:00Z"
        val end = "2026-10-01T00:00:10Z"
        val raw = DriveData(1, startDate = start, endDate = end,
            source = "telemetry_mqtt", qualityState = "observed",
            energyConsumedNet = 8.0,
            odometerDetails = DriveOdometerDetails(distance = 1.0))
        val original = raw.toSyncSummary(-7)!!
        val detail = DriveDetail(1, source = "telemetry_mqtt",
            startDate = start, endDate = end,
            odometerDetails = DriveOdometerDetails(distance = 2.0),
            batteryDetails = DriveBatteryDetails(80, 70), speedMax = 92,
            startAddress = "current origin", endAddress = "current destination",
            positions = listOf(DrivePosition(date = start, power = 360.0),
                DrivePosition(date = end, power = 360.0)))
        val enriched = original.withResolvedDriveEnergy(detail, detail.resolveDriveEnergy())
        f.localDriveOverride = listOf(enriched)
        f.fetchDrives = { ApiResult.Success(listOf(raw.copy(
            energyConsumedNet = null, speedMax = 33))) }
        f.fetchCharges = { ApiResult.Success(emptyList()) }
        val result = (f.repository().load(7) as ApiResult.Success).data
        val drive = result.drives.single()
        assertEquals(2.0, drive.distance!!, 0.0)
        assertEquals(80, drive.startBatteryLevel)
        assertEquals(70, drive.endBatteryLevel)
        assertEquals(92, drive.speedMax)
        assertEquals(500.0, drive.efficiencyWhKm!!, 1e-12)
        assertEquals(1.0, drive.netEnergyKwh!!, 1e-12)
        assertEquals("current origin", drive.startAddress)
        val stored = f.persistedDrives.single()
        assertEquals(2.0, stored.distance, 0.0)
        assertEquals(80, stored.startBatteryLevel)
        assertEquals(70, stored.endBatteryLevel)
        assertEquals(1.0, stored.energyConsumed!!, 1e-12)
        assertEquals(500.0, stored.efficiency!!, 1e-12)
        assertEquals(original.apiEvidence, HistorySummaryEvidenceCodec.sourceJson(stored.apiEvidence))
        assertEquals(8.0, stored.toRawAnalysisDriveData().energyConsumedNet!!, 0.0)
    }

    @Test fun parkedChargeMetadataRetainsCurrentBatteryAndAddressAcrossRealLoad() = runTest {
        val f = Fixture()
        val start = "2026-09-29T00:00:00Z"
        val end = "2026-09-29T01:00:00Z"
        val raw = ChargeData(1, startDate = start, endDate = end,
            address = "old location", source = "telemetry_mqtt",
            qualityState = "observed", chargeEnergyAdded = 12.0)
        val initial = raw.toSyncSummary(-7)!!
        val metric = EnergyMetric(valueKwh = 2.0, source = "telemetry_mqtt",
            quality = "reported", method = "session_counter_delta",
            measurementPoint = "battery_input", coverageKind = "endpoints",
            coverageRatio = 1.0, startDate = start, endDate = end)
        val detailed = ChargeDetail(1, startDate = start, endDate = end,
            source = "telemetry_mqtt", address = "new location",
            batteryDetails = ChargeBatteryDetails(80, 90),
            energyContract = EnergyContract(batteryInput = metric))
        f.localChargeOverride = listOf(initial.withDetailEvidence(detailed))
        f.fetchCharges = { ApiResult.Success(listOf(raw.copy(chargeEnergyAdded = null))) }
        f.fetchDrives = { ApiResult.Success(emptyList()) }
        val result = (f.repository().load(7) as ApiResult.Success).data
        val charge = result.charges.single()
        assertEquals("new location", charge.address)
        assertEquals(80, charge.startBatteryLevel)
        assertEquals(90, charge.endBatteryLevel)
        assertEquals(2.0, charge.batteryInputKwh!!, 0.0)
        val stored = f.persistedCharges.single()
        assertEquals("new location", stored.address)
        assertEquals(80, stored.startBatteryLevel)
        assertEquals(90, stored.endBatteryLevel)
        assertEquals(2.0, stored.energyAdded, 0.0)
        assertEquals(initial.apiEvidence, HistorySummaryEvidenceCodec.sourceJson(stored.apiEvidence))
        assertEquals(12.0, stored.toRawAnalysisChargeData().chargeEnergyAdded!!, 0.0)
    }

    @Test fun identityDiagnosticPreservesTypedFailureWithoutAuthorizingHistory() = runTest {
        val f = Fixture()
        f.identity = ApiResult.Error("private response", kind = ApiErrorKind.INVALID_RESPONSE, safeFailure = SafeApiFailure.JSON_DATA)
        f.repository().load(7)
        assertEquals(SafeApiFailure.JSON_DATA, f.diagnosticErrors.single().safeFailure)
        assertTrue(f.driveCalls.isEmpty()); assertTrue(f.chargeCalls.isEmpty())
    }
    @Test fun carsFailuresStillFetchBothHistoriesAndPersistNewRecords() = runTest {
        for (code in listOf(401, 429, 503)) {
        val f = Fixture(); f.cars = ApiResult.Error("discovery failure", code)
        val result = f.repository().load(7) as ApiResult.Success
        assertEquals(listOf(1), f.driveCalls); assertEquals(listOf(1), f.chargeCalls)
        assertEquals(2, result.data.drives.first().driveId)
        assertEquals(2, result.data.charges.first().chargeId)
        assertTrue(f.persistedDrives.any { it.driveId == 2 && it.carId == -7 })
        assertTrue(f.persistedCharges.any { it.chargeId == 2 && it.carId == -7 })
        assertNull(result.data.drivesSyncError)
        assertTrue(result.data.drivesFromRemote)
        assertEquals(0, f.carsCalls); assertEquals(1, f.identityCalls)
        assertTrue(f.diagnostics.isEmpty())
        }
    }
    @Test fun listEditDetailReturnAndOfflineUseTheSameVerifiedCostNamespace() = runTest {
        val f = Fixture(); f.legacyPending = true
        val repository = f.repository()
        val list = (repository.load(7) as ApiResult.Success).data
        val readsBeforeDetail = f.localReads
        val detail = (repository.resolveContext(7) as ApiResult.Success).data
        assertEquals(readsBeforeDetail, f.localReads) // Identity only: no history/DAO reads.
        assertEquals(list.context.localHistoryCarId, detail.context.localHistoryCarId)
        assertTrue(list.localArchiveLinkPending); assertTrue(detail.localArchiveLinkPending)
        val costs = mutableMapOf<Pair<Int, Int>, Double?>()
        val save: suspend (Int, Int, Double?) -> Unit = { car, charge, amount -> costs[car to charge] = amount }
        assertTrue(saveVerifiedHistoryChargeCost(detail, 2, 50.0, repository::isContextCurrent, save))
        val returned = (repository.load(7) as ApiResult.Success).data
        assertEquals(50.0, costs[returned.context.localHistoryCarId to 2]!!, 0.0)
        f.identity = ApiResult.Error("offline", 503)
        val offline = (repository.resolveContext(7) as ApiResult.Success).data
        assertFalse(offline.remoteAuthorized)
        assertEquals(50.0, costs[offline.context.localHistoryCarId to 2]!!, 0.0)
        f.scope = f.original.copy(accountNamespace = "account-b")
        assertFalse(saveVerifiedHistoryChargeCost(offline, 2, 99.0, repository::isContextCurrent, save))
        assertEquals(50.0, costs[offline.context.localHistoryCarId to 2]!!, 0.0)
    }
    @Test fun proofCannotBeReusedAfterOriginModeVehicleOrAccountChange() = runTest {
        for (change in listOf("origin", "mode", "account", "vehicle", "logout")) {
            val f = Fixture(); val repository = f.repository()
            val proof = (repository.resolveContext(7) as ApiResult.Success).data
            when (change) {
                "origin" -> f.scope = f.original.copy(effectiveApiOrigin = "https://other.example")
                "mode" -> f.scope = f.original.copy(source = HistoryConnectionSource.SELF_HOSTED)
                "account" -> f.scope = f.original.copy(accountNamespace = "account-b")
                "vehicle" -> f.cached = f.context.copy(localHistoryCarId = -8, stableIdentity = "other-uid")
                else -> f.scope = null
            }
            var writes = 0
            assertFalse(saveVerifiedHistoryChargeCost(proof, 2, 50.0, repository::isContextCurrent) { _, _, _ -> writes++ })
            assertEquals(0, writes)
        }
    }
    @Test fun newAuthenticatedIdentityCanAllocateOriginNamespaceWithoutLegacyMapping() = runTest {
        val f = Fixture(); f.cached = null
        val result = f.repository().load(7) as ApiResult.Success
        assertEquals(2, result.data.drives.first().driveId)
        assertEquals(-7, result.data.context.localHistoryCarId)
        assertEquals(0, f.carsCalls)
    }
    @Test fun endpoint404FallsBackOnlyToSuccessfulAuthenticatedCarsDiscovery() = runTest {
        val f = Fixture(); f.identity = ApiResult.Error("unsupported", 404)
        f.cars = ApiResult.Success(listOf(CarData(7, vehicleUid = "provider-a")))
        val result = f.repository().load(7) as ApiResult.Success
        assertTrue(result.data.drivesFromRemote)
        assertEquals(1, f.carsCalls); assertEquals(listOf(1), f.driveCalls)
    }
    @Test fun failedIdentityCannotAuthorizeHistoryEvenWithOriginScopedCache() = runTest {
        for (code in listOf(401, 403, 429, 502, 503)) {
            val f = Fixture(); f.identity = ApiResult.Error("identity failure", code)
            val result = f.repository().load(7) as ApiResult.Success
            assertEquals("history_cached", result.data.drivesSyncError)
            assertEquals(0, f.carsCalls); assertTrue(f.driveCalls.isEmpty()); assertTrue(f.chargeCalls.isEmpty())
            assertTrue(f.diagnostics.contains("history_context" to code))
        }
    }
    @Test fun invalidIdentityResponseNeverReadsRemoteHistory() = runTest {
        for (identity in listOf(HistoryContextData(2, 7, "provider-a"), HistoryContextData(1, 8, "provider-a"),
            HistoryContextData(1, 7, " "), HistoryContextData(1, 7, " provider-a"), HistoryContextData(1, 7, "x".repeat(257)))) {
            val f = Fixture(); f.identity = ApiResult.Success(identity); f.cached = null
            val result = f.repository().load(7) as ApiResult.Error
            assertEquals(ApiErrorKind.INVALID_RESPONSE, result.kind)
            assertEquals(0, f.carsCalls); assertTrue(f.driveCalls.isEmpty()); assertTrue(f.persistedDrives.isEmpty())
        }
    }
    @Test fun noPreviouslyVerifiedMappingNeverFetchesByNumericId() = runTest {
        val f = Fixture(); f.cached = null; f.identity = ApiResult.Error("unsupported", 404)
        assertTrue(f.repository().load(7) is ApiResult.Error)
        assertTrue(f.driveCalls.isEmpty()); assertTrue(f.chargeCalls.isEmpty())
    }
    @Test fun successfulDiscoveryWithMissingVehicleDoesNotRequestHistory() = runTest {
        val f = Fixture(); f.identity = ApiResult.Error("unsupported", 404); f.cars = ApiResult.Success(emptyList())
        val result = f.repository().load(7) as ApiResult.Success
        assertTrue(f.driveCalls.isEmpty()); assertTrue(f.chargeCalls.isEmpty())
        assertEquals("history_cached", result.data.drivesSyncError)
    }
    @Test fun historyOwn401And502KeepCachedWarningsAndDistinctDiagnostics() = runTest {
        for (code in listOf(401, 403, 404, 502)) {
            val f = Fixture(); f.fetchDrives = { ApiResult.Error("failure", code) }; f.fetchCharges = { ApiResult.Error("failure", code) }
            val result = f.repository().load(7) as ApiResult.Success
            assertEquals(listOf(1), result.data.drives.map { it.driveId })
            assertEquals("history_cached", result.data.drivesSyncError)
            assertFalse(result.data.drivesFromRemote)
            assertTrue(f.diagnostics.contains("drives" to code)); assertTrue(f.diagnostics.contains("charges" to code))
        }
    }
    @Test fun accountOrOriginChangeDuringPageFailsClosedWithoutPersisting() = runTest {
        for (next in listOf("account", "origin", "mode", "logout")) {
            val f = Fixture()
            f.fetchDrives = {
                f.scope = when (next) { "account" -> f.original.copy(accountNamespace = "account-b"); "origin" -> f.original.copy(effectiveApiOrigin = "https://other.example"); "mode" -> f.original.copy(source = HistoryConnectionSource.SELF_HOSTED); else -> null }
                ApiResult.Success(listOf(f.newDrive))
            }
            val result = f.repository().load(7) as ApiResult.Error
            assertEquals(HISTORY_IDENTITY_UNAVAILABLE, result.message)
            assertTrue(f.chargeCalls.isEmpty()); assertTrue(f.persistedDrives.isEmpty()); assertTrue(f.persistedCharges.isEmpty())
        }
    }
    @Test fun secondPageFailureKeepsPartialRowsAndChargesSuccessIndependent() = runTest {
        val f = Fixture()
        f.fetchDrives = { page ->
            if (page == 1) ApiResult.Success((2..51).map { f.newDrive.copy(driveId = it, startDate = java.time.Instant.parse("2026-10-04T00:00:00Z").plusSeconds(it.toLong()).toString(), endDate = java.time.Instant.parse("2026-10-04T01:00:00Z").plusSeconds(it.toLong()).toString()) })
            else ApiResult.Error("second page unavailable", 502)
        }
        val result = f.repository().load(7) as ApiResult.Success
        assertEquals(listOf(1, 2), f.driveCalls)
        assertEquals(51, result.data.drives.size)
        assertTrue(result.data.drives.any { it.driveId == 1 })
        assertEquals("history_partial", result.data.drivesSyncError)
        assertFalse(result.data.drivesFromRemote)
        assertTrue(result.data.chargesFromRemote)
        assertNull(result.data.chargesSyncError)
        assertTrue(f.persistedDrives.any { it.driveId == 51 })
        assertTrue(f.persistedCharges.any { it.chargeId == 2 })
    }
    @Test fun scopeChangeOnSecondPageDiscardsAllDownloadedPages() = runTest {
        val f = Fixture()
        f.fetchDrives = { page ->
            if (page == 1) ApiResult.Success((2..51).map { f.newDrive.copy(driveId = it, startDate = java.time.Instant.parse("2026-10-04T00:00:00Z").plusSeconds(it.toLong()).toString(), endDate = java.time.Instant.parse("2026-10-04T01:00:00Z").plusSeconds(it.toLong()).toString()) })
            else { f.scope = f.original.copy(accountNamespace = "account-b"); ApiResult.Success(listOf(f.newDrive.copy(driveId = 52))) }
        }
        val result = f.repository().load(7) as ApiResult.Error
        assertEquals(HISTORY_IDENTITY_UNAVAILABLE, result.message)
        assertEquals(listOf(1, 2), f.driveCalls)
        assertTrue(f.chargeCalls.isEmpty()); assertTrue(f.persistedDrives.isEmpty())
    }
    @Test fun scopeChangeBetweenDaoWritesStopsSecondWriteAndDoesNotReturnData() = runTest {
        val f = Fixture(); f.afterDrivePersist = { f.scope = f.original.copy(accountNamespace = "account-b") }
        val result = f.repository().load(7) as ApiResult.Error
        assertEquals(HISTORY_IDENTITY_UNAVAILABLE, result.message)
        assertTrue(f.persistedDrives.all { it.carId == -7 }); assertTrue(f.persistedCharges.isEmpty())
    }
    @Test fun cancellationSwallowedByTransportCannotPersistOrStartNextEndpoint() = runTest {
        val f = Fixture()
        f.fetchDrives = {
            currentCoroutineContext()[Job]!!.cancel()
            ApiResult.Success(listOf(f.newDrive))
        }
        val job = launch {
            try { f.repository().load(7); fail("must propagate cancellation") } catch (_: CancellationException) { }
        }
        job.join()
        assertTrue(f.persistedDrives.isEmpty()); assertTrue(f.persistedCharges.isEmpty()); assertTrue(f.chargeCalls.isEmpty())
    }
}

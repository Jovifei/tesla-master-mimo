package com.matelink.data.repository

import android.app.Application
import androidx.room.Room
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.sync.toSyncSummary
import com.matelink.data.sync.toAggregate
import com.matelink.domain.analytics.*
import com.matelink.ui.screens.drives.historyEnergy
import com.matelink.ui.screens.drives.observeScopedDriveRows
import com.matelink.ui.screens.drives.toQualifiedHistoryMetrics
import com.matelink.ui.screens.drives.optionalDetailIsCurrent
import com.matelink.ui.screens.drives.capacityMetadata
import com.matelink.data.sync.DriveDetailRequestBudget
import com.matelink.data.sync.runDriveDetailBatch
import com.matelink.data.sync.driveDetailFailureWorkerResult
import com.matelink.data.sync.finishDataSyncBatch
import com.matelink.data.sync.runHistoryDetailBatch
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.DriveDetailBatchResult
import com.matelink.data.sync.CarSyncResult
import androidx.work.ListenableWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Real isolated disk Room, scope fences, overlapping consumers and database reopening. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class EnergyDurableConsumerTest {
    @get:Rule val temp = TemporaryFolder()
    private val start = "2026-10-13T00:00:00Z"
    private val end = "2026-10-13T00:01:00Z"
    private val row = DriveData(4, startDate = start, endDate = end, source = "teslamate_archive",
        qualityState = "observed", batteryDetails = DriveBatteryDetails(80, 79),
        odometerDetails = DriveOdometerDetails(distance = 2.0))
    private fun detail() = row.asCachedDetail().copy(positions = listOf(
        DrivePosition(date = start, power = 10.0),
        DrivePosition(date = "2026-10-13T00:00:10Z", power = 10.0)))
    private fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), StatsDatabase::class.java,
        File(temp.root, "energy.db").absolutePath).allowMainThreadQueries().build()

    @Test fun correctedListReceiptRecomputesStoredSocAndExplicitUnknownRevokesIt() = runBlocking {
        val db = open()
        try {
            val dao = db.driveSummaryDao()
            dao.upsert(row.toSyncSummary(-7)!!)
            val d = detail()
            dao.mergeResolvedDetail(-7, d, d.resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD")) {}
            val corrected = row.copy(batteryDetails = DriveBatteryDetails(80, 78), startAddress = "Corrected raw address")
                .toSyncSummary(-7)!!
            dao.upsertPreservingEvidence(listOf(corrected))
            val saved = dao.get(-7, 4)!!
            val display = saved.toAnalysisDriveData()
            assertEquals(corrected.apiEvidence, HistorySummaryEvidenceCodec.sourceJson(saved.apiEvidence))
            assertEquals("Corrected raw address", saved.toRawAnalysisDriveData().startAddress)
            assertEquals(78, display.endBatteryLevel)
            assertEquals(1.568, display.historyEnergy(saved.toQualifiedHistoryMetrics()).energyKwh!!, 1e-12)
            assertEquals("model_trim_assumption", display.energyContract!!.netEnergy!!.capacityBasis)
            assertEquals(78.4, display.energyContract!!.netEnergy!!.capacityKwh!!, 1e-12)
            val shifted = row.copy(endDate = "2026-10-13T00:02:00Z").toSyncSummary(-7)!!
            assertNull(HistorySummaryEvidenceCodec.rebaseDriveRaw(saved.apiEvidence, shifted.apiEvidence))
            val foreign = row.copy(source = "local_import", batteryDetails = DriveBatteryDetails(80, 70)).toSyncSummary(-7)!!
            dao.upsertPreservingEvidence(listOf(foreign))
            assertEquals(1.568, dao.get(-7, 4)!!.energyConsumed!!, 1e-12)
            val unknown = corrected.toRawAnalysisDriveData().copy(energyContract = EnergyContract(netEnergy = EnergyMetric(
                quality = "unknown", source = row.source, startDate = start, endDate = end,
                reason = "provider_energy_unsupported"))).toSyncSummary(-7)!!
            dao.upsertPreservingEvidence(listOf(unknown))
            val revoked = dao.get(-7, 4)!!
            assertNull(revoked.energyConsumed)
            assertNull(revoked.toAnalysisDriveData().historyEnergy(revoked.toQualifiedHistoryMetrics()).energyKwh)
        } finally { db.close() }
    }

    @Test fun reopenAndListRefreshPreserveCapacityAndEstimate() = runBlocking {
        var db = open()
        val original = row.toSyncSummary(-7)!!
        try {
            val dao = db.driveSummaryDao()
            dao.upsert(original)
            val d = detail()
            val saved = dao.mergeResolvedDetail(-7, d, d.resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD")) {}
            assertEquals(0.784, saved!!.energyConsumed!!, 1e-12)
            assertEquals(original.apiEvidence, HistorySummaryEvidenceCodec.sourceJson(saved.apiEvidence))
            db.close()
            db = open()
            val fixture = HistoryFixture(db.driveSummaryDao())
            val loaded = fixture.repository().load(7) as ApiResult.Success
            val reloaded = db.driveSummaryDao().get(-7, 4)!!
            val card = loaded.data.drives.single().historyEnergy(reloaded.toQualifiedHistoryMetrics())
            assertEquals(0.784, card.energyKwh!!, 1e-12)
            assertTrue(card.isEstimated)
            assertEquals(78.4, card.evidence!!.capacityKwh!!, 1e-12)
            assertEquals("model_trim_assumption", card.evidence!!.capacityBasis)
            assertNotNull(loaded.data.verifiedReadContext)
            assertEquals("verified history UID avoids duplicate car discovery", 0, fixture.carsReads)
            assertEquals(1, fixture.historyContextReads)
            val corrected = d.copy(batteryDetails = DriveBatteryDetails(80, 78))
            val background = db.driveSummaryDao().mergeResolvedDetail(-7, corrected) {}
            assertEquals(1.568, background!!.energyConsumed!!, 1e-12)
            assertEquals(78.4, background.toAnalysisDriveData().energyContract!!.netEnergy!!.capacityKwh!!, 1e-12)
        } finally { db.close() }
    }

    @Test fun newerSummaryInterleavingPreservesRawReceipt() = runBlocking {
        val db = open()
        val release = CompletableDeferred<Unit>()
        try {
            val dao = db.driveSummaryDao()
            dao.upsert(row.toSyncSummary(-7)!!)
            val afterRead = CompletableDeferred<Unit>()
            var fences = 0
            val detailWrite = async(Dispatchers.IO) {
                dao.mergeResolvedDetail(-7, detail()) {
                    fences++
                    if (fences == 2) { afterRead.complete(Unit); release.await() }
                }
            }
            withTimeout(10_000) { afterRead.await() }
            val newer = row.copy(startAddress = "New source receipt").toSyncSummary(-7)!!
            val started = CompletableDeferred<Unit>()
            val summaryWrite = async(Dispatchers.IO) {
                started.complete(Unit)
                dao.upsertPreservingEvidence(listOf(newer))
            }
            withTimeout(10_000) { started.await() }
            assertFalse("summary write must wait for the detail transaction", summaryWrite.isCompleted)
            release.complete(Unit)
            withTimeout(10_000) { detailWrite.await(); summaryWrite.await() }
            val final = dao.get(-7, 4)!!
            assertEquals(newer.toRawAnalysisDriveData(), final.toRawAnalysisDriveData())
            assertEquals(newer.apiEvidence, HistorySummaryEvidenceCodec.sourceJson(final.apiEvidence))
            assertEquals(0.6, final.toAnalysisDriveData().netEnergyKwh!!, 1e-12)
        } finally { release.complete(Unit); db.close() }
    }

    @Test fun scopeSwitchAfterWriteRollsBack() = runBlocking {
        val db = open()
        try {
            val dao = db.driveSummaryDao()
            val before = row.toSyncSummary(-7)!!
            dao.upsert(before)
            var fences = 0
            try {
                dao.mergeResolvedDetail(-7, detail()) {
                    fences++
                    if (fences == 4) {
                        assertEquals(0.6, dao.get(-7, 4)!!.energyConsumed!!, 1e-12)
                        throw CancellationException("account changed after final suspended write")
                    }
                }
                fail("scope cancellation expected")
            } catch (_: CancellationException) { }
            assertEquals(4, fences)
            assertEquals(before, dao.get(-7, 4))
            db.close()
            val reopened = open()
            try { assertEquals(before, reopened.driveSummaryDao().get(-7, 4)) } finally { reopened.close() }
        } finally { if (db.isOpen) db.close() }
    }

    @Test fun scopeSwitchBeforeReadAfterReadOrBeforeWriteKeepsOriginalReceipt() = runBlocking {
        val db = open()
        try {
            val dao = db.driveSummaryDao()
            val before = row.toSyncSummary(-7)!!
            for (interruptAt in 1..3) {
                dao.upsert(before)
                var fences = 0
                try {
                    dao.mergeResolvedDetail(-7, detail()) {
                        if (++fences == interruptAt) throw CancellationException("scope generation changed")
                    }
                    fail("scope cancellation expected at fence $interruptAt")
                } catch (_: CancellationException) { }
                assertEquals(before, dao.get(-7, 4))
            }
        } finally { db.close() }
    }

    @Test fun observeEnrichmentStopsAtScopeChange() = runBlocking {
        val db = open()
        val emissions = Channel<List<com.matelink.data.local.entity.DriveSummary>>(Channel.UNLIMITED)
        val invalidated = CompletableDeferred<Unit>()
        var observation: Job? = null
        try {
            val dao = db.driveSummaryDao()
            dao.upsert(row.toSyncSummary(-7)!!)
            val fixture = HistoryFixture(dao)
            val repository = fixture.repository()
            val loaded = repository.load(7) as ApiResult.Success
            val proof = loaded.data.verifiedReadContext!!
            observation = launch(Dispatchers.Default) {
                observeScopedDriveRows(dao.observeAll(-7), { repository.isContextCurrent(proof) },
                    { invalidated.complete(Unit) }) { emissions.trySend(it) }
            }
            assertNull(withTimeout(10_000) { emissions.receive() }.single().toQualifiedHistoryMetrics().energyKwh)
            dao.mergeResolvedDetail(-7, detail()) { check(repository.isContextCurrent(proof)) }
            val enriched = withTimeout(10_000) {
                var snapshot = emissions.receive()
                while (snapshot.single().energyConsumed == null) snapshot = emissions.receive()
                snapshot.single()
            }
            assertEquals(0.6, enriched.toAnalysisDriveData().historyEnergy(enriched.toQualifiedHistoryMetrics()).energyKwh!!, 1e-12)
            fixture.scope = fixture.scope.copy(accountNamespace = "account-b", sessionGeneration = 1)
            dao.upsert(enriched.copy(startAddress = "another account emission"))
            withTimeout(10_000) { invalidated.await(); observation!!.join() }
            while (true) {
                val queued = emissions.tryReceive().getOrNull() ?: break
                assertNotEquals("another account emission", queued.single().startAddress)
            }
        } finally { observation?.cancelAndJoin(); emissions.close(); db.close() }
    }

    @Test fun optionalMetadataRejectsSameCarIdWithDifferentUid() = runBlocking {
        val db = open()
        try {
            val fixture = HistoryFixture(db.driveSummaryDao())
            val proof = (fixture.repository().resolveContext(7) as ApiResult.Success).data
            assertTrue(proof.matchesCar(CarData(7, vehicleUid = "vehicle-a")))
            assertFalse(proof.matchesCar(CarData(7, vehicleUid = "vehicle-b")))
            assertFalse(proof.matchesCar(CarData(8, vehicleUid = "vehicle-a")))
            val health = ApiResult.Success(BatteryHealth(currentCapacity = 90.0))
            assertNull(proof.capacityMetadata(ApiResult.Success(listOf(CarData(7, vehicleUid = "vehicle-b"))), health))
            assertEquals(90.0, proof.capacityMetadata(ApiResult.Success(listOf(CarData(7, vehicleUid = "vehicle-a"))), health)!!.reportedCapacity!!, 0.0)
        } finally { db.close() }
    }

    @Test fun delayedMetadataRefinesLatestStoredSocInsteadOfReplacingIt() = runBlocking {
        val db = open()
        try {
            val dao = db.driveSummaryDao()
            val original = detail()
            dao.upsert(row.toSyncSummary(-7)!!)
            dao.mergeResolvedDetail(-7, original) {}
            val delayed = CompletableDeferred<ResolvedDriveEnergy>()
            val metadata = async {
                dao.refineResolvedCapacity(-7, original, delayed.await()) {}
            }
            val corrected = original.copy(batteryDetails = DriveBatteryDetails(80, 78))
            dao.mergeResolvedDetail(-7, corrected) {}
            delayed.complete(original.resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD"))
            val final = withTimeout(10_000) { metadata.await() }!!
            assertEquals(78, final.toAnalysisDriveData().endBatteryLevel)
            assertEquals(1.568, final.energyConsumed!!, 1e-12)
            assertEquals(78.4, final.toAnalysisDriveData().energyContract!!.netEnergy!!.capacityKwh!!, 1e-12)
            assertEquals(row.toSyncSummary(-7)!!.apiEvidence, HistorySummaryEvidenceCodec.sourceJson(final.apiEvidence))
            val unknown = original.copy(energyContract = EnergyContract(netEnergy = EnergyMetric(
                method = "soc_capacity_estimate", quality = "unknown", reason = "provider_energy_unsupported")))
            dao.mergeResolvedDetail(-7, unknown) {}
            assertNull(dao.refineResolvedCapacity(-7, original,
                original.resolveDriveEnergy(model = "Y", trimBadging = "Long Range AWD")) {}!!.energyConsumed)
        } finally { db.close() }
    }

    @Test fun changedDetailProofDuringScopeAwaitRejectsOldPublication() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var sameLoad = true
        var published = false
        val oldMetadata = async {
            if (optionalDetailIsCurrent({ sameLoad }, { entered.complete(Unit); released.await(); true })) published = true
        }
        entered.await()
        sameLoad = false
        released.complete(Unit)
        oldMetadata.await()
        assertFalse(published)
    }

    @Test fun boundedWorkerBatchesResumePersistedPendingRowsAndStopOnFirstFailure() = runBlocking {
        var db = open()
        var completed = 0
        val calls = mutableListOf<Int>()
        suspend fun batch(budget: DriveDetailRequestBudget, failure: ApiResult.Error? = null): com.matelink.data.sync.DriveDetailBatchResult {
            val dao = db.driveSummaryDao()
            return runDriveDetailBatch(budget,
                selectIds = { dao.getUnprocessedDriveBatchIds(-7, com.matelink.data.local.entity.SchemaVersion.DRIVE_ENERGY, it) },
                fetch = { id -> calls += id; failure ?: ApiResult.Success(detail().copy(driveId = id)) },
                persist = { detail ->
                    val saved = dao.mergeResolvedDetail(-7, detail) {}
                    if (saved == null) false else { db.aggregateDao().upsertDriveAggregate(detail.toAggregate(-7, 1L)); true }
                },
                pendingCount = { dao.countUnprocessedDrives(-7, com.matelink.data.local.entity.SchemaVersion.DRIVE_ENERGY) },
                ensureCurrent = {}, markComplete = { completed++ })
        }
        try {
            db.driveSummaryDao().upsertAll((1..351).map { row.copy(driveId = it).toSyncSummary(-7)!! })
            val firstBudget = DriveDetailRequestBudget()
            val first = batch(firstBudget)
            assertEquals(20, calls.size)
            assertEquals(331, first.pending)
            assertFalse(first.complete)
            assertEquals(0, completed)
            assertEquals(0, firstBudget.remaining)
            assertEquals(331, batch(firstBudget).pending)
            assertEquals(20, calls.size)
            db.close(); db = open()
            val limited = batch(DriveDetailRequestBudget(), ApiResult.Error("limited", 429))
            assertEquals(21, calls.size)
            assertEquals(331, limited.pending)
            assertEquals(ListenableWorker.Result.retry().javaClass, driveDetailFailureWorkerResult(limited.failure!!).javaClass)
            val auth = batch(DriveDetailRequestBudget(), ApiResult.Error("auth", 401))
            assertEquals(22, calls.size)
            assertEquals(ListenableWorker.Result.failure().javaClass, driveDetailFailureWorkerResult(auth.failure!!).javaClass)
            var continued = batch(DriveDetailRequestBudget())
            var batches = 2
            while (!continued.complete) {
                assertEquals(0, completed)
                assertTrue(batches < 20)
                continued = batch(DriveDetailRequestBudget())
                batches++
            }
            assertEquals(18, batches)
            assertEquals(353, calls.size) // 351 committed details plus 2 stopped attempts.
            assertEquals(1, completed)
            assertEquals((1..351).toList(), calls.filterIndexed { index, _ -> index != 20 && index != 21 })
        } finally { db.close() }
    }

    @Test fun normalPendingBatchAwaitsContinuationAndDoesNotUseErrorRetry() = runBlocking {
        val scheduling = CompletableDeferred<Unit>()
        val persisted = CompletableDeferred<Unit>()
        val result = async {
            finishDataSyncBatch(331) { scheduling.complete(Unit); persisted.await() }
        }
        scheduling.await()
        assertFalse(result.isCompleted)
        persisted.complete(Unit)
        val finished = result.await() as ListenableWorker.Result.Success
        assertTrue(finished.outputData.getBoolean("history_pending", false))
        assertEquals(331, finished.outputData.getInt("pending_drives", 0))
        var scheduled = false
        val complete = finishDataSyncBatch(0) { scheduled = true } as ListenableWorker.Result.Success
        assertFalse(scheduled)
        assertFalse(complete.outputData.getBoolean("history_pending", false))
    }

    @Test fun deferredDriveBatchStillProcessesNewChargeButApiFailureStopsFurtherRequests() = runBlocking {
        val fixture = HistoryResourceFixture(File(temp.root, "fair-resource.db"))
        try {
            fixture.initialize(); fixture.seed(1, 351, 1)
            val result = runHistoryDetailBatch(listOf(1)) { car, budget -> fixture.repo.syncDetailsOnly(car, budget) }
            assertTrue(result is CarSyncResult.Deferred)
            assertEquals(20, fixture.calls.size)
            assertEquals(1, fixture.calls.count { it.contains("/charges/") })
            assertTrue(fixture.db.chargeSummaryDao().getUnprocessedChargeBatchIds(fixture.localId(1),
                com.matelink.data.local.entity.SchemaVersion.CURRENT, 20).isEmpty())
            fixture.calls.clear(); fixture.status = { 429 }
            val stopped = runHistoryDetailBatch(listOf(1)) { car, budget -> fixture.repo.syncDetailsOnly(car, budget) }
            assertTrue(stopped is CarSyncResult.Failed)
            assertEquals(429, (stopped as CarSyncResult.Failed).error.code)
            assertEquals(1, fixture.calls.size)
        } finally { fixture.close() }
    }

    @Test fun priorComputationVersionIsReevaluatedOnceWithoutSchemaMigration() = runBlocking {
        val db = open()
        try {
            val dao = db.driveSummaryDao()
            val d = detail()
            dao.upsert(row.toSyncSummary(-7)!!)
            db.chargeSummaryDao().upsert(ChargeData(9, startDate = start, endDate = end,
                source = "teslamate_archive", qualityState = "observed").toSyncSummary(-7)!!)
            db.aggregateDao().upsertChargeAggregate(ChargeDetail(9, startDate = start, endDate = end).toAggregate(-7, 1L))
            db.aggregateDao().upsertDriveAggregate(d.toAggregate(-7, 1L).copy(schemaVersion = 5))
            val version = com.matelink.data.local.entity.SchemaVersion.DRIVE_ENERGY
            assertEquals(7, version)
            assertEquals(7, com.matelink.data.local.entity.SchemaVersion.CURRENT)
            assertTrue(db.chargeSummaryDao().getUnprocessedChargeIds(-7, com.matelink.data.local.entity.SchemaVersion.CURRENT).isEmpty())
            assertEquals(listOf(4), dao.getUnprocessedDriveIds(-7, version))
            val databaseVersion = db.openHelper.readableDatabase.version
            var computations = 0
            suspend fun reprocessPending() {
                for (id in dao.getUnprocessedDriveIds(-7, version)) {
                    assertEquals(4, id)
                    computations++
                    dao.mergeResolvedDetail(-7, d) {}
                    db.aggregateDao().upsertDriveAggregate(d.toAggregate(-7, 2L))
                }
            }
            reprocessPending()
            assertTrue(dao.getUnprocessedDriveIds(-7, version).isEmpty())
            val storedOnce = dao.get(-7, 4)!!
            reprocessPending()
            assertEquals(1, computations)
            assertEquals(storedOnce, dao.get(-7, 4))
            assertEquals(databaseVersion, db.openHelper.readableDatabase.version)
            assertEquals(0.6, dao.get(-7, 4)!!.energyConsumed!!, 1e-12)
        } finally { db.close() }
    }

    private inner class HistoryFixture(private val dao: DriveSummaryDao) {
        var scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "account-a", "https://api.example")
        private val context = VehicleContext(7, cloudOriginVehicleStableIdentity("account-a", "https://api.example", "vehicle-a"),
            -7, HistoryConnectionSource.CLOUD, "cloud")
        var carsReads = 0
        var historyContextReads = 0
        fun repository() = UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { scope }, getCars = { carsReads++; ApiResult.Success(listOf(CarData(7, vehicleUid = "vehicle-a"))) },
            getHistoryContext = { historyContextReads++; ApiResult.Success(HistoryContextData(1, 7, "vehicle-a")) },
            resolveCar = { _, _ -> context }, cachedContext = { _, expected -> if (expected == scope) context else null },
            localDrives = { dao.getAllChronological(it) }, localCharges = { emptyList() },
            getDrives = { _, _, _, page -> ApiResult.Success(if (page == 1) listOf(row) else emptyList()) },
            getCharges = { _, _, _, _ -> ApiResult.Success(emptyList()) },
            persistDrives = { dao.upsertPreservingEvidence(it) }, persistCharges = {}
        ))
    }
}

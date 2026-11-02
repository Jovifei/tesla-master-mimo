package com.matelink.ui.screens.drives

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.OpenMeteoApi
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.DrivePosition
import com.matelink.data.repository.HistoryDetailRepository
import com.matelink.data.repository.HistoryDetailCodec
import com.matelink.data.repository.HistoryDetailIdentity
import com.matelink.data.repository.HistoryDetailStorageLimits
import com.matelink.data.repository.HistoryDetailWriteResult
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.DriveEnergyAttemptKey
import com.matelink.data.repository.DriveEnergyRequestBudget
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.repository.WeatherRepository
import com.matelink.data.repository.enrichDriveHistoryEnergy
import com.matelink.data.repository.persistVerifiedDriveDetail
import com.matelink.data.repository.verifiedDetailIdentity
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.TripDetector
import com.matelink.domain.TripRepository
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
import com.matelink.domain.analytics.asCachedDetail
import com.matelink.domain.analytics.resolveDriveEnergy
import com.matelink.domain.analytics.toAnalysisDriveData
import java.io.File
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class DriveHistoryAutoEnergyConsumerTest {
    @get:Rule val temp = TemporaryFolder()
    private val start = Instant.parse("2026-10-30T12:00:00Z")

    private fun drive(id: Int, source: String = "teslamate_archive"): DriveData {
        val begin = start.plusSeconds(id.toLong() * 60)
        return DriveData(id, startDate = begin.toString(), endDate = begin.plusSeconds(20).toString(),
            durationMin = 1, source = source, sourceInstanceId = "archive-a", sourceId = "record-$id",
            qualityState = "observed", odometerDetails = DriveOdometerDetails(distance = 2.0), lengthUnit = "km")
    }

    private fun detail(row: DriveData): DriveDetail = DriveDetail(row.driveId, startDate = row.startDate,
        endDate = row.endDate, durationMin = 1, source = row.source, sourceInstanceId = row.sourceInstanceId,
        sourceId = row.sourceId, odometerDetails = row.odometerDetails, lengthUnit = "km",
        positions = listOf(DrivePosition(date = row.startDate, power = 36.0),
            DrivePosition(date = row.endDate, power = 36.0)))

    private fun response(row: DriveData, points: String) = """
        {"data":{"drive":{"drive_id":${row.driveId},"start_date":"${row.startDate}","end_date":"${row.endDate}",
        "duration_min":1,"source":"${row.source}","source_instance_id":"${row.sourceInstanceId}",
        "source_id":"${row.sourceId}","quality_state":"observed",
        "odometer_details":{"odometer_distance":2.0},"drive_details":$points}}}
    """.trimIndent()

    private fun createPage(f: HistoryResourceFixture) = DrivesViewModel(f.context, f.apiRepo,
        UnifiedHistoryRepository(f.apiRepo, f.vehicleContexts, f.db.driveSummaryDao(), f.db.chargeSummaryDao()),
        f.geocodingRepository, f.settings, f.db.driveSummaryDao(), f.vehicleContexts,
        Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZoneId.of("Asia/Shanghai")), SavedStateHandle(),
        f.db, HistoryDetailRepository(f.db.historyDetailDao()))

    private suspend fun awaitLatestHistoryLoad(page: DrivesViewModel) {
        withTimeout(20_000) {
            val loader = DrivesViewModel::class.java.getDeclaredField("latestLoad").apply { isAccessible = true }.get(page)
            val loadField = loader.javaClass.getDeclaredField("job").apply { isAccessible = true }
            while (loadField.get(loader) == null) delay(10)
            (loadField.get(loader) as Job).join()
        }
    }

    private suspend fun currentEnergyJob(page: DrivesViewModel): Job = withTimeout(10_000) {
        val field = DrivesViewModel::class.java.getDeclaredField("energyEnrichment").apply { isAccessible = true }
        while (field.get(page) == null) delay(10)
        field.get(page) as Job
    }

    @Test fun automaticSummaryLeavesChargeBudgetAndExplicitDetailStillLoads() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "automatic-summary-only.db"))
        var detailPage: DriveDetailViewModel? = null
        try {
            f.initialize()
            val row = drive(64).copy(startAddress = "original-start", endAddress = "original-end", speedMax = 21)
            val car = f.localId(1)
            val original = row.toSyncSummary(car)!!
            f.db.driveSummaryDao().upsert(original)
            val originalReceipt = HistorySummaryEvidenceCodec.sourceJson(original.apiEvidence)
            assertNotNull(originalReceipt)

            val charge = ChargeDetail(900, startDate = row.startDate, endDate = row.endDate,
                source = row.source, sourceInstanceId = row.sourceInstanceId, sourceId = "charge-900",
                chargePoints = listOf(ChargePoint(date = row.startDate, batteryLevel = 45)))
            val codec = HistoryDetailCodec()
            val defaultLimits = HistoryDetailStorageLimits()
            val chargeBytes = codec.encodeCharge(charge, defaultLimits).encodedByteCount
            val driveBytes = codec.encodeDrive(detail(row), defaultLimits).encodedByteCount
            val pointStore = HistoryDetailRepository(f.db.historyDetailDao(), codec,
                HistoryDetailStorageLimits(maxTotalBytes = chargeBytes + driveBytes - 1), System::currentTimeMillis)
            val networkDetail = response(row,
                """[{"date":"${row.startDate}","power":36},{"date":"${row.endDate}","power":36}]""")
                .replace("\"duration_min\":1,", "\"duration_min\":1,\"start_address\":\"new-detail-address\",\"speed_max\":99,")
            val networkList = """{"data":{"drives":[{"drive_id":${row.driveId},"start_date":"${row.startDate}","end_date":"${row.endDate}",
                "duration_min":1,"source":"${row.source}","source_instance_id":"${row.sourceInstanceId}","source_id":"${row.sourceId}",
                "quality_state":"observed","start_address":"original-start","end_address":"original-end","speed_max":21,
                "odometer_details":{"odometer_distance":2.0}}]}}""".trimIndent()
            f.detailResponse = { path -> when {
                path.endsWith("/drives/64") -> networkDetail
                path.endsWith("/drives") -> networkList
                else -> null
            } }
            var history = UnifiedHistoryRepository(f.apiRepo, f.vehicleContexts, f.db.driveSummaryDao(), f.db.chargeSummaryDao())
            val proof = (history.resolveContext(1) as ApiResult.Success).data
            assertTrue(proof.remoteAuthorized)
            enrichDriveHistoryEnergy(proof, listOf(original), f.apiRepo, f.db, pointStore, mutableMapOf()) {
                if (!history.isContextCurrent(proof)) throw CancellationException("scope changed")
            }

            val enriched = requireNotNull(f.db.driveSummaryDao().get(car, row.driveId))
            val projected = enriched.toAnalysisDriveData()
            assertEquals(0.2, projected.netEnergyKwh!!, 1e-9)
            val metric = requireNotNull(projected.energyContract?.netEnergy)
            assertEquals("estimated", metric.quality)
            assertEquals("drive_power_integral", metric.method)
            assertEquals(originalReceipt, HistorySummaryEvidenceCodec.sourceJson(enriched.apiEvidence))
            assertEquals(original.copy(energyConsumed = enriched.energyConsumed, efficiency = enriched.efficiency,
                energySource = enriched.energySource, energyCoverageSeconds = enriched.energyCoverageSeconds,
                energyCoverageRatio = enriched.energyCoverageRatio, apiEvidence = enriched.apiEvidence), enriched)
            assertEquals(0L, f.db.historyDetailDao().storedByteCount())
            val driveIdentity = requireNotNull(enriched.verifiedDetailIdentity(proof.context))
            assertNull(pointStore.readDrive(driveIdentity).detail)

            val chargeIdentity = HistoryDetailIdentity.create(proof.context, HistoryDetailIdentity.CHARGE,
                charge.chargeId, charge.source, charge.sourceInstanceId, charge.startDate, charge.endDate, charge.sourceId)
            assertTrue(pointStore.saveCharge(chargeIdentity, charge) is HistoryDetailWriteResult.Stored)
            assertEquals(chargeBytes, f.db.historyDetailDao().storedByteCount())

            f.reopen()
            val reopened = requireNotNull(f.db.driveSummaryDao().get(car, row.driveId))
            assertEquals(0.2, reopened.toAnalysisDriveData().netEnergyKwh!!, 1e-9)
            assertEquals(originalReceipt, HistorySummaryEvidenceCodec.sourceJson(reopened.apiEvidence))
            assertEquals(0.2, reopened.toAnalysisDriveData().asCachedDetail().resolveDriveEnergy().estimate.energyKwh!!, 1e-9)
            history = UnifiedHistoryRepository(f.apiRepo, f.vehicleContexts, f.db.driveSummaryDao(), f.db.chargeSummaryDao())
            val refreshed = (history.load(1) as ApiResult.Success).data.drives.single { it.driveId == row.driveId }
            assertEquals(0.2, refreshed.netEnergyKwh!!, 1e-9)
            assertEquals("estimated", refreshed.energyContract!!.netEnergy!!.quality)
            val refreshedSummary = requireNotNull(f.db.driveSummaryDao().get(car, row.driveId))
            val originalRaw = requireNotNull(HistorySummaryEvidenceCodec.decodeDrive(requireNotNull(original.apiEvidence)))
            val refreshedRaw = requireNotNull(HistorySummaryEvidenceCodec.decodeDrive(requireNotNull(refreshedSummary.apiEvidence)))
            // The next real response records its own (here unspecified) temperature unit.
            assertEquals(originalRaw.copy(temperatureUnit = ""), refreshedRaw)
            assertEquals("", refreshedRaw.temperatureUnit)
            assertNull(refreshedRaw.energyContract)
            assertNull(refreshedRaw.energyConsumedNet)

            val noWeather = Proxy.newProxyInstance(OpenMeteoApi::class.java.classLoader,
                arrayOf(OpenMeteoApi::class.java)) { _, _, _ -> error("Synthetic points have no location") } as OpenMeteoApi
            val trips = TripRepository(f.db.driveSummaryDao(), f.db.chargeSummaryDao(),
                f.db.aggregateDao(), f.db.savedTripDao(), TripDetector())
            val explicitStore = HistoryDetailRepository(f.db.historyDetailDao())
            val page = DriveDetailViewModel(f.apiRepo, f.db.driveSummaryDao(), history,
                WeatherRepository(noWeather), trips, f.geocodingRepository, f.db, explicitStore, f.vehicleContexts)
            detailPage = page
            page.loadDriveDetail(1, row.driveId)
            val visible = withTimeout(20_000) {
                page.uiState.first { !it.isLoading && !it.isRefreshing && it.driveDetail?.positions?.size == 2 }
            }
            assertEquals(2, visible.driveDetail!!.positions!!.size)
            assertEquals(0.2, visible.stats!!.energy.energyKwh!!, 1e-9)
            val reopenedContext = requireNotNull(f.vehicleContexts.cachedContextForRemote(1))
            val latest = requireNotNull(f.db.driveSummaryDao().get(car, row.driveId))
            assertEquals(2, explicitStore.readDrive(requireNotNull(latest.verifiedDetailIdentity(reopenedContext))).detail!!.positions!!.size)
            assertTrue(f.calls.count { it.endsWith("/drives/64") } >= 2)
            assertNotNull(explicitStore.readCharge(chargeIdentity).detail)
        } finally {
            detailPage?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun listAppearsBeforeDetailAndAutomaticallyPublishesEstimatedPower() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "automatic-power.db"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var page: DrivesViewModel? = null
        try {
            f.initialize()
            val row = drive(64)
            f.db.driveSummaryDao().upsert(row.toSyncSummary(f.localId(1))!!)
            f.onRequest = { if (it.endsWith("/drives/64")) { entered.complete(Unit); release.await() } }
            f.detailResponse = { path -> if (path.endsWith("/drives/64")) response(row,
                """[{"date":"${row.startDate}","power":36},{"date":"${row.endDate}","power":36}]""") else null }
            val vm = createPage(f)
            page = vm
            vm.setCarId(1)
            withTimeout(10_000) { entered.await() }
            assertEquals(64, vm.uiState.value.drives.single().driveId)
            assertNull(vm.uiState.value.driveMetrics[64]?.energyKwh)
            release.complete(Unit)
            val state = withTimeout(20_000) { vm.uiState.first { it.driveMetrics[64]?.energyKwh != null } }
            val presentation = state.drives.single().historyEnergy(state.driveMetrics[64])
            assertEquals(0.2, presentation.energyKwh!!, 1e-9)
            assertEquals(100.0, presentation.efficiencyWhKm!!, 1e-9)
            assertTrue(presentation.isEstimated)
            assertTrue(driveHistoryEfficiencyText(presentation)!!.startsWith("≈"))
            assertEquals(1, f.calls.count { it.endsWith("/drives/64") })
        } finally {
            release.complete(Unit)
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun cachedSameIdentityPointsAreRecomputedWithoutNetwork() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "cached-power.db"))
        var page: DrivesViewModel? = null
        try {
            f.initialize()
            val row = drive(65)
            val summary = row.toSyncSummary(f.localId(1))!!
            f.db.driveSummaryDao().upsert(summary)
            val identity = summary.verifiedDetailIdentity(f.vehicleContexts.cachedContextForRemote(1)!!)!!
            HistoryDetailRepository(f.db.historyDetailDao()).saveDrive(identity, detail(row))
            val vm = createPage(f)
            page = vm
            vm.setCarId(1)
            val state = withTimeout(20_000) { vm.uiState.first { it.driveMetrics[65]?.energyKwh != null } }
            assertTrue(state.drives.single().historyEnergy(state.driveMetrics[65]).isEstimated)
            assertTrue(f.calls.none { it.endsWith("/drives/65") })
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun rejectedPointWriteRollsBackFailureMarkerWhenScopeChanges() = runBlocking {
        val f = HistoryResourceFixture(File(temp.root, "failure-marker-scope.db"))
        try {
            f.initialize()
            val row = drive(65)
            val car = f.localId(1)
            val summary = row.toSyncSummary(car)!!
            f.db.driveSummaryDao().upsert(summary)
            val context = f.vehicleContexts.cachedContextForRemote(1)!!
            val identity = summary.verifiedDetailIdentity(context)!!
            val restricted = HistoryDetailRepository(f.db.historyDetailDao(), HistoryDetailCodec(),
                HistoryDetailStorageLimits(maxPointsPerReceipt = 1), System::currentTimeMillis)
            try {
                persistVerifiedDriveDetail(f.db, restricted, context, detail(row)) {
                    if (f.db.historyDetailDao().latestAny(identity.identityKey) != null)
                        throw CancellationException("scope changed after failure marker")
                }
                fail("late scope change must roll back the failure marker")
            } catch (_: CancellationException) { }
            assertNull(f.db.historyDetailDao().latestAny(identity.identityKey))
            assertEquals(summary.apiEvidence, f.db.driveSummaryDao().get(car, 65)!!.apiEvidence)
        } finally { f.close() }
    }

    @Test fun requestBudgetIsAtomicAndStopsAfterFailure() = runBlocking {
        val budget = DriveEnergyRequestBudget()
        val admitted = (1..100).map { async(Dispatchers.Default) { budget.tryAcquire() } }.awaitAll()
        assertEquals(20, admitted.count { it })
        val stopped = DriveEnergyRequestBudget()
        assertTrue(stopped.tryAcquire())
        stopped.stop()
        assertFalse(stopped.tryAcquire())
    }

    @Test fun queuedRealRoomRequestsShareBudgetAndCachedPointsRemainFree() = runBlocking {
        val f = HistoryResourceFixture(File(temp.root, "queued-energy-budget.db"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val firstGate = AtomicBoolean(true)
        try {
            f.initialize()
            val car = f.localId(1)
            val rows = (1..25).map { drive(it) }
            val summaries = rows.map { it.toSyncSummary(car)!! }
            val cachedRow = drive(26)
            val cachedSummary = cachedRow.toSyncSummary(car)!!
            f.db.driveSummaryDao().upsertAll(summaries + cachedSummary)
            val context = requireNotNull(f.vehicleContexts.cachedContextForRemote(1))
            val pointStore = HistoryDetailRepository(f.db.historyDetailDao())
            val cachedIdentity = requireNotNull(cachedSummary.verifiedDetailIdentity(context))
            assertTrue(pointStore.saveDrive(cachedIdentity, detail(cachedRow)) is HistoryDetailWriteResult.Stored)
            f.detailResponse = { path -> rows.firstOrNull { path.endsWith("/drives/${it.driveId}") }
                ?.let { response(it, "[]") } }
            f.onRequest = { path ->
                if (path.contains("/drives/") && firstGate.compareAndSet(true, false)) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val history = UnifiedHistoryRepository(f.apiRepo, f.vehicleContexts, f.db.driveSummaryDao(), f.db.chargeSummaryDao())
            val proof = (history.resolveContext(1) as ApiResult.Success).data
            assertTrue(proof.remoteAuthorized)
            val attempted = mutableMapOf<DriveEnergyAttemptKey, Any>()
            val budget = DriveEnergyRequestBudget()
            suspend fun runBatch(items: List<com.matelink.data.local.entity.DriveSummary>, current: DriveEnergyRequestBudget) {
                enrichDriveHistoryEnergy(proof, items, f.apiRepo, f.db, pointStore, attempted, current) {
                    if (!history.isContextCurrent(proof)) throw CancellationException("scope changed")
                }
            }
            val first = async { runBatch(summaries, budget) }
            withTimeout(20_000) { entered.await() }
            val queued = async { first.await(); runBatch(summaries, budget) }
            release.complete(Unit)
            withTimeout(30_000) { first.await(); queued.await() }
            val firstCalls = f.calls.filter { it.contains("/drives/") }
            assertEquals(20, firstCalls.size)
            assertEquals(20, firstCalls.distinct().size)

            runBatch(listOf(cachedSummary), budget)
            assertEquals(0.2, f.db.driveSummaryDao().get(car, cachedRow.driveId)!!.toAnalysisDriveData().netEnergyKwh!!, 1e-9)
            assertEquals(20, f.calls.count { it.contains("/drives/") })

            runBatch(summaries, DriveEnergyRequestBudget())
            val foregroundCalls = f.calls.filter { it.contains("/drives/") }
            assertEquals(25, foregroundCalls.size)
            assertEquals(25, foregroundCalls.distinct().size)
            synchronized(attempted) { attempted.clear() }
            runBatch(summaries, DriveEnergyRequestBudget())
            assertEquals(2, f.calls.count { it == firstCalls.first() })
        } finally {
            release.complete(Unit)
            f.close()
        }
    }

    @Test fun newSameReceiptCachedDetailIsUsedWithoutRepeatingNetwork() = runBlocking {
        val f = HistoryResourceFixture(File(temp.root, "other-page-saved-power.db"))
        try {
            f.initialize()
            val car = f.localId(1)
            val row = drive(1)
            val summary = row.toSyncSummary(car)!!
            f.db.driveSummaryDao().upsert(summary)
            f.detailResponse = { path -> if (path.endsWith("/drives/1")) response(row, "[]") else null }
            val history = UnifiedHistoryRepository(f.apiRepo, f.vehicleContexts, f.db.driveSummaryDao(), f.db.chargeSummaryDao())
            val proof = (history.resolveContext(1) as ApiResult.Success).data
            val store = HistoryDetailRepository(f.db.historyDetailDao())
            val attempted = mutableMapOf<DriveEnergyAttemptKey, Any>()
            suspend fun runBatch() {
                enrichDriveHistoryEnergy(proof, listOf(summary), f.apiRepo, f.db, store, attempted,
                    DriveEnergyRequestBudget()) {
                    if (!history.isContextCurrent(proof)) throw CancellationException("scope changed")
                }
            }
            runBatch()
            assertEquals(1, f.calls.count { it.endsWith("/drives/1") })
            assertNull(f.db.driveSummaryDao().get(car, row.driveId)!!.toAnalysisDriveData().netEnergyKwh)
            assertEquals(1, synchronized(attempted) { attempted.size })

            val foreign = detail(row).copy(sourceInstanceId = "other-instance")
            val foreignKey = HistoryDetailIdentity.create(proof.context, HistoryDetailIdentity.DRIVE,
                foreign.driveId, foreign.source, foreign.sourceInstanceId, foreign.startDate, foreign.endDate, foreign.sourceId)
            assertTrue(store.saveDrive(foreignKey, foreign) is HistoryDetailWriteResult.Stored)
            runBatch()
            assertEquals(1, f.calls.count { it.endsWith("/drives/1") })
            assertNull(f.db.driveSummaryDao().get(car, row.driveId)!!.toAnalysisDriveData().netEnergyKwh)

            val sameKey = requireNotNull(summary.verifiedDetailIdentity(proof.context))
            assertTrue(store.saveDrive(sameKey, detail(row)) is HistoryDetailWriteResult.Stored)
            runBatch()
            val enriched = requireNotNull(f.db.driveSummaryDao().get(car, row.driveId))
            assertEquals(0.2, enriched.toAnalysisDriveData().netEnergyKwh!!, 1e-9)
            assertEquals("estimated", enriched.toAnalysisDriveData().energyContract!!.netEnergy!!.quality)
            assertEquals(HistorySummaryEvidenceCodec.sourceJson(summary.apiEvidence),
                HistorySummaryEvidenceCodec.sourceJson(enriched.apiEvidence))
            assertEquals(1, f.calls.count { it.endsWith("/drives/1") })
        } finally { f.close() }
    }

    @Test fun networkFailureStopsLaterQueuedBatchesInTheSameCycle() = runBlocking {
        val f = HistoryResourceFixture(File(temp.root, "failed-energy-budget.db"))
        try {
            f.initialize()
            val car = f.localId(1)
            val rows = (1..3).map { drive(it).toSyncSummary(car)!! }
            f.db.driveSummaryDao().upsertAll(rows)
            f.status = { if (it.contains("/drives/")) 503 else 200 }
            val history = UnifiedHistoryRepository(f.apiRepo, f.vehicleContexts, f.db.driveSummaryDao(), f.db.chargeSummaryDao())
            val proof = (history.resolveContext(1) as ApiResult.Success).data
            val attempted = mutableMapOf<DriveEnergyAttemptKey, Any>()
            val budget = DriveEnergyRequestBudget()
            repeat(2) {
                enrichDriveHistoryEnergy(proof, rows, f.apiRepo, f.db,
                    HistoryDetailRepository(f.db.historyDetailDao()), attempted, budget) {
                    if (!history.isContextCurrent(proof)) throw CancellationException("scope changed")
                }
            }
            assertEquals(1, f.calls.count { it.contains("/drives/") })
        } finally { f.close() }
    }

    @Test fun cancelledSentRequestStillConsumesThisCyclesBudget() = runBlocking {
        val f = HistoryResourceFixture(File(temp.root, "cancelled-energy-budget.db"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val firstGate = AtomicBoolean(true)
        try {
            f.initialize()
            val car = f.localId(1)
            val row = drive(1)
            val summary = row.toSyncSummary(car)!!
            f.db.driveSummaryDao().upsert(summary)
            f.detailResponse = { path -> if (path.endsWith("/drives/1")) response(row, "[]") else null }
            f.onRequest = { path ->
                if (path.endsWith("/drives/1") && firstGate.compareAndSet(true, false)) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val history = UnifiedHistoryRepository(f.apiRepo, f.vehicleContexts, f.db.driveSummaryDao(), f.db.chargeSummaryDao())
            val proof = (history.resolveContext(1) as ApiResult.Success).data
            val attempted = mutableMapOf<DriveEnergyAttemptKey, Any>()
            val budget = DriveEnergyRequestBudget(1)
            suspend fun runBatch(current: DriveEnergyRequestBudget) {
                enrichDriveHistoryEnergy(proof, listOf(summary), f.apiRepo, f.db,
                    HistoryDetailRepository(f.db.historyDetailDao()), attempted, current) {
                    if (!history.isContextCurrent(proof)) throw CancellationException("scope changed")
                }
            }
            val first = async { runBatch(budget) }
            withTimeout(20_000) { entered.await() }
            first.cancel()
            release.complete(Unit)
            first.cancelAndJoin()
            runBatch(budget)
            assertEquals(1, f.calls.count { it.endsWith("/drives/1") })
            runBatch(DriveEnergyRequestBudget(1))
            assertEquals(2, f.calls.count { it.endsWith("/drives/1") })
        } finally {
            release.complete(Unit)
            f.close()
        }
    }

    @Test fun snapshotHasTwentyRequestLimitAndNoPowerIsNotRetriedByForegroundReload() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "bounded-power.db"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val firstGate = AtomicBoolean(true)
        val detailCalls = Channel<String>(Channel.UNLIMITED)
        var page: DrivesViewModel? = null
        try {
            f.initialize()
            val car = f.localId(1)
            val rows = (1..25).map { drive(it) }
            f.db.driveSummaryDao().upsertAll(rows.map { it.toSyncSummary(car)!! })
            f.detailResponse = { path -> rows.firstOrNull { path.endsWith("/drives/${it.driveId}") }
                ?.let { response(it, "[]") } }
            f.onRequest = { path ->
                if (path.contains("/drives/")) {
                    if (firstGate.compareAndSet(true, false)) {
                        entered.complete(Unit)
                        release.await()
                    }
                    detailCalls.send(path)
                }
            }
            val vm = createPage(f)
            page = vm
            vm.setCarId(1)
            withTimeout(20_000) { entered.await() }
            awaitLatestHistoryLoad(vm)
            release.complete(Unit)
            val firstRequests = withTimeout(30_000) { List(20) { detailCalls.receive() } }
            currentEnergyJob(vm).join()
            val first = f.calls.filter { it.contains("/drives/") }
            assertEquals(20, first.size)
            assertEquals(20, firstRequests.distinct().size)
            assertTrue(rows.all { f.db.driveSummaryDao().get(car, it.driveId)!!.toAnalysisDriveData().netEnergyKwh == null })
            vm.refreshForForeground()
            awaitLatestHistoryLoad(vm)
            withTimeout(30_000) { repeat(5) { detailCalls.receive() } }
            currentEnergyJob(vm).join()
            val second = f.calls.filter { it.contains("/drives/") }
            assertEquals(25, second.size)
            assertEquals(25, second.distinct().size)
            vm.refresh()
            awaitLatestHistoryLoad(vm)
            withTimeout(30_000) { repeat(20) { detailCalls.receive() } }
            currentEnergyJob(vm).join()
            assertEquals(2, f.calls.count { it == first.first() })
        } finally {
            release.complete(Unit)
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            detailCalls.close()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun firstNetworkErrorStopsAndFleetUnknownIsNeverAutomaticallyEstimated() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "power-first-error.db"))
        val entered = CompletableDeferred<Unit>()
        var page: DrivesViewModel? = null
        try {
            f.initialize()
            val car = f.localId(1)
            val archive = listOf(drive(1), drive(2), drive(3))
            val fleet = drive(4, "telemetry_mqtt")
            f.db.driveSummaryDao().upsertAll((archive + fleet).map { it.toSyncSummary(car)!! })
            f.status = { if (it.contains("/drives/")) 503 else 200 }
            f.onRequest = { if (it.contains("/drives/")) entered.complete(Unit) }
            val vm = createPage(f)
            page = vm
            vm.setCarId(1)
            withTimeout(20_000) { entered.await() }
            awaitLatestHistoryLoad(vm)
            currentEnergyJob(vm).join()
            val calls = f.calls.filter { it.contains("/drives/") }
            assertEquals(1, calls.size)
            assertFalse(calls.single().endsWith("/drives/4"))
            assertNull(f.db.driveSummaryDao().get(car, 4)!!.toAnalysisDriveData().netEnergyKwh)
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun fleetSocEndpointsPublishTheSameLabeledEstimateWithoutNetwork() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "fleet-summary-soc.db"))
        var page: DrivesViewModel? = null
        try {
            f.initialize()
            val car = f.localId(1)
            val fleet = drive(66, "telemetry_mqtt").copy(
                sourceInstanceId = "fleet-a", sourceId = "record-66",
                odometerDetails = DriveOdometerDetails(distance = 10.0),
                batteryDetails = DriveBatteryDetails(startBatteryLevel = 80, endBatteryLevel = 70))
            f.db.driveSummaryDao().upsert(fleet.toSyncSummary(car)!!)
            val vm = createPage(f)
            page = vm
            vm.setCarId(1)
            awaitLatestHistoryLoad(vm)
            currentEnergyJob(vm).join()
            val repaired = requireNotNull(f.db.driveSummaryDao().get(car, 66)).toAnalysisDriveData()
            assertEquals(6.0, repaired.netEnergyKwh!!, 1e-9)
            assertEquals(600.0, repaired.efficiencyWhKm!!, 1e-9)
            assertEquals("estimated", repaired.energyContract!!.netEnergy!!.quality)
            assertEquals("soc_capacity_estimate", repaired.energyContract.netEnergy.method)
            assertTrue(f.calls.none { it.endsWith("/drives/66") })
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun incompletePowerAndExplicitProviderUnknownRemainUnknown() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "incomplete-power.db"))
        val detailCalls = Channel<String>(Channel.UNLIMITED)
        var page: DrivesViewModel? = null
        try {
            f.initialize()
            val car = f.localId(1)
            val partial = drive(10)
            val denied = drive(11)
            f.db.driveSummaryDao().upsertAll(listOf(partial, denied).map { it.toSyncSummary(car)!! })
            f.detailResponse = { path -> when {
                path.endsWith("/drives/10") -> response(partial,
                    """[{"date":"${Instant.parse(requireNotNull(partial.startDate)).plusSeconds(1)}","power":36},{"date":"${partial.endDate}","power":36}]""")
                    .replace("\"drive_details\":", "\"battery_details\":{\"start_battery_level\":60,\"end_battery_level\":60},\"drive_details\":")
                path.endsWith("/drives/11") -> response(denied,
                    """[{"date":"${denied.startDate}","power":36},{"date":"${denied.endDate}","power":36}]""")
                    .replace("\"drive_details\":", "\"energy_contract\":{\"version\":1,\"net_energy\":{\"quality\":\"unknown\"}},\"drive_details\":")
                else -> null
            } }
            f.onRequest = { if (it.contains("/drives/")) detailCalls.send(it) }
            val vm = createPage(f)
            page = vm
            vm.setCarId(1)
            awaitLatestHistoryLoad(vm)
            val calls = withTimeout(20_000) { List(2) { detailCalls.receive() } }
            currentEnergyJob(vm).join()
            assertEquals(2, calls.distinct().size)
            assertNull(f.db.driveSummaryDao().get(car, 10)!!.toAnalysisDriveData().netEnergyKwh)
            assertNull(f.db.driveSummaryDao().get(car, 11)!!.toAnalysisDriveData().netEnergyKwh)
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            detailCalls.close()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun changedSourceReceiptRejectsLateResultAndCanBeRetried() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "changed-receipt.db"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var page: DrivesViewModel? = null
        try {
            f.initialize()
            val row = drive(64)
            val car = f.localId(1)
            f.db.driveSummaryDao().upsert(row.toSyncSummary(car)!!)
            f.onRequest = { if (it.endsWith("/drives/64")) { entered.complete(Unit); release.await() } }
            f.detailResponse = { path -> if (path.endsWith("/drives/64")) response(row,
                """[{"date":"${row.startDate}","power":36},{"date":"${row.endDate}","power":36}]""") else null }
            val vm = createPage(f)
            page = vm
            vm.setCarId(1)
            withTimeout(10_000) { entered.await() }
            val previous = currentEnergyJob(vm)
            f.db.driveSummaryDao().upsert(row.copy(startAddress = "更新后的地点").toSyncSummary(car)!!)
            release.complete(Unit)
            withTimeout(10_000) { previous.join() }
            assertNull(f.db.driveSummaryDao().get(car, 64)!!.toAnalysisDriveData().netEnergyKwh)
            vm.refreshForForeground()
            withTimeout(20_000) { vm.uiState.first { it.driveMetrics[64]?.energyKwh != null } }
            assertEquals(2, f.calls.count { it.endsWith("/drives/64") })
            assertEquals(0.2, f.db.driveSummaryDao().get(car, 64)!!.toAnalysisDriveData().netEnergyKwh!!, 1e-9)
        } finally {
            release.complete(Unit)
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun leavingDuringRequestAllowsOneRetryWhenPageReturns() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "paused-power.db"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var page: DrivesViewModel? = null
        try {
            f.initialize()
            val row = drive(64)
            val car = f.localId(1)
            f.db.driveSummaryDao().upsert(row.toSyncSummary(car)!!)
            f.onRequest = { if (it.endsWith("/drives/64")) { entered.complete(Unit); release.await() } }
            f.detailResponse = { path -> if (path.endsWith("/drives/64")) response(row,
                """[{"date":"${row.startDate}","power":36},{"date":"${row.endDate}","power":36}]""") else null }
            val vm = createPage(f)
            page = vm
            vm.setCarId(1)
            withTimeout(10_000) { entered.await() }
            val previous = currentEnergyJob(vm)
            vm.pauseEnrichment()
            release.complete(Unit)
            withTimeout(10_000) { previous.join() }
            assertNull(f.db.driveSummaryDao().get(car, 64)!!.toAnalysisDriveData().netEnergyKwh)
            vm.refreshForForeground()
            withTimeout(20_000) { vm.uiState.first { it.driveMetrics[64]?.energyKwh != null } }
            assertEquals(2, f.calls.count { it.endsWith("/drives/64") })
            assertEquals(0.2, f.db.driveSummaryDao().get(car, 64)!!.toAnalysisDriveData().netEnergyKwh!!, 1e-9)
        } finally {
            release.complete(Unit)
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun delayedAutomaticEstimateDoesNotReplaceQualifiedDetail() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "newer-qualified-power.db"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var page: DrivesViewModel? = null
        try {
            f.initialize()
            val row = drive(64)
            val car = f.localId(1)
            f.db.driveSummaryDao().upsert(row.toSyncSummary(car)!!)
            f.onRequest = { if (it.endsWith("/drives/64")) { entered.complete(Unit); release.await() } }
            f.detailResponse = { path -> if (path.endsWith("/drives/64")) response(row,
                """[{"date":"${row.startDate}","power":36},{"date":"${row.endDate}","power":36}]""") else null }
            val vm = createPage(f)
            page = vm
            vm.setCarId(1)
            withTimeout(10_000) { entered.await() }
            val previous = currentEnergyJob(vm)
            f.db.driveSummaryDao().mergeResolvedDetail(car, detail(row).copy(energyConsumedNet = 1.0)) { }
            release.complete(Unit)
            withTimeout(10_000) { previous.join() }
            assertEquals(1.0, f.db.driveSummaryDao().get(car, 64)!!.toAnalysisDriveData().netEnergyKwh!!, 1e-9)
        } finally {
            release.complete(Unit)
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun accountChangeRejectsLateDetail() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = HistoryResourceFixture(File(temp.root, "late-account.db"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var page: DrivesViewModel? = null
        try {
            f.initialize(cloud = true)
            val row = drive(64)
            val oldCar = f.localId(1)
            f.db.driveSummaryDao().upsert(row.toSyncSummary(oldCar)!!)
            f.onRequest = { if (it.endsWith("/drives/64")) { entered.complete(Unit); release.await() } }
            f.detailResponse = { path -> when {
                path.endsWith("/history-context") ->
                    """{"data":{"capability_version":1,"car_id":1,"vehicle_uid":"synthetic-vehicle-1"}}"""
                path.endsWith("/drives/64") -> response(row,
                    """[{"date":"${row.startDate}","power":36},{"date":"${row.endDate}","power":36}]""")
                else -> null
            } }
            val vm = createPage(f)
            page = vm
            vm.setCarId(1)
            withTimeout(10_000) { entered.await() }
            val previous = currentEnergyJob(vm)
            f.sessions.save("other-access", "other-refresh", 3600, "different-account")
            release.complete(Unit)
            withTimeout(10_000) { previous.join() }
            withTimeout(10_000) {
                while (vm.uiState.value.drives.any { it.driveId == 64 }) delay(10)
            }
            assertNull(f.db.driveSummaryDao().get(oldCar, 64)!!.toAnalysisDriveData().netEnergyKwh)
        } finally {
            release.complete(Unit)
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close()
            Dispatchers.resetMain()
        }
    }
}

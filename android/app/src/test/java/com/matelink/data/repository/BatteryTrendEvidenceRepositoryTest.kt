package com.matelink.data.repository

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.matelink.BuildConfig
import com.matelink.data.api.TeslamateApi
import com.matelink.data.api.ArchiveEnrollmentRequestFence
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveRange
import com.matelink.data.local.ConnectionMode
import com.matelink.data.local.VehicleStatusStore
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.BatteryTrendSource
import com.matelink.domain.analytics.estimateBatteryTrend
import com.matelink.ui.screens.battery.BatteryViewModel
import com.squareup.moshi.Moshi
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class BatteryTrendEvidenceRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private class Server {
        val queries = Collections.synchronizedList(mutableListOf<Pair<String?, String?>>())
        @Volatile var empty = false
        @Volatile var total = 110
        @Volatile var failCursor: String? = null
        @Volatile var conflict = false
        @Volatile var changedSameHash = false
        @Volatile var badUnits = false
        @Volatile var badTimestamp = false
        @Volatile var badRangeSource = false
        @Volatile var snapshotDrift = false
        @Volatile var zeroRange = false
        @Volatile var oversized = false
        @Volatile var evidenceStatus = 200
        @Volatile var mixedSources = false
        @Volatile var alternateSource = false
        @Volatile var repeatedCursor = false
        var onEvidence: suspend () -> Unit = {}
        var onHistoryContext: suspend () -> Unit = {}

        private fun observation(id: Int): JSONObject {
            val day = if (mixedSources && id == 2) 0L else id - 1L
            val start = Instant.parse("2026-07-01T00:00:00Z").plusSeconds(day * 86_400L)
            val end = start.plusSeconds(600)
            fun boundary(soc: Int, time: Instant, position: Int, range: Double, source: String) = JSONObject()
                .put("soc_percent", soc).put("soc_position_observed_at", time.toString())
                .put("soc_position_id", position).put("rated_range_km", range)
                .put("rated_range_source", source).put("rated_range_observed_at", JSONObject.NULL)
            val startTime = if (badTimestamp && id == 1) start.plusSeconds(10) else start
            val observation = JSONObject().put("source_record_id", "teslamate:drive:$id")
                .put("started_at", start.toString()).put("ended_at", end.toString())
                .put("start", boundary(80, startTime, id * 2,
                    if (zeroRange && id == 1) 0.0 else 320.0 - id / 100.0 + if (changedSameHash && id == 1) 1.0 else 0.0,
                    if (badRangeSource && id == 1) "unknown" else "teslamate_drive_start_summary"))
                .put("end", boundary(79, end, id * 2 + 1, 316.0, "teslamate_drive_end_summary"))
                .put("outside_temp_avg_c", 22.0).put("temperature_source", "teslamate_drive_average")
            val other = alternateSource || (mixedSources && id == 2)
            return JSONObject().put("source_instance_id", if (other) "other-instance" else "synthetic-instance")
                .put("source_vehicle_id", if (other) "other-source-vehicle" else "synthetic-source-vehicle")
                .put("payload_sha256", if (conflict && id == 1) "f".repeat(64) else "%064x".format(id))
                .put("observation", observation)
        }

        fun page(cursor: String?, fixed: String?): String {
            queries += cursor to fixed
            val from = cursor?.toInt() ?: 0
            val maximum = if (empty) 0 else total
            val last = minOf(from + 50, maximum)
            val entries = JSONArray()
            if (!empty) for (id in (from + 1)..last) entries.put(observation(id))
            val complete = last >= maximum
            val next = if (complete) null else if (repeatedCursor && from > 0) from.toString() else last.toString()
            return JSONObject().put("data", JSONObject().put("schema_version", 1)
                .put("units", JSONObject().put("rated_range", if (badUnits) "mi" else "km")
                    .put("temperature", "C").put("soc", "percent"))
                .put("snapshot_max_seq", maximum + if (snapshotDrift && from > 0) 1 else 0)
                .put("next_cursor", next ?: JSONObject.NULL)
                .put("complete", complete).put("observations", entries)).toString()
        }
    }

    private suspend fun fixture(name: String, server: Server): HistoryResourceFixture {
        val f = HistoryResourceFixture(File(temp.root, "$name.db"))
        f.initialize(cloud = true)
        com.matelink.data.repository.SettingsRepository(f.context).setMockMode(false)
        installTransport(f, server)
        return f
    }

    private suspend fun installTransport(f: HistoryResourceFixture, server: Server) {
        // Populate both normal and cloud-only API cache keys before replacing their transport.
        if (f.modes.mode.first() == ConnectionMode.TESLA_CLOUD) {
            f.apiFactory.create(BuildConfig.JOURVOLT_API_BASE_URL)
            f.apiFactory.create(BuildConfig.JOURVOLT_API_BASE_URL, requiredCloudMode = true)
        } else f.apiFactory.create("https://synthetic.example")
        val moshi = Moshi.Builder().build()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val path = url.encodedPath
            val cursor = url.queryParameter("cursor")
            if (path.endsWith("/history-context")) runBlocking { server.onHistoryContext() }
            if (path.endsWith("/battery-trend/evidence")) {
                val fence = requireNotNull(chain.request().tag(ArchiveEnrollmentRequestFence::class.java))
                check(runBlocking { fence.isCurrent() }) { "Evidence request lacks a current scoped identity" }
                runBlocking { server.onEvidence() }
            }
            val status = if (path.endsWith("/battery-trend/evidence")) {
                if (cursor == server.failCursor && cursor != null) 503 else server.evidenceStatus
            } else 200
            val body = when {
                path.endsWith("/history-context") -> {
                    val id = path.substringBeforeLast('/').substringAfterLast('/').toInt()
                    """{"data":{"capability_version":1,"car_id":$id,"vehicle_uid":"synthetic-vehicle-$id"}}"""
                }
                path.endsWith("/battery-trend/evidence") -> if (server.oversized) "x".repeat(1024 * 1024 + 1)
                    else server.page(cursor, url.queryParameter("snapshot_max_seq"))
                path.endsWith("/battery-health") -> """{"data":{"battery_health":{"max_capacity":75,"current_capacity":70}}}"""
                path.endsWith("/status") -> """{"data":{"status":{"display_name":"synthetic-car","battery_details":{"battery_level":80}},"units":{"unit_of_length":"km","unit_of_temperature":"C"}}}"""
                path.endsWith("/drives") -> """{"data":{"drives":[]}}"""
                path.endsWith("/charges") -> """{"data":{"charges":[]}}"""
                else -> """{"data":{"cars":[{"car_id":1,"vehicle_uid":"synthetic-vehicle-1"},{"car_id":2,"vehicle_uid":"synthetic-vehicle-2"}]}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://synthetic.example/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(TeslamateApi::class.java)
        @Suppress("UNCHECKED_CAST")
        val cache = f.apiFactory.javaClass.getDeclaredField("apiCache").apply { isAccessible = true }
            .get(f.apiFactory) as MutableMap<Any, TeslamateApi>
        cache.keys.toList().forEach { cache[it] = api }
    }

    private fun history(f: HistoryResourceFixture) = UnifiedHistoryRepository(f.apiRepo, f.vehicleContexts,
        f.db.driveSummaryDao(), f.db.chargeSummaryDao())

    private fun evidence(f: HistoryResourceFixture, history: UnifiedHistoryRepository = history(f),
                         pageTimeoutMs: Long = 20_000L, totalTimeoutMs: Long = 300_000L) =
        BatteryTrendEvidenceRepository(f.db, f.apiRepo, history, f.vehicleContexts,
            Moshi.Builder().build(), pageTimeoutMs, totalTimeoutMs)

    private fun count(f: HistoryResourceFixture): Int = f.db.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM battery_trend_evidence").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun seedCloudHistory(f: HistoryResourceFixture, mixed: Boolean) {
        val local = f.vehicleContexts.cachedContextForRemote(1)!!.localHistoryCarId
        val start = Instant.parse("2026-07-01T00:00:00Z")
        val rows = (1..12).map { id ->
            val at = start.plusSeconds((id - 1L) * 3 * 86_400)
            DriveData(id, startDate = at.toString(), endDate = at.plusSeconds(600).toString(),
                source = "teslamate_archive", sourceInstanceId = if (mixed && id == 2) "other" else "history-source",
                sourceRecordId = "history-$id", qualityState = "observed", lengthUnit = "km",
                temperatureUnit = "C", outsideTempAvg = 22.0,
                batteryDetails = DriveBatteryDetails(startBatteryLevel = 80, endBatteryLevel = 79),
                rangeRated = DriveRange(startRange = 320.0, endRange = 316.0)).toSyncSummary(local)!!
        }
        f.db.driveSummaryDao().upsertAll(rows)
    }

    private suspend fun awaitBatteryLoader(page: BatteryViewModel) = withTimeout(30_000) {
        val loader = BatteryViewModel::class.java.getDeclaredField("loader").apply { isAccessible = true }.get(page)
        val job = loader.javaClass.getDeclaredField("job").apply { isAccessible = true }
        while (job.get(loader) == null) kotlinx.coroutines.delay(10)
        (job.get(loader) as Job).join()
    }

    private fun removeVerifiedDigest(f: HistoryResourceFixture, databaseName: String, digest: String) {
        val prefs = f.context.getSharedPreferences("contexts-$databaseName.db", Context.MODE_PRIVATE)
        val keys = prefs.all.filter { (key, value) -> key.endsWith(":verified-identity") && value == digest }.keys
        assertEquals(1, keys.size)
        val edit = prefs.edit(); keys.forEach(edit::remove); assertTrue(edit.commit())
    }

    @Test fun threeStablePagesRestore110SamplesAndSurviveRoomReopen() = runBlocking {
        val server = Server(); val f = fixture("pages", server)
        try {
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            val repo = evidence(f)
            assertNull((repo.loadCached(scope, vehicle) as ApiResult.Success).data)
            val loaded = (repo.refresh(scope, vehicle) as ApiResult.Success).data!!
            assertEquals(110, loaded.samples.size)
            assertFalse(loaded.isCached)
            assertEquals(BatteryTrendSource.TREND_ESTIMATE, estimateBatteryTrend(loaded.samples).source)
            assertEquals(listOf(null, "50", "100"), server.queries.map { it.first })
            assertEquals(listOf(null, "110", "110"), server.queries.map { it.second })
            assertEquals(110, count(f))
            f.reopen()
            val reopened = (evidence(f).loadCached(scope, vehicle) as ApiResult.Success).data!!
            assertEquals(110, reopened.samples.size)
            assertTrue(reopened.isCached)
        } finally { f.close() }
    }

    @Test fun failedPartialEmptyUnknownUnitsAndConflictsNeverEraseCachedEvidence() = runBlocking {
        val server = Server(); val f = fixture("preserve", server)
        try {
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            val repo = evidence(f)
            assertEquals(110, (repo.refresh(scope, vehicle) as ApiResult.Success).data!!.samples.size)
            server.failCursor = "50"
            assertTrue(repo.refresh(scope, vehicle) is ApiResult.Error)
            assertEquals(110, count(f))
            server.failCursor = null; server.empty = true
            val empty = (repo.refresh(scope, vehicle) as ApiResult.Success).data!!
            assertEquals(110, empty.samples.size); assertTrue(empty.isCached)
            server.empty = false; server.badUnits = true
            assertTrue(repo.refresh(scope, vehicle) is ApiResult.Error)
            server.badUnits = false; server.badTimestamp = true
            assertTrue(repo.refresh(scope, vehicle) is ApiResult.Error)
            server.badTimestamp = false; server.badRangeSource = true
            assertTrue(repo.refresh(scope, vehicle) is ApiResult.Error)
            server.badRangeSource = false; server.conflict = true
            assertTrue(repo.refresh(scope, vehicle) is ApiResult.Error)
            server.conflict = false; server.changedSameHash = true
            assertTrue(repo.refresh(scope, vehicle) is ApiResult.Error)
            server.changedSameHash = false; server.oversized = true
            assertTrue(repo.refresh(scope, vehicle) is ApiResult.Error)
            server.oversized = false
            server.snapshotDrift = true
            assertTrue(repo.refresh(scope, vehicle) is ApiResult.Error)
            server.snapshotDrift = false; server.repeatedCursor = true
            assertTrue(repo.refresh(scope, vehicle) is ApiResult.Error)
            assertEquals(110, count(f))
            assertEquals(110, (repo.loadCached(scope, vehicle) as ApiResult.Success).data!!.samples.size)
        } finally { f.close() }
    }

    @Test fun genuineZeroRangeIsStoredButNotUsedAsPositiveRange() = runBlocking {
        val server = Server().apply { zeroRange = true }; val f = fixture("zero", server)
        try {
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            val loaded = (evidence(f).refresh(scope, vehicle) as ApiResult.Success).data!!
            assertEquals(110, count(f))
            assertEquals(110, loaded.samples.size)
            val firstDay = loaded.samples.single { it.date == LocalDate.parse("2026-07-01") }
            assertEquals(79.0, firstDay.socPercent!!, 0.0)
            assertEquals(316.0, firstDay.ratedRangeKm!!, 0.0)
        } finally { f.close() }
    }

    @Test fun interruptedFirstSnapshotDoesNotPersistPartialPages() = runBlocking {
        val server = Server().apply { failCursor = "50" }; val f = fixture("partial", server)
        try {
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            val repo = evidence(f)
            assertTrue(repo.refresh(scope, vehicle) is ApiResult.Error)
            assertEquals(0, count(f)); assertNull((repo.loadCached(scope, vehicle) as ApiResult.Success).data)
        } finally { f.close() }
    }

    @Test fun tenSlowPagesHaveIndividualDeadlinesAndOneBoundedWholeSnapshot() = runBlocking {
        val server = Server().apply { total = 477; onEvidence = { kotlinx.coroutines.delay(200) } }
        val f = fixture("slow-pages", server)
        try {
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            val repo = evidence(f, pageTimeoutMs = 1_500, totalTimeoutMs = 15_000)
            val started = System.nanoTime()
            val loaded = (repo.refresh(scope, vehicle) as ApiResult.Success).data!!
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("Ten delayed pages must exceed one page deadline in total", elapsedMs > 1_500)
            assertEquals(10, server.queries.size)
            assertEquals(477, loaded.samples.size)
            assertEquals(477, count(f))
        } finally { f.close() }
    }

    @Test fun perPageAndWholeSnapshotTimeoutLeaveNoPartialRows() = runBlocking {
        val server = Server().apply { onEvidence = { kotlinx.coroutines.delay(300) } }
        val f = fixture("timeouts", server)
        try {
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            assertTrue(evidence(f, pageTimeoutMs = 100, totalTimeoutMs = 5_000).refresh(scope, vehicle) is ApiResult.Error)
            assertEquals(0, count(f))
            assertTrue(evidence(f, pageTimeoutMs = 1_000, totalTimeoutMs = 400).refresh(scope, vehicle) is ApiResult.Error)
            assertEquals(0, count(f))
        } finally { f.close() }
    }

    @Test fun cancellationDuringSecondPageDoesNotPersistFirstPage() = runBlocking {
        val server = Server(); val f = fixture("cancel-page", server)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        try {
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            server.onEvidence = {
                if (requests.incrementAndGet() == 2) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            val running = async(Dispatchers.IO) { evidence(f).refresh(scope, vehicle) }
            withTimeout(10_000) { entered.await() }
            running.cancel()
            release.complete(Unit)
            running.cancelAndJoin()
            assertEquals(0, count(f))
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun oldRemoteMappingWithoutUidDigestIsReverifiedBeforeImport() = runBlocking {
        val server = Server(); val f = fixture("legacy-digest", server)
        try {
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            val digest = f.vehicleContexts.cachedVerifiedStatusIdentityDigest(1, scope, vehicle)!!
            removeVerifiedDigest(f, "legacy-digest", digest)
            assertNull(f.vehicleContexts.cachedVerifiedStatusIdentityDigest(1, scope, vehicle))
            val repo = evidence(f)
            assertNull((repo.loadCached(scope, vehicle) as ApiResult.Success).data)
            val loaded = (repo.refresh(scope, vehicle) as ApiResult.Success).data!!
            assertEquals(110, loaded.samples.size)
            assertNotNull(f.vehicleContexts.cachedVerifiedStatusIdentityDigest(1, scope, vehicle))
            assertEquals(110, count(f))
        } finally { f.close() }
    }

    @Test fun multiSourceSameWindowCannotBeMergedOrOverwriteSingleSourceCache() = runBlocking {
        val server = Server().apply { mixedSources = true }; val f = fixture("multi-source", server)
        try {
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            val repo = evidence(f)
            val initial = repo.refresh(scope, vehicle) as ApiResult.Error
            assertEquals("Battery trend evidence source conflict", initial.message)
            assertEquals(0, count(f))
            assertNull((repo.loadCached(scope, vehicle) as ApiResult.Success).data)

            server.mixedSources = false
            assertEquals(110, (repo.refresh(scope, vehicle) as ApiResult.Success).data!!.samples.size)
            server.mixedSources = true
            assertEquals("Battery trend evidence source conflict", (repo.refresh(scope, vehicle) as ApiResult.Error).message)
            assertEquals(110, (repo.loadCached(scope, vehicle) as ApiResult.Success).data!!.samples.size)
            server.mixedSources = false; server.alternateSource = true
            assertEquals("Battery trend evidence source conflict", (repo.refresh(scope, vehicle) as ApiResult.Error).message)
            assertEquals(110, count(f))
            val fingerprint = f.db.openHelper.readableDatabase.query("SELECT scopeFingerprint FROM battery_trend_evidence LIMIT 1").use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }
            val original = f.db.batteryTrendEvidenceDao().allFor(fingerprint, vehicle.localHistoryCarId).first()
            f.db.batteryTrendEvidenceDao().insertAll(listOf(original.copy(
                sourceInstanceId = "other-instance", sourceVehicleId = "other-source-vehicle")))
            assertEquals("Battery trend evidence source conflict", (repo.loadCached(scope, vehicle) as ApiResult.Error).message)
            server.alternateSource = false
            assertEquals("Battery trend evidence source conflict", (repo.refresh(scope, vehicle) as ApiResult.Error).message)
            assertEquals(111, count(f))
        } finally { f.close() }
    }

    @Test fun abaWhileDiscoveringVehicleRejectsBeforeAnyEvidenceRequest() = runBlocking {
        val server = Server(); val f = fixture("aba-discovery", server)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            val digest = f.vehicleContexts.cachedVerifiedStatusIdentityDigest(1, scope, vehicle)!!
            removeVerifiedDigest(f, "aba-discovery", digest)
            val repo = evidence(f)
            server.onHistoryContext = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            val old = async(Dispatchers.IO) { repo.refresh(scope, vehicle) }
            withTimeout(10_000) { entered.await() }
            f.sessions.save("synthetic-access", "synthetic-refresh", 3600, "different-account")
            f.sessions.save("synthetic-access", "synthetic-refresh", 3600, "synthetic-account")
            release.complete(Unit)
            val result = try { old.await() } catch (_: CancellationException) { null }
            assertTrue(result == null || result is ApiResult.Error)
            assertTrue(server.queries.isEmpty())
            assertEquals(0, count(f))
            assertNull(f.vehicleContexts.cachedVerifiedStatusIdentityDigest(1, scope, vehicle))
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun accountCarAndSourcePartitionsDoNotLeakAndAbaRejectsLatePage() = runBlocking {
        val server = Server(); val f = fixture("identity", server)
        try {
            val scopeA = f.vehicleContexts.captureReadScope()
            val vehicleA = f.vehicleContexts.cachedContextForRemote(1, scopeA)!!
            val repo = evidence(f)
            assertTrue(repo.refresh(scopeA, vehicleA) is ApiResult.Success)
            val otherCar = f.vehicleContexts.cachedContextForRemote(2, scopeA)!!
            assertNull((repo.loadCached(scopeA, otherCar) as ApiResult.Success).data)
            f.sessions.save("synthetic-b", "synthetic-refresh", 3600, "different-account")
            val scopeB = f.vehicleContexts.captureReadScope()
            val vehicleB = f.vehicleContexts.resolve(CarData(1, vehicleUid = "synthetic-vehicle-1"), scopeB)
            assertNull((repo.loadCached(scopeB, vehicleB) as ApiResult.Success).data)
            f.modes.set(ConnectionMode.SELF_HOSTED)
            val localScope = f.vehicleContexts.captureReadScope()
            val local = f.vehicleContexts.resolve(CarData(1), localScope)
            assertNull((repo.loadCached(localScope, local) as ApiResult.Success).data)
            val beforeLocal = server.queries.size
            assertTrue(repo.refresh(localScope, local) is ApiResult.Error)
            assertEquals(beforeLocal, server.queries.size)
            f.modes.set(ConnectionMode.TESLA_CLOUD)
            f.sessions.save("synthetic-a", "synthetic-refresh", 3600, "synthetic-account")
            installTransport(f, server)
            val again = f.vehicleContexts.captureReadScope()
            val restored = f.vehicleContexts.cachedContextForRemote(1, again)!!
            assertEquals(110, (repo.loadCached(again, restored) as ApiResult.Success).data!!.samples.size)

            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            server.total = 111
            server.onEvidence = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            val old = async(Dispatchers.IO) { repo.refresh(again, restored) }
            withTimeout(10_000) { entered.await() }
            f.sessions.save("synthetic-b", "synthetic-refresh", 3600, "different-account")
            f.sessions.save("synthetic-a", "synthetic-refresh", 3600, "synthetic-account")
            release.complete(Unit)
            try { old.await(); fail("ABA scope must reject the late page") } catch (_: CancellationException) { }
            assertEquals(110, count(f))
        } finally { f.close() }
    }

    @Test fun realBatteryViewModelDisplaysEvidenceAndKeepsItAfterEmptyRefresh() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val server = Server(); val f = fixture("viewmodel", server)
        var page: BatteryViewModel? = null
        try {
            val history = history(f)
            val vm = BatteryViewModel(f.apiRepo, AnalysisHistoryRepository(history, f.vehicleContexts),
                f.vehicleContexts, VehicleStatusStore(f.context, Moshi.Builder().build()), evidence(f, history))
            page = vm
            vm.setCarId(1)
            val loaded = withTimeout(30_000) { vm.uiState.first { it.batteryTrend?.sampleCount == 110 } }
            assertEquals(BatteryTrendSource.TREND_ESTIMATE, loaded.batteryTrend!!.source)
            assertEquals(110, vm.computeStats()!!.batteryTrend!!.sampleCount)
            server.evidenceStatus = 401
            val before = server.queries.size
            vm.refresh()
            withTimeout(30_000) { while (server.queries.size == before) kotlinx.coroutines.delay(10) }
            val unauthorized = withTimeout(30_000) { vm.uiState.first {
                !it.isRefreshing && it.batteryTrend?.sampleCount == 110 && it.isCachedTrend
            } }
            assertEquals(110, unauthorized.batteryTrend!!.sampleCount)
            assertEquals("A fenced 401 must not trigger an evidence retry", before + 1, server.queries.size)
            server.evidenceStatus = 200
            server.empty = true
            vm.refresh()
            val cached = withTimeout(30_000) { vm.uiState.first {
                !it.isRefreshing && it.batteryTrend?.sampleCount == 110 && it.isCachedTrend
            } }
            assertEquals(110, cached.batteryTrend!!.sampleCount)
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close(); Dispatchers.resetMain()
        }
    }

    @Test fun oldMappingWithoutUidDigestRecoversInRealBatteryViewModel() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val server = Server(); val f = fixture("legacy-vm", server)
        var page: BatteryViewModel? = null
        try {
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            val digest = f.vehicleContexts.cachedVerifiedStatusIdentityDigest(1, scope, vehicle)!!
            removeVerifiedDigest(f, "legacy-vm", digest)
            assertNull(f.vehicleContexts.cachedVerifiedStatusIdentityDigest(1, scope, vehicle))
            val history = history(f)
            val vm = BatteryViewModel(f.apiRepo, AnalysisHistoryRepository(history, f.vehicleContexts),
                f.vehicleContexts, VehicleStatusStore(f.context, Moshi.Builder().build()), evidence(f, history))
            page = vm
            vm.setCarId(1)
            val loaded = withTimeout(30_000) { vm.uiState.first { it.batteryTrend?.sampleCount == 110 } }
            assertEquals(BatteryTrendSource.TREND_ESTIMATE, loaded.batteryTrend!!.source)
            assertNotNull(f.vehicleContexts.cachedVerifiedStatusIdentityDigest(1, scope, vehicle))
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close(); Dispatchers.resetMain()
        }
    }

    @Test fun slowTenPagesDoNotBlockCachedEvidenceOrLiveBatteryStatus() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val server = Server(); val f = fixture("slow-vm", server)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var page: BatteryViewModel? = null
        try {
            val history = history(f); val repo = evidence(f, history)
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            assertEquals(110, (repo.refresh(scope, vehicle) as ApiResult.Success).data!!.samples.size)
            server.total = 477
            server.onEvidence = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
            val vm = BatteryViewModel(f.apiRepo, AnalysisHistoryRepository(history, f.vehicleContexts),
                f.vehicleContexts, VehicleStatusStore(f.context, Moshi.Builder().build()), repo)
            page = vm
            vm.setCarId(1)
            withTimeout(10_000) { entered.await() }
            val visible = withTimeout(20_000) { vm.uiState.first {
                it.batteryTrend?.sampleCount == 110 && it.isCachedTrend &&
                    it.carStatus?.displayName == "synthetic-car" && it.batteryHealth?.maxCapacity == 75.0
            } }
            assertFalse(release.isCompleted)
            assertEquals(110, visible.batteryTrend!!.sampleCount)
            release.complete(Unit)
            awaitBatteryLoader(vm)
            assertEquals(477, vm.uiState.value.batteryTrend!!.sampleCount)
        } finally {
            release.complete(Unit)
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close(); Dispatchers.resetMain()
        }
    }

    @Test fun multiSourceConflictPreservesSingleSourceCacheButNeverDisplaysMixedHistory() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var page: BatteryViewModel? = null
        val server = Server(); val f = fixture("conflict-vm-cached", server)
        try {
            val history = history(f); val repo = evidence(f, history)
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            assertEquals(110, (repo.refresh(scope, vehicle) as ApiResult.Success).data!!.samples.size)
            server.mixedSources = true
            val vm = BatteryViewModel(f.apiRepo, AnalysisHistoryRepository(history, f.vehicleContexts),
                f.vehicleContexts, VehicleStatusStore(f.context, Moshi.Builder().build()), repo)
            page = vm
            vm.setCarId(1)
            awaitBatteryLoader(vm)
            assertEquals(110, vm.uiState.value.batteryTrend!!.sampleCount)
            assertTrue(vm.uiState.value.isCachedTrend)
            assertEquals(110, count(f))
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close(); Dispatchers.resetMain()
        }

        Dispatchers.setMain(UnconfinedTestDispatcher())
        val mixed = Server().apply { mixedSources = true }; val second = fixture("conflict-vm-empty", mixed)
        var secondPage: BatteryViewModel? = null
        try {
            seedCloudHistory(second, mixed = true)
            val history = history(second)
            val vm = BatteryViewModel(second.apiRepo, AnalysisHistoryRepository(history, second.vehicleContexts),
                second.vehicleContexts, VehicleStatusStore(second.context, Moshi.Builder().build()), evidence(second, history))
            secondPage = vm
            vm.setCarId(1)
            awaitBatteryLoader(vm)
            assertTrue(vm.uiState.value.batteryTrend == null ||
                vm.uiState.value.batteryTrend?.source == BatteryTrendSource.UNAVAILABLE)
            assertEquals("synthetic-car", vm.uiState.value.carStatus?.displayName)
            assertEquals(0, count(second))
        } finally {
            secondPage?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            second.close(); Dispatchers.resetMain()
        }
    }

    @Test fun multiSourceConflictPreservesEvenAnInsufficientSingleSourceEvidenceCache() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val server = Server().apply { total = 3 }; val f = fixture("conflict-vm-short", server)
        var page: BatteryViewModel? = null
        try {
            val history = history(f); val repo = evidence(f, history)
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.cachedContextForRemote(1, scope)!!
            assertEquals(3, (repo.refresh(scope, vehicle) as ApiResult.Success).data!!.samples.size)
            server.mixedSources = true
            val vm = BatteryViewModel(f.apiRepo, AnalysisHistoryRepository(history, f.vehicleContexts),
                f.vehicleContexts, VehicleStatusStore(f.context, Moshi.Builder().build()), repo)
            page = vm
            vm.setCarId(1)
            awaitBatteryLoader(vm)
            assertEquals(BatteryTrendSource.UNAVAILABLE, vm.uiState.value.batteryTrend!!.source)
            assertEquals(3, vm.uiState.value.batteryTrend!!.sampleCount)
            assertTrue(vm.uiState.value.isCachedTrend)
            assertEquals(3, count(f))
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close(); Dispatchers.resetMain()
        }
    }

    @Test fun missingOrUnauthorizedEvidenceFallsBackOnlyToScopedSingleSourceHistory() = runBlocking {
        for (code in listOf(404, 401, 503)) {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            val server = Server().apply { evidenceStatus = code }; val f = fixture("fallback-$code", server)
            var page: BatteryViewModel? = null
            try {
                seedCloudHistory(f, mixed = false)
                val history = history(f)
                val vm = BatteryViewModel(f.apiRepo, AnalysisHistoryRepository(history, f.vehicleContexts),
                    f.vehicleContexts, VehicleStatusStore(f.context, Moshi.Builder().build()), evidence(f, history))
                page = vm
                vm.setCarId(1)
                awaitBatteryLoader(vm)
                assertEquals(12, vm.uiState.value.batteryTrend!!.sampleCount)
                assertTrue(vm.uiState.value.isCachedTrend)
                assertEquals("synthetic-car", vm.uiState.value.carStatus?.displayName)
                assertEquals(0, count(f))
                f.sessions.save("synthetic-access", "synthetic-refresh", 3600, "fallback-account-$code")
                vm.setCarId(1)
                val switched = withTimeout(30_000) { vm.uiState.first {
                    !it.isLoading && it.carStatus != null &&
                        (it.batteryTrend == null || it.batteryTrend?.source == BatteryTrendSource.UNAVAILABLE)
                } }
                assertNotEquals(12, switched.batteryTrend?.sampleCount)
            } finally {
                page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                f.close(); Dispatchers.resetMain()
            }
        }
    }

    @Test fun selfHostedBatteryStillUsesItsOwnDriveHistoryWithoutCallingCloudEvidence() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val server = Server(); val f = fixture("self-hosted", server)
        var page: BatteryViewModel? = null
        try {
            f.modes.set(ConnectionMode.SELF_HOSTED)
            val scope = f.vehicleContexts.captureReadScope()
            val vehicle = f.vehicleContexts.resolve(CarData(1), scope)
            val start = Instant.parse("2026-07-01T00:00:00Z")
            val rows = (1..12).map { id ->
                val at = start.plusSeconds((id - 1L) * 3 * 86_400)
                DriveData(id, startDate = at.toString(), endDate = at.plusSeconds(600).toString(),
                    source = "teslamate_archive", sourceInstanceId = "synthetic-self-hosted",
                    sourceRecordId = "drive-$id", qualityState = "observed", lengthUnit = "km",
                    temperatureUnit = "C", outsideTempAvg = 22.0,
                    batteryDetails = DriveBatteryDetails(startBatteryLevel = 80, endBatteryLevel = 79),
                    rangeRated = DriveRange(startRange = 320.0, endRange = 316.0))
                    .toSyncSummary(vehicle.localHistoryCarId)!!
            }
            f.db.driveSummaryDao().upsertAll(rows)
            installTransport(f, server)
            val history = history(f)
            val vm = BatteryViewModel(f.apiRepo, AnalysisHistoryRepository(history, f.vehicleContexts),
                f.vehicleContexts, VehicleStatusStore(f.context, Moshi.Builder().build()), evidence(f, history))
            page = vm
            vm.setCarId(1)
            val state = withTimeout(30_000) { vm.uiState.first { it.batteryTrend?.sampleCount == 12 } }
            assertNotEquals(BatteryTrendSource.UNAVAILABLE, state.batteryTrend!!.source)
            assertTrue(server.queries.isEmpty())
            assertEquals(0, count(f))
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close(); Dispatchers.resetMain()
        }
    }
}

package com.matelink.data.sync

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import com.matelink.BuildConfig
import com.matelink.data.api.NominatimApi
import com.matelink.data.api.TeslamateApi
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.repository.*
import com.matelink.di.TeslamateApiFactory
import com.matelink.notification.*
import com.squareup.moshi.Moshi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DetailSyncResourceRecoveryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun actualRepositorySharesTwentyRequestsAcrossDrivesChargesAndCarsAfterRoomReopen() = runBlocking {
        val fixture = HistoryResourceFixture(File(temp.root, "resource.db"))
        try {
            fixture.initialize()
            fixture.seed(1, 351, 500)
            fixture.seed(2, 2, 2)
            var batches = 0
            while (true) {
                val before = fixture.calls.size
                val result = runHistoryDetailBatch(listOf(1, 2)) { car, budget -> fixture.repo.syncDetailsOnly(car, budget) }
                val calls = fixture.calls.drop(before)
                assertTrue("batch requests=${calls.size}", calls.size <= 20)
                if (batches == 0) {
                    assertTrue(calls.any { it.contains("/cars/1/drives/") })
                    assertTrue(calls.any { it.contains("/cars/1/charges/") })
                    assertTrue(calls.any { it.contains("/cars/2/drives/") })
                    assertTrue(calls.any { it.contains("/cars/2/charges/") })
                    fixture.reopen()
                }
                batches++
                if (result == CarSyncResult.Complete) break
                assertTrue("unexpected failure $result", result is CarSyncResult.Deferred)
                assertTrue("finite recovery stalled", batches < 100)
            }
            assertEquals(855, fixture.calls.size)
            assertEquals(351, fixture.db.driveSummaryDao().getAllChronological(fixture.localId(1)).size)
            assertEquals(500, fixture.db.chargeSummaryDao().getAllForCar(fixture.localId(1)).size)
        } finally { fixture.close() }
    }

    @Test fun permanent404StaysVisibleAndChangedReceiptCanBeReconsidered() = runBlocking {
        val fixture = HistoryResourceFixture(File(temp.root, "404.db"))
        try {
            fixture.initialize(); fixture.seed(1, 2, 2)
            fixture.status = { path -> if (path.endsWith("/drives/1") || path.endsWith("/charges/1")) 404 else 200 }
            assertEquals(CarSyncResult.Complete, runHistoryDetailBatch(listOf(1)) { car, budget -> fixture.repo.syncDetailsOnly(car, budget) })
            val car = fixture.localId(1)
            assertEquals("detail_terminal_404", fixture.db.driveSummaryDao().get(car, 1)!!.qualityReason)
            assertEquals("detail_terminal_404", fixture.db.chargeSummaryDao().get(car, 1)!!.qualityReason)
            assertEquals(2, fixture.db.driveSummaryDao().getAllChronological(car).size)
            assertEquals(2, fixture.db.chargeSummaryDao().getAllForCar(car).size)
            val terminal = fixture.db.chargeSummaryDao().get(car, 1)!!
            fixture.db.chargeSummaryDao().upsertPreservingEvidence(listOf(terminal.copy(qualityReason = "missing_api_evidence")))
            assertEquals(0, fixture.db.chargeSummaryDao().countUnprocessedCharges(car, com.matelink.data.local.entity.SchemaVersion.CURRENT))
            fixture.db.chargeSummaryDao().upsertPreservingEvidence(listOf(terminal.copy(apiEvidence = "{}", qualityReason = "changed_receipt")))
            assertEquals(1, fixture.db.chargeSummaryDao().countUnprocessedCharges(car, com.matelink.data.local.entity.SchemaVersion.CURRENT))
        } finally { fixture.close() }
    }

    @Test fun sourceABADuringHttpCannotWriteTheReturnedDetail() = runBlocking {
        val fixture = HistoryResourceFixture(File(temp.root, "source-aba.db"))
        try {
            fixture.initialize(); fixture.seed(1, 2, 2)
            fixture.onRequest = {
                fixture.settings.saveServerUrl("https://synthetic-B.example")
                fixture.settings.saveServerUrl("https://synthetic.example")
            }
            try {
                runHistoryDetailBatch(listOf(1)) { car, budget -> fixture.repo.syncDetailsOnly(car, budget) }
                fail("ABA must cancel stale detail delivery")
            } catch (_: CancellationException) { }
            assertEquals(1, fixture.calls.size)
            assertEquals(2, fixture.db.driveSummaryDao().countUnprocessedDrives(fixture.localId(1), com.matelink.data.local.entity.SchemaVersion.DRIVE_ENERGY))
        } finally { fixture.close() }
    }

    @Test fun rateLimitAndAuthStopBeforeChargesOrLaterCars() = runBlocking {
        for (status in listOf(429, 401, 403)) {
            val fixture = HistoryResourceFixture(File(temp.root, "$status.db"))
            try {
                fixture.initialize(); fixture.seed(1, 2, 2); fixture.seed(2, 2, 2)
                fixture.status = { status }
                val outcome = runHistoryDetailBatch(listOf(1, 2)) { car, budget -> fixture.repo.syncDetailsOnly(car, budget) }
                assertTrue(outcome is CarSyncResult.Failed)
                assertEquals("status=$status must stop all further API calls", 1, fixture.calls.size)
            } finally { fixture.close() }
        }
    }
}

/** Initialized production repository, real disk Room and real Retrofit/OkHttp with isolated responses.
 * Reflection is restricted to substituting secure preferences and the factory's existing API cache;
 * repository/worker constructors and orchestration are never bypassed. */
internal class HistoryResourceFixture(private val databaseFile: File) {
    val context: Context = RuntimeEnvironment.getApplication()
    private val prefsName = databaseFile.name
    val settingsDisk = HistoryMemoryPreferences()
    val modes = ConnectionModeStore(HistoryMemoryPreferences())
    val sessions = JourVoltSessionStore(context.getSharedPreferences("sessions-$prefsName", Context.MODE_PRIVATE))
    private val contexts = VehicleContextStore(context.getSharedPreferences("contexts-$prefsName", Context.MODE_PRIVATE))
    private val secure: SecureSettingsDataStore
    val settings: SettingsDataStore
    val apiFactory: TeslamateApiFactory
    val apiRepo: TeslamateRepository
    lateinit var vehicleContexts: VehicleContextRepository
    var db: StatsDatabase = open()
    lateinit var repo: SyncRepository
    lateinit var syncManager: SyncManager
    val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
    var status: (String) -> Int = { 200 }
    var onRequest: suspend (String) -> Unit = {}
    val log = SyncLogCollector()
    private val moshi = Moshi.Builder().build()
    init {
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        secure = unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, SecureSettingsDataStore::class.java) as SecureSettingsDataStore
        fun field(name: String, value: Any) = SecureSettingsDataStore::class.java.getDeclaredField(name).apply { isAccessible = true }.set(secure, value)
        field("securePrefs", context.getSharedPreferences("secure-$prefsName", Context.MODE_PRIVATE))
        field("_apiTokenFlow", MutableStateFlow("")); field("_httpBasicPasswordFlow", MutableStateFlow("")); field("_amapKeyFlow", MutableStateFlow(""))
        settings = SettingsDataStore(settingsDisk, secure, context.getSharedPreferences("language-$prefsName", Context.MODE_PRIVATE))
        apiFactory = TeslamateApiFactory(settings, sessions, JourVoltSessionRefresher(sessions), modes, moshi)
        apiRepo = TeslamateRepository(apiFactory, settings, SettingsRepository(context), modes)
    }
    private fun open() = Room.databaseBuilder(context, StatsDatabase::class.java, databaseFile.absolutePath).allowMainThreadQueries().build()
    suspend fun initialize(cloud: Boolean = false) {
        modes.set(if (cloud) ConnectionMode.TESLA_CLOUD else ConnectionMode.SELF_HOSTED)
        settings.saveServerUrl("https://synthetic.example")
        if (cloud) sessions.save("synthetic-access", "synthetic-refresh", 3600, "synthetic-account")
        installApi()
        rebuild()
        val scope = vehicleContexts.captureReadScope()
        for (id in listOf(1, 2)) contexts.resolveVerifiedHistoryCar(CarData(id, vehicleUid = "synthetic-vehicle-$id"),
            scope.accountNamespace, scope.source, scope.serverIdentity, scope.effectiveApiOrigin)
    }
    private suspend fun installApi() {
        apiFactory.create("https://synthetic.example")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            calls.add(path)
            runBlocking { onRequest(path) }
            val code = status(path)
            val id = path.substringAfterLast('/').toIntOrNull() ?: 1
            val body = when {
                path.endsWith("/drives/$id") -> """{"data":{"drive":{"drive_id":$id,"start_date":"2026-10-14T00:00:00Z","end_date":"2026-10-14T00:01:00Z","source":"teslamate_archive","quality_state":"observed"}}}"""
                path.endsWith("/charges/$id") -> """{"data":{"charge":{"charge_id":$id,"start_date":"2026-10-14T00:00:00Z","end_date":"2026-10-14T00:01:00Z","source":"teslamate_archive","quality_state":"observed"}}}"""
                path.endsWith("/drives") -> """{"data":{"drives":[]}}"""
                path.endsWith("/charges") -> """{"data":{"charges":[]}}"""
                else -> """{"data":{"cars":[{"car_id":1,"vehicle_uid":"synthetic-vehicle-1"},{"car_id":2,"vehicle_uid":"synthetic-vehicle-2"}]}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val fake = Retrofit.Builder().baseUrl("https://synthetic.example/").client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi)).build().create(TeslamateApi::class.java)
        @Suppress("UNCHECKED_CAST")
        val cache = TeslamateApiFactory::class.java.getDeclaredField("apiCache").apply { isAccessible = true }.get(apiFactory) as MutableMap<Any, TeslamateApi>
        cache.keys.toList().forEach { cache[it] = fake }
    }
    private fun rebuild() {
        syncManager = SyncManager(db.syncStateDao(), db.driveSummaryDao(), db.chargeSummaryDao(), db.aggregateDao())
        val legacy = LegacyHistoryMigrationRepository(db, db.driveSummaryDao(), db.chargeSummaryDao(), db.aggregateDao(), db.chargeCostOverrideDao(), db.legacyHistoryArchiveDao())
        vehicleContexts = VehicleContextRepository(contexts, sessions, modes, settings, apiRepo, legacy)
        val nominatim = Proxy.newProxyInstance(NominatimApi::class.java.classLoader, arrayOf(NominatimApi::class.java)) { _, _, _ -> error("External geocoding forbidden in test") } as NominatimApi
        val geocode = GeocodingRepository(nominatim, AmapReverseGeocoder(context, AmapSettingsStore(context, secure)),
            db.geocodeCacheDao(), db.geocodeQueueDao(), db.geocodeProgressDao(), modes)
        repo = SyncRepository(apiRepo, db.driveSummaryDao(), db.chargeSummaryDao(), db.aggregateDao(), syncManager,
            geocode, modes, HistoryMetadataStore(), TripNotificationStateStore(context), TripNotificationManager(context),
            vehicleContexts, CompletedChargeEventStore(context), CompletedChargeNotificationManager(context))
    }
    fun localId(remote: Int): Int = runBlocking { vehicleContexts.cachedContextForRemote(remote)!!.localHistoryCarId }
    suspend fun seed(remote: Int, drives: Int, charges: Int) {
        val local = localId(remote)
        db.driveSummaryDao().upsertAll((1..drives).map { DriveData(it, startDate = "2026-10-14T00:00:00Z", endDate = "2026-10-14T00:01:00Z").toSyncSummary(local)!! })
        db.chargeSummaryDao().upsertAll((1..charges).map { ChargeData(it, startDate = "2026-10-14T00:00:00Z", endDate = "2026-10-14T00:01:00Z").toSyncSummary(local)!! })
    }
    fun reopen() { db.close(); db = open(); rebuild() }
    fun close() { db.close() }
}

internal class HistoryMemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
}

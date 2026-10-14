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
import com.matelink.domain.analytics.HistorySummaryEvidenceCodec
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
            assertTrue(HistorySummaryEvidenceCodec.isDetailTerminal404(fixture.db.driveSummaryDao().get(car, 1)!!.apiEvidence, car))
            assertTrue(HistorySummaryEvidenceCodec.isDetailTerminal404(fixture.db.chargeSummaryDao().get(car, 1)!!.apiEvidence, car))
            assertFalse(HistorySummaryEvidenceCodec.isDetailTerminal404(fixture.db.chargeSummaryDao().get(car, 1)!!.apiEvidence, car - 1))
            assertNull(fixture.db.aggregateDao().getDriveAggregate(car, 1))
            assertNull(fixture.db.aggregateDao().getChargeAggregate(car, 1))
            assertEquals(2, fixture.db.driveSummaryDao().getAllChronological(car).size)
            assertEquals(2, fixture.db.chargeSummaryDao().getAllForCar(car).size)
            val terminal = fixture.db.chargeSummaryDao().get(car, 1)!!
            fixture.db.chargeSummaryDao().upsertPreservingEvidence(listOf(terminal.copy(apiEvidence = HistorySummaryEvidenceCodec.sourceJson(terminal.apiEvidence), qualityReason = "missing_api_evidence")))
            assertEquals(0, fixture.db.chargeSummaryDao().countUnprocessedCharges(car, com.matelink.data.local.entity.SchemaVersion.CURRENT))
            fixture.db.chargeSummaryDao().upsertPreservingEvidence(listOf(terminal.copy(apiEvidence = HistorySummaryEvidenceCodec.encode(
                HistorySummaryEvidenceCodec.decodeCharge(terminal.apiEvidence!!)!!.copy(chargeEnergyAdded = 1.0)), qualityReason = "changed_receipt")))
            assertEquals(1, fixture.db.chargeSummaryDao().countUnprocessedCharges(car, com.matelink.data.local.entity.SchemaVersion.CURRENT))
        } finally { fixture.close() }
    }

    @Test fun observedAndDerivedSummaryStatsSurviveActual404AndDiskRoomReopen() = runBlocking {
        val fixture = HistoryResourceFixture(File(temp.root, "404-qualified-stats.db"))
        try {
            fixture.initialize()
            val car = fixture.localId(1)
            seedSummaryQualifications(fixture, car)
            suspend fun stats(): List<Any?> = listOf(
                fixture.db.driveSummaryDao().count(car), fixture.db.driveSummaryDao().sumDistance(car),
                fixture.db.driveSummaryDao().sumEnergyConsumed(car), fixture.db.chargeSummaryDao().count(car),
                fixture.db.chargeSummaryDao().sumEnergyAdded(car), fixture.db.chargeSummaryDao().sumCost(car))
            val before = stats()
            val driveRaw = (1..3).associateWith { HistorySummaryEvidenceCodec.sourceJson(fixture.db.driveSummaryDao().get(car, it)!!.apiEvidence) }
            val chargeRaw = (1..3).associateWith { HistorySummaryEvidenceCodec.sourceJson(fixture.db.chargeSummaryDao().get(car, it)!!.apiEvidence) }
            assertEquals(listOf(2, 12.0, 1.5, 2, 10.0, 2.0), before)
            fixture.status = { 404 }
            assertEquals(CarSyncResult.Complete, runHistoryDetailBatch(listOf(1)) { id, budget -> fixture.repo.syncDetailsOnly(id, budget) })
            fixture.reopen()
            assertEquals("Unavailable detail must not remove qualified summary metrics from Stats SQL", before, stats())
            for (id in 1..3) {
                val driveEvidence = fixture.db.driveSummaryDao().get(car, id)!!.apiEvidence
                val chargeEvidence = fixture.db.chargeSummaryDao().get(car, id)!!.apiEvidence
                assertEquals(driveRaw[id], HistorySummaryEvidenceCodec.sourceJson(driveEvidence))
                assertEquals(chargeRaw[id], HistorySummaryEvidenceCodec.sourceJson(chargeEvidence))
                assertTrue(HistorySummaryEvidenceCodec.isDetailTerminal404(driveEvidence, car))
                assertTrue(HistorySummaryEvidenceCodec.isDetailTerminal404(chargeEvidence, car))
                assertFalse(HistorySummaryEvidenceCodec.isDetailTerminal404(driveEvidence, car - 1))
                assertFalse(HistorySummaryEvidenceCodec.isDetailTerminal404(chargeEvidence, car - 1))
                assertNull(fixture.db.aggregateDao().getDriveAggregate(car, id))
                assertNull(fixture.db.aggregateDao().getChargeAggregate(car, id))
            }
            assertEquals(4, fixture.db.query("SELECT COUNT(*) FROM drives_summary WHERE carId = ?", arrayOf(car)).use { it.moveToFirst(); it.getInt(0) })
            assertEquals(4, fixture.db.query("SELECT COUNT(*) FROM charges_summary WHERE carId = ?", arrayOf(car)).use { it.moveToFirst(); it.getInt(0) })
        } finally { fixture.close() }
    }

    @Test fun terminal404PreservesExactSummaryQualityReasonIncludingUnqualifiedRowsAfterReopen() = runBlocking {
        val fixture = HistoryResourceFixture(File(temp.root, "404-quality-reasons.db"))
        try {
            fixture.initialize()
            val car = fixture.localId(1)
            seedSummaryQualifications(fixture, car)
            fun states(table: String): List<Pair<String, String>> = fixture.db.query(
                "SELECT qualityState, qualityReason FROM $table WHERE carId = ? ORDER BY " +
                    if (table == "drives_summary") "driveId" else "chargeId", arrayOf(car)).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
            }
            val beforeDrive = states("drives_summary")
            val beforeCharge = states("charges_summary")
            fixture.status = { 404 }
            runHistoryDetailBatch(listOf(1)) { id, budget -> fixture.repo.syncDetailsOnly(id, budget) }
            fixture.reopen()
            assertEquals("A processing failure is separate from original summary qualification/reason", beforeDrive, states("drives_summary"))
            assertEquals(beforeCharge, states("charges_summary"))
            assertEquals(2, fixture.db.driveSummaryDao().count(car))
            assertEquals(2, fixture.db.chargeSummaryDao().count(car))
            assertEquals(6, fixture.calls.size) // quarantined rows never requested or promoted
        } finally { fixture.close() }
    }

    private suspend fun seedSummaryQualifications(fixture: HistoryResourceFixture, car: Int) {
        val quality = listOf("observed" to "genuine_summary_observed", "derived" to "genuine_summary_derived",
            "incomplete" to "legacy_missing_receipt", "quarantined" to "legacy_ambiguous_source")
        val drives = quality.mapIndexed { index, (state, reason) ->
            DriveData(index + 1, startDate = "2026-10-14T00:00:00Z", endDate = "2026-10-14T00:01:00Z",
                source = "teslamate_archive", odometerDetails = DriveOdometerDetails(distance = 6.0))
                .toSyncSummary(car)!!.copy(qualityState = state, qualityReason = reason,
                    distance = if (index < 2) 6.0 else 777.0, energyConsumed = if (index < 2) 0.75 else 777.0)
        }
        val charges = quality.mapIndexed { index, (state, reason) ->
            ChargeData(index + 1, startDate = "2026-10-14T00:00:00Z", endDate = "2026-10-14T00:01:00Z",
                source = "teslamate_archive", chargeEnergyAdded = 5.0, cost = 1.0)
                .toSyncSummary(car)!!.copy(qualityState = state, qualityReason = reason,
                    energyAdded = if (index < 2) 5.0 else 777.0, cost = if (index < 2) 1.0 else 777.0)
        }
        fixture.db.driveSummaryDao().upsertAll(drives)
        fixture.db.chargeSummaryDao().upsertAll(charges)
    }

    @Test fun terminal404OnLegacyNullEvidenceRecordsOnlyLocalRecoveryMetadata() = runBlocking {
        val fixture = HistoryResourceFixture(File(temp.root, "404-legacy-null.db"))
        try {
            fixture.initialize(); fixture.seed(1, 1, 1)
            val car = fixture.localId(1)
            fixture.db.driveSummaryDao().get(car, 1)!!.let {
                fixture.db.driveSummaryDao().upsert(it.copy(apiEvidence = null, qualityState = "incomplete", qualityReason = "legacy_missing_receipt"))
            }
            fixture.db.chargeSummaryDao().get(car, 1)!!.let {
                fixture.db.chargeSummaryDao().upsert(it.copy(apiEvidence = null, qualityState = "incomplete", qualityReason = "legacy_missing_receipt"))
            }
            fixture.status = { 404 }
            assertEquals(CarSyncResult.Complete, runHistoryDetailBatch(listOf(1)) { id, budget -> fixture.repo.syncDetailsOnly(id, budget) })
            fixture.reopen()
            val drive = fixture.db.driveSummaryDao().get(car, 1)!!
            val charge = fixture.db.chargeSummaryDao().get(car, 1)!!
            assertNull(HistorySummaryEvidenceCodec.sourceJson(drive.apiEvidence))
            assertNull(HistorySummaryEvidenceCodec.sourceJson(charge.apiEvidence))
            assertTrue(HistorySummaryEvidenceCodec.isDetailTerminal404(drive.apiEvidence, car))
            assertTrue(HistorySummaryEvidenceCodec.isDetailTerminal404(charge.apiEvidence, car))
            assertEquals("legacy_missing_receipt", drive.qualityReason)
            assertEquals("legacy_missing_receipt", charge.qualityReason)
            assertEquals("incomplete", drive.qualityState)
            assertEquals("incomplete", charge.qualityState)
            assertEquals(0, fixture.db.driveSummaryDao().count(car))
            assertEquals(0, fixture.db.chargeSummaryDao().count(car))
        } finally { fixture.close() }
    }

    @Test fun changedGenuineReceiptReconsidersLegacyTerminalReasonWithoutUpgradingSummary() = runBlocking {
        val fixture = HistoryResourceFixture(File(temp.root, "404-legacy-receipt.db"))
        try {
            fixture.initialize(); fixture.seed(1, 1, 1)
            val car = fixture.localId(1)
            val drive = fixture.db.driveSummaryDao().get(car, 1)!!.copy(qualityState = "incomplete", qualityReason = "detail_terminal_404")
            val charge = fixture.db.chargeSummaryDao().get(car, 1)!!.copy(qualityState = "incomplete", qualityReason = "detail_terminal_404")
            fixture.db.driveSummaryDao().upsert(drive); fixture.db.chargeSummaryDao().upsert(charge)
            fixture.db.driveSummaryDao().upsertPreservingEvidence(listOf(drive.copy(qualityReason = "api_evidence")))
            fixture.db.chargeSummaryDao().upsertPreservingEvidence(listOf(charge.copy(qualityReason = "api_evidence")))
            assertEquals(0, fixture.db.driveSummaryDao().countUnprocessedDrives(car, com.matelink.data.local.entity.SchemaVersion.DRIVE_ENERGY))
            assertEquals(0, fixture.db.chargeSummaryDao().countUnprocessedCharges(car, com.matelink.data.local.entity.SchemaVersion.CURRENT))
            val updatedDrive = drive.copy(apiEvidence = HistorySummaryEvidenceCodec.encode(
                HistorySummaryEvidenceCodec.decodeDrive(drive.apiEvidence!!)!!.copy(odometerDetails = DriveOdometerDetails(distance = 2.0))),
                qualityState = "observed", qualityReason = "genuine_changed_receipt")
            val updatedCharge = charge.copy(apiEvidence = HistorySummaryEvidenceCodec.encode(
                HistorySummaryEvidenceCodec.decodeCharge(charge.apiEvidence!!)!!.copy(chargeEnergyAdded = 2.0)),
                qualityState = "observed", qualityReason = "genuine_changed_receipt")
            fixture.db.driveSummaryDao().upsertPreservingEvidence(listOf(updatedDrive))
            fixture.db.chargeSummaryDao().upsertPreservingEvidence(listOf(updatedCharge))
            fixture.reopen()
            assertEquals(listOf(1), fixture.db.driveSummaryDao().getUnprocessedDriveBatchIds(car, com.matelink.data.local.entity.SchemaVersion.DRIVE_ENERGY, 20))
            assertEquals(listOf(1), fixture.db.chargeSummaryDao().getUnprocessedChargeBatchIds(car, com.matelink.data.local.entity.SchemaVersion.CURRENT, 20))
            assertEquals("incomplete", fixture.db.driveSummaryDao().get(car, 1)!!.qualityState)
            assertEquals("incomplete", fixture.db.chargeSummaryDao().get(car, 1)!!.qualityState)
            assertEquals("genuine_changed_receipt", fixture.db.driveSummaryDao().get(car, 1)!!.qualityReason)
            assertEquals("genuine_changed_receipt", fixture.db.chargeSummaryDao().get(car, 1)!!.qualityReason)
            assertEquals(0, fixture.db.driveSummaryDao().count(car))
            assertEquals(0, fixture.db.chargeSummaryDao().count(car))
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

package com.matelink.ui.screens.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import com.matelink.data.api.TeslamateApi
import com.matelink.data.api.models.*
import com.matelink.data.local.*
import com.matelink.data.local.entity.*
import com.matelink.data.model.Instance
import com.matelink.data.repository.*
import com.matelink.data.sync.HistoryMemoryPreferences
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toAggregate
import com.matelink.data.sync.toSyncSummary
import com.matelink.notification.SentryNotificationManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual settings/instance VMs and disk Room. Scheduled jobs are replaced with no-I/O test workers. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ExternalReviewSettingsScopeTest {
    @get:Rule val temp = TemporaryFolder()
    private val managers = mutableListOf<WorkManagerImpl>()
    private val start = "2026-10-14T00:00:00Z"
    private val end = "2026-10-14T00:01:00Z"
    private val instanceNamePrefix = "ExternalReviewSettingsScopeTest-"
    private val instanceServerPrefix = "https://external-review-settings-"
    private val firstServer = "${instanceServerPrefix}a.synthetic.example"
    private val secondServer = "${instanceServerPrefix}b.synthetic.example"

    @After fun releaseManagers() {
        managers.forEach { it.cancelAllWork().result.get() }
        WorkManagerImpl.setDelegate(null)
        managers.forEach { it.workDatabase.close() }
    }

    private fun manager(f: HistoryResourceFixture) {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String,
                workerParameters: WorkerParameters): ListenableWorker =
                object : Worker(appContext, workerParameters) {
                    override fun doWork(): ListenableWorker.Result = ListenableWorker.Result.success()
                }
        }
        val configuration = Configuration.Builder().setWorkerFactory(factory).build()
        val manager = Class.forName("androidx.work.impl.WorkManagerImplExtKt")
            .getMethod("createWorkManager", Context::class.java, Configuration::class.java)
            .invoke(null, f.context, configuration) as WorkManagerImpl
        manager.cancelAllWork().result.get()
        WorkManagerImpl.setDelegate(manager)
        managers += manager
    }

    private suspend fun fixture(name: String): HistoryResourceFixture {
        val f = HistoryResourceFixture(File(temp.root, "$name.db"))
        SettingsRepository(f.context).setMockMode(false)
        SettingsRepository(f.context).setCurrentCarId(7)
        f.initialize()
        // Configuration changes must never create a client that can issue real HTTP.
        val cacheField = f.apiFactory.javaClass.getDeclaredField("apiCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(f.apiFactory) as Map<Any, TeslamateApi>
        val intercepted = cache.values.first()
        cacheField.set(f.apiFactory, object : LinkedHashMap<Any, TeslamateApi>() {
            override fun get(key: Any): TeslamateApi = intercepted
        })
        f.detailResponse = { path ->
            """{"data":{"cars":[{"car_id":7,"vehicle_uid":"synthetic-vehicle-7"}]}}"""
                .takeIf { path.endsWith("/cars") }
        }
        return f
    }

    private data class Seeded(
        val current: VehicleContext,
        val drives: Map<Int, DriveSummary>,
        val charges: Map<Int, ChargeSummary>,
        val driveAggregates: Map<Int, DriveDetailAggregate>,
        val chargeAggregates: Map<Int, ChargeDetailAggregate>,
        val syncStates: Map<Int, SyncState>,
        val trips: List<SavedTripWithLegs>,
        val fingerprints: Map<Long, List<String>>,
        val overrides: List<ChargeCostOverride>,
        val archive: LegacyHistoryArchive,
        val pointIdentities: List<HistoryDetailIdentity>,
        val pointDetail: ChargeDetail
    )

    private suspend fun seed(f: HistoryResourceFixture): Seeded {
        val scope = f.vehicleContexts.captureReadScope()
        val current = f.vehicleContexts.resolve(CarData(7), scope)
        val otherVehicle = requireNotNull(f.vehicleContexts.cachedContextForRemote(2, scope))
        val otherOrigin = f.vehicleContexts.resolve(CarData(7), scope.copy(
            serverIdentity = secondServer, effectiveApiOrigin = secondServer))
        val carIds = listOf(current.localHistoryCarId, otherVehicle.localHistoryCarId,
            otherOrigin.localHistoryCarId, 7)
        assertEquals(4, carIds.distinct().size)
        val driveData = DriveData(11, startDate = start, endDate = end,
            odometerDetails = DriveOdometerDetails(distance = 1.0), lengthUnit = "km",
            source = "teslamate_archive", sourceInstanceId = "archive-fixture", qualityState = "observed")
        val chargeData = ChargeData(22, startDate = start, endDate = end, chargeEnergyAdded = 2.0,
            source = "teslamate_archive", sourceInstanceId = "archive-fixture", qualityState = "observed")
        val driveDetail = DriveDetail(11, startDate = start, endDate = end,
            source = "teslamate_archive", sourceInstanceId = "archive-fixture", positions = emptyList())
        val chargeDetail = ChargeDetail(22, startDate = start, endDate = end,
            source = "teslamate_archive", sourceInstanceId = "archive-fixture", chargeEnergyAdded = 2.0,
            chargePoints = listOf(ChargePoint(date = start, chargeEnergyAdded = 0.0),
                ChargePoint(date = end, chargeEnergyAdded = 2.0)))
        val drives = carIds.associateWith { requireNotNull(driveData.toSyncSummary(it)) }
        val charges = carIds.associateWith { requireNotNull(chargeData.toSyncSummary(it)) }
        f.db.driveSummaryDao().upsertAll(drives.values.toList())
        f.db.chargeSummaryDao().upsertAll(charges.values.toList())
        val driveAggregates = carIds.associateWith { driveDetail.toAggregate(it, 1L) }
        val chargeAggregates = carIds.associateWith { chargeDetail.toAggregate(it, 1L) }
        f.db.aggregateDao().upsertDriveAggregates(driveAggregates.values.toList())
        f.db.aggregateDao().upsertChargeAggregates(chargeAggregates.values.toList())
        val syncStates = carIds.associateWith { SyncState(it, lastDriveSyncAt = 1L,
            lastChargeSyncAt = 1L, lastDriveDetailId = 11, lastChargeDetailId = 22,
            summariesSynced = true, detailsSynced = true) }
        syncStates.values.forEach { f.db.syncStateDao().upsert(it) }
        val trips = carIds.map { car ->
            val id = f.db.savedTripDao().insertTripWithLegs(SavedTrip(carId = car,
                name = "Synthetic owner trip $car", source = SavedTrip.SOURCE_USER_EDITED,
                createdAt = 1L, updatedAt = 1L)) { trip ->
                listOf(SavedTripLeg(trip, 0, SavedTripLeg.TYPE_DRIVE, 11),
                    SavedTripLeg(trip, 1, SavedTripLeg.TYPE_CHARGE, 22))
            }
            f.db.savedTripDao().insertConsumedFingerprints(listOf(SavedTripConsumedFingerprint(id, "fixture-$car")))
            requireNotNull(f.db.savedTripDao().getWithLegs(id))
        }
        val fingerprints = trips.associate { it.trip.id to f.db.savedTripDao().getConsumedFingerprints(it.trip.id) }
        val overrides = carIds.map { ChargeCostOverride(it, 22, 4.5,
            sessionKey = "fixture-session", currencyCode = "CNY", sourceKind = "teslamate_archive",
            sourceInstanceId = "archive-fixture", startInstant = start, endInstant = end) }
        overrides.forEach { f.db.chargeCostOverrideDao().upsert(it) }
        val archive = LegacyHistoryArchive(7, "unlinked-fixture-vehicle", "3",
            LegacyHistoryArchive.EXPLICIT_UPGRADE_ARCHIVE, 1L)
        f.db.legacyHistoryArchiveDao().recordExplicitUpgradeOrigin(archive)
        val pointStore = HistoryDetailRepository(f.db.historyDetailDao())
        val pointIdentities = listOf(current, otherOrigin).map { vehicle ->
            HistoryDetailIdentity.create(vehicle, HistoryDetailIdentity.CHARGE, 22,
                "teslamate_archive", "archive-fixture", start, end).also { identity ->
                assertTrue(pointStore.saveCharge(identity, chargeDetail) is HistoryDetailWriteResult.Stored)
            }
        }
        return Seeded(current, drives, charges, driveAggregates, chargeAggregates, syncStates,
            trips, fingerprints, overrides, archive, pointIdentities, chargeDetail)
    }

    private suspend fun assertProtectedRows(f: HistoryResourceFixture, seeded: Seeded) {
        for (trip in seeded.trips) {
            assertEquals("An instance or cache reset must retain the user's trip and legs", trip,
                f.db.savedTripDao().getWithLegs(trip.trip.id))
            assertEquals(seeded.fingerprints.getValue(trip.trip.id),
                f.db.savedTripDao().getConsumedFingerprints(trip.trip.id))
        }
        assertEquals(seeded.overrides.toSet(), f.db.chargeCostOverrideDao().getAll().toSet())
        assertEquals(seeded.archive, f.db.legacyHistoryArchiveDao().get(7))
        for (identity in seeded.pointIdentities) {
            val points = HistoryDetailRepository(f.db.historyDetailDao()).readCharge(identity)
            assertEquals("Original point receipts must survive cache and instance actions", seeded.pointDetail, points.detail)
            assertEquals(2, points.receipt?.pointCount)
        }
    }

    private suspend fun assertCachedRows(f: HistoryResourceFixture, seeded: Seeded, ids: Collection<Int>) {
        for (id in ids) {
            assertEquals(seeded.drives.getValue(id), f.db.driveSummaryDao().get(id, 11))
            assertEquals(seeded.charges.getValue(id), f.db.chargeSummaryDao().get(id, 22))
            assertEquals(seeded.driveAggregates.getValue(id), f.db.aggregateDao().getDriveAggregate(id, 11))
            assertEquals(seeded.chargeAggregates.getValue(id), f.db.aggregateDao().getChargeAggregate(id, 22))
            assertEquals(seeded.syncStates.getValue(id), f.db.syncStateDao().get(id))
        }
    }

    private fun settingsVm(f: HistoryResourceFixture) = SettingsViewModel(f.context, f.settings, f.modes,
        SettingsRepository(f.context), f.apiRepo, f.syncManager,
        TpmsStateRepository(TpmsStateDataStore(HistoryMemoryPreferences()), f.vehicleContexts),
        SentryStateRepository(SentryStateDataStore(f.context), f.db.sentryAlertLogDao(), f.vehicleContexts),
        SentryNotificationManager(f.context), f.vehicleContexts)

    @Test fun forceResyncUsesTheVerifiedLocalKeyAndPreservesOtherNamespacesAndOwnerRows() = runBlocking(Dispatchers.IO) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = fixture("force-resync-scope")
        val seeded = seed(f)
        manager(f)
        val vm = settingsVm(f)
        try {
            withTimeout(10_000) { vm.uiState.first { !it.isLoading } }
            vm.forceResync()
            val result = withTimeout(10_000) { vm.uiState.first { !it.isResyncing &&
                (it.successMessage != null || it.error != null) } }
            assertNull(result.error)
            assertNotNull(result.successMessage)
            assertNull("Remote car 7 must reset its verified negative local key", f.db.driveSummaryDao().get(seeded.current.localHistoryCarId, 11))
            assertNull(f.db.chargeSummaryDao().get(seeded.current.localHistoryCarId, 22))
            assertNull(f.db.aggregateDao().getDriveAggregate(seeded.current.localHistoryCarId, 11))
            assertNull(f.db.aggregateDao().getChargeAggregate(seeded.current.localHistoryCarId, 22))
            assertFalse(requireNotNull(f.db.syncStateDao().get(seeded.current.localHistoryCarId)).summariesSynced)
            assertCachedRows(f, seeded, seeded.drives.keys - seeded.current.localHistoryCarId)
            assertProtectedRows(f, seeded)
        } finally { vm.viewModelScope.cancel(); f.close(); Dispatchers.resetMain() }
    }

    @Test fun scopedFullResetRetainsTripReferencesManualEditsArchivesAndOriginalPoints() = runBlocking(Dispatchers.IO) {
        val f = fixture("direct-scoped-reset")
        try {
            val seeded = seed(f)
            f.syncManager.fullResetSync(seeded.current.localHistoryCarId)
            assertNull(f.db.driveSummaryDao().get(seeded.current.localHistoryCarId, 11))
            assertNull(f.db.chargeSummaryDao().get(seeded.current.localHistoryCarId, 22))
            assertNull(f.db.aggregateDao().getDriveAggregate(seeded.current.localHistoryCarId, 11))
            assertNull(f.db.aggregateDao().getChargeAggregate(seeded.current.localHistoryCarId, 22))
            assertCachedRows(f, seeded, seeded.drives.keys - seeded.current.localHistoryCarId)
            assertProtectedRows(f, seeded)
        } finally { f.close() }
    }

    @Test fun scopeChangeDuringCarDiscoveryCannotResetAnyNamespaceOrReportSuccess() = runBlocking(Dispatchers.IO) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = fixture("resync-stale-source")
        val seeded = seed(f)
        manager(f)
        val vm = settingsVm(f)
        try {
            withTimeout(10_000) { vm.uiState.first { !it.isLoading } }
            val changed = AtomicBoolean()
            f.onRequest = { path ->
                if (path.endsWith("/cars") && changed.compareAndSet(false, true)) f.settings.saveServerUrl(secondServer)
            }
            vm.forceResync()
            val result = withTimeout(10_000) { vm.uiState.first { !it.isResyncing &&
                (it.successMessage != null || it.error != null) } }
            assertTrue(changed.get())
            assertCachedRows(f, seeded, seeded.drives.keys)
            assertProtectedRows(f, seeded)
            assertNull("A stale discovery result must not start resync for another source", result.successMessage)
        } finally { vm.viewModelScope.cancel(); f.close(); Dispatchers.resetMain() }
    }

    private suspend fun assertInstanceActionPreservesAllRows(deleteCurrent: Boolean) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = fixture(if (deleteCurrent) "delete-current-instance" else "switch-instance")
        val seeded = seed(f)
        manager(f)
        val secure = f.javaClass.getDeclaredField("secure").apply { isAccessible = true }.get(f) as SecureSettingsDataStore
        val instances = InstanceDataStore(f.context, secure)
        // The delegated instance store can survive another method in this Robolectric sandbox.
        // Remove only this class's synthetic records; never clear an owner or another fixture's store.
        instances.instances.first().filter { it.name.startsWith(instanceNamePrefix) &&
            it.serverUrl.startsWith(instanceServerPrefix) }.forEach { instances.removeInstance(it.id) }
        assertTrue("This test requires an isolated synthetic instance list", instances.instances.first().isEmpty())
        val first = instances.addInstance(Instance(name = "${instanceNamePrefix}A", serverUrl = firstServer, carId = 7), "fixture-A")
        val second = instances.addInstance(Instance(name = "${instanceNamePrefix}B", serverUrl = secondServer, carId = 7), "fixture-B")
        assertEquals(setOf(first.id, second.id), instances.instances.first().map { it.id }.toSet())
        instances.setActiveInstance(first.id)
        val vm = InstanceViewModel(f.context, instances, f.settings, f.db, f.apiRepo)
        try {
            withTimeout(10_000) { vm.uiState.first { !it.isLoading && it.activeInstanceId == first.id } }
            val scopeJob = requireNotNull(vm.viewModelScope.coroutineContext[Job])
            val previousJobs = scopeJob.children.toSet()
            if (deleteCurrent) vm.deleteInstance(first.id) else vm.switchInstance(second.id)
            val operationJobs = scopeJob.children.filter { it !in previousJobs }.toList()
            withTimeout(10_000) { operationJobs.forEach { it.join() } }
            assertEquals(second.id, instances.getActiveInstanceId())
            val settings = f.settings.settings.first()
            assertEquals("The completed operation must apply the new active source", secondServer, settings.serverUrl)
            assertEquals("The completed operation must apply that instance's token", "fixture-B", settings.apiToken)
            if (deleteCurrent) assertTrue(instances.instances.first().none { it.id == first.id })
            assertCachedRows(f, seeded, seeded.drives.keys)
            assertProtectedRows(f, seeded)
        } finally {
            vm.viewModelScope.cancel()
            instances.removeInstance(first.id)
            instances.removeInstance(second.id)
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun switchingInstancePreservesOtherSourcesAndOwnerRoomRecords() = runBlocking(Dispatchers.IO) {
        assertInstanceActionPreservesAllRows(deleteCurrent = false)
    }

    @Test fun deletingCurrentInstancePreservesOtherSourcesAndOwnerRoomRecords() = runBlocking(Dispatchers.IO) {
        assertInstanceActionPreservesAllRows(deleteCurrent = true)
    }
}

package com.matelink.ui.screens.timeline

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.HistoryContextData
import com.matelink.data.local.ChargeCostOverrideStore
import com.matelink.data.local.ConnectionMode
import com.matelink.data.local.dao.ChargeCostOverrideDao
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.HistoryReadDependencies
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual VM, repository and Room; only the local manual-price read can fail. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ExternalReviewTimelineRecoveryTest {
    @get:Rule val temp = TemporaryFolder()

    private suspend fun withTimeline(block: suspend (HistoryResourceFixture, TimelineViewModel, () -> Unit) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val fixture = HistoryResourceFixture(File(temp.root, "timeline-${System.nanoTime()}.db"))
        var vm: TimelineViewModel? = null
        try {
            fixture.initialize()
            fixture.detailResponse = { path ->
                if (path.endsWith("/drives")) """{"data":{"drives":[{"drive_id":31,"start_date":"2026-10-14T00:00:00Z","end_date":"2026-10-14T00:10:00Z","source":"teslamate_archive","source_instance_id":"fixture","source_id":"drive-31","quality_state":"observed","odometer_details":{"distance":8.0}}]}}"""
                else null
            }
            val selection = SettingsRepository(fixture.context)
            selection.setMockMode(false)
            selection.setCurrentCarId(1)
            var failCosts = false
            val actualDao = fixture.db.chargeCostOverrideDao()
            val prices = Proxy.newProxyInstance(ChargeCostOverrideDao::class.java.classLoader,
                arrayOf(ChargeCostOverrideDao::class.java)) { _, method, args ->
                if (method.name == "getAll" && failCosts) throw IOException("synthetic local read failure")
                method.invoke(actualDao, *(args ?: emptyArray()))
            } as ChargeCostOverrideDao
            val model = TimelineViewModel(fixture.context,
                UnifiedHistoryRepository(fixture.apiRepo, fixture.vehicleContexts,
                    fixture.db.driveSummaryDao(), fixture.db.chargeSummaryDao()),
                selection, fixture.settings, ChargeCostOverrideStore(prices, fixture.settings),
                fixture.vehicleContexts)
            vm = model
            withTimeout(10_000) { model.uiState.first { !it.isLoading && it.events.isNotEmpty() } }
            block(fixture, model) { failCosts = true }
        } finally {
            vm?.viewModelScope?.cancel()
            fixture.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun sameVerifiedVehicleRefreshFailureRetainsThePublishedTimeline() = runBlocking {
        withTimeline { _, vm, failCosts ->
            val before = vm.uiState.value.events
            assertEquals(31, (before.single() as TimelineEvent.DriveEvent).driveId)
            failCosts()
            vm.refresh()
            val failed = withTimeout(10_000) { vm.uiState.first { !it.isLoading } }
            assertEquals("A transient read failure must keep this verified car's existing timeline", before, failed.events)
            assertEquals(before.groupBy { it.date }, failed.groupedEvents)
            assertTrue("Read failures must keep a user-visible reason even without an exception message", !failed.error.isNullOrBlank())
        }
    }

    @Test fun accountScopeLossClearsPreviouslyPublishedTimelineEvenWhenTheNextReadFails() = runBlocking {
        withTimeline { fixture, vm, failCosts ->
            assertTrue(vm.uiState.value.events.isNotEmpty())
            failCosts()
            // No cloud login exists: resolving this scope must fail before any HTTP request.
            fixture.modes.set(ConnectionMode.TESLA_CLOUD)
            val failed = withTimeout(10_000) { vm.uiState.first { !it.isLoading && it.error != null } }
            assertTrue(failed.events.isEmpty())
            assertTrue(failed.groupedEvents.isEmpty())
        }
    }

    @Test fun anotherSelectedCarInTheSameScopeCannotRetainThePreviousCarsEventsOnHistoryFailure() = runBlocking {
        withIdentityTimeline("timeline-selected-car.db") { fixture, vm ->
            val before = vm.uiState.value.events
            val scope = fixture.resources.vehicleContexts.captureReadScope()
            assertEquals(31, (before.single() as TimelineEvent.DriveEvent).driveId)
            fixture.failHistory = true

            fixture.selection.setCurrentCarId(2)
            val failed = withTimeout(10_000) {
                vm.uiState.first { !it.isLoading && it.error != null }
            }

            assertEquals(scope, fixture.resources.vehicleContexts.captureReadScope())
            assertEquals(2, fixture.selection.currentCarId.first())
            assertTrue("The new selected car's history request must actually fail", 2 in fixture.historyRequests)
            assertTrue("Car 2 must not display car 1's previously published events", failed.events.isEmpty())
            assertTrue(failed.groupedEvents.isEmpty())
        }
    }

    @Test fun reboundUidWithTheSameRemoteCarIdCannotRetainOldEventsOnHistoryFailure() = runBlocking {
        withIdentityTimeline("timeline-rebound-history.db") { fixture, vm ->
            val scope = fixture.resources.vehicleContexts.captureReadScope()
            val oldLocalCar = fixture.resources.localId(1)
            fixture.vehicleUids[1] = "synthetic-rebound-vehicle"
            fixture.failHistory = true

            vm.refresh()
            val failed = withTimeout(10_000) {
                vm.uiState.first { !it.isLoading && it.error != null }
            }

            assertEquals(scope, fixture.resources.vehicleContexts.captureReadScope())
            assertEquals(1, fixture.selection.currentCarId.first())
            assertTrue("Authenticated UID rebinding must create a different local history namespace",
                oldLocalCar != fixture.resources.localId(1))
            assertTrue("A new UID cannot inherit the old UID's timeline after failed history", failed.events.isEmpty())
            assertTrue(failed.groupedEvents.isEmpty())
            assertEquals(listOf(31), fixture.resources.db.driveSummaryDao().getAllChronological(oldLocalCar)
                .map { it.driveId })
        }
    }

    @Test fun reboundUidCannotRetainOldEventsWhenItsSuccessfulHistoryIsFollowedByCostReadFailure() = runBlocking {
        withIdentityTimeline("timeline-rebound-costs.db") { fixture, vm ->
            val scope = fixture.resources.vehicleContexts.captureReadScope()
            val oldLocalCar = fixture.resources.localId(1)
            fixture.vehicleUids[1] = "synthetic-rebound-vehicle"
            fixture.failCosts = true

            vm.refresh()
            val failed = withTimeout(10_000) {
                vm.uiState.first { !it.isLoading && it.error != null }
            }

            val newLocalCar = fixture.resources.localId(1)
            assertEquals(scope, fixture.resources.vehicleContexts.captureReadScope())
            assertTrue(oldLocalCar != newLocalCar)
            assertEquals("The new UID's real history must reach its own Room namespace before costs fail",
                listOf(101), fixture.resources.db.driveSummaryDao().getAllChronological(newLocalCar).map { it.driveId })
            assertTrue("Cost failure for UID B must not restore UID A's events", failed.events.isEmpty())
            assertTrue(failed.groupedEvents.isEmpty())
        }
    }

    private suspend fun withIdentityTimeline(
        databaseName: String,
        block: suspend (IdentityTimelineFixture, TimelineViewModel) -> Unit
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val fixture = IdentityTimelineFixture(File(temp.root, databaseName))
        var vm: TimelineViewModel? = null
        try {
            fixture.initialize()
            val model = TimelineViewModel(fixture.resources.context, fixture.repository(), fixture.selection,
                fixture.resources.settings, fixture.costs(), fixture.resources.vehicleContexts)
            vm = model
            withTimeout(10_000) {
                model.uiState.first { !it.isLoading && it.events.isNotEmpty() && it.error == null }
            }
            block(fixture, model)
        } finally {
            vm?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            fixture.resources.close()
            Dispatchers.resetMain()
        }
    }

    /** Actual vehicle namespaces and disk Room, with local deterministic provider response ports. */
    private class IdentityTimelineFixture(database: File) {
        val resources = HistoryResourceFixture(database)
        val selection = SettingsRepository(resources.context)
        val vehicleUids = mutableMapOf(1 to "synthetic-vehicle-1", 2 to "synthetic-vehicle-2")
        val historyRequests = mutableListOf<Int>()
        var failHistory = false
        var failCosts = false

        suspend fun initialize() {
            resources.initialize(cloud = true)
            selection.setMockMode(false)
            selection.setCurrentCarId(1)
        }

        fun repository() = UnifiedHistoryRepository(HistoryReadDependencies(
            captureScope = { resources.vehicleContexts.captureReadScope() },
            getCars = { ApiResult.Success(vehicleUids.map { (id, uid) -> CarData(id, vehicleUid = uid) }) },
            getHistoryContext = { id -> ApiResult.Success(HistoryContextData(1, id, vehicleUids.getValue(id))) },
            resolveCar = { car, scope -> resources.vehicleContexts.resolveVerifiedHistoryCar(car, scope) },
            cachedContext = { id, scope -> resources.vehicleContexts.cachedVerifiedHistoryContext(id, scope) },
            localDrives = { resources.db.driveSummaryDao().getAllChronological(it) },
            localCharges = { resources.db.chargeSummaryDao().getAllForCar(it) },
            getDrives = { id, _, _, page ->
                check(page == 1)
                historyRequests += id
                if (failHistory) ApiResult.Error("synthetic_history_failure", 503)
                else {
                    val original = id == 1 && vehicleUids.getValue(id) == "synthetic-vehicle-1"
                    ApiResult.Success(listOf(DriveData(if (original) 31 else 100 + id,
                        startDate = if (original) "2026-10-27T00:00:00Z" else "2026-10-28T00:00:00Z",
                        endDate = if (original) "2026-10-27T00:10:00Z" else "2026-10-28T00:10:00Z",
                        durationMin = 10, odometerDetails = DriveOdometerDetails(distance = 8.0), lengthUnit = "km",
                        source = "teslamate_archive", sourceInstanceId = vehicleUids.getValue(id),
                        sourceRecordId = "synthetic-timeline-$id", qualityState = "observed")))
                }
            },
            getCharges = { _, _, _, page ->
                check(page == 1)
                if (failHistory) ApiResult.Error("synthetic_history_failure", 503) else ApiResult.Success(emptyList())
            },
            persistDrives = { resources.db.driveSummaryDao().upsertPreservingEvidence(it) },
            persistCharges = { resources.db.chargeSummaryDao().upsertPreservingEvidence(it) }
        ))

        fun costs(): ChargeCostOverrideStore {
            val actualDao = resources.db.chargeCostOverrideDao()
            val prices = Proxy.newProxyInstance(ChargeCostOverrideDao::class.java.classLoader,
                arrayOf(ChargeCostOverrideDao::class.java)) { _, method, args ->
                if (method.name == "getAll" && failCosts) throw IOException("synthetic_local_cost_read_failure")
                method.invoke(actualDao, *(args ?: emptyArray()))
            } as ChargeCostOverrideDao
            return ChargeCostOverrideStore(prices, resources.settings)
        }
    }
}

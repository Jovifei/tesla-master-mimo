package com.matelink.ui.screens.battery

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveRange
import com.matelink.data.local.ConnectionMode
import com.matelink.data.local.VehicleStatusStore
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.api.TeslamateApi
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.BatteryTrendSource
import com.squareup.moshi.Moshi
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class BatteryRoomRetentionConsumerTest {
    @get:Rule val temp = TemporaryFolder()

    private class ApiValues {
        val uid = AtomicReference("battery-vehicle-A")
        val label = AtomicReference("battery-vehicle-A")
        val capacity = AtomicReference(75.0)
    }

    private suspend fun fixture(name: String, api: ApiValues): HistoryResourceFixture {
        val f = HistoryResourceFixture(File(temp.root, "$name.db"))
        f.initialize(cloud = true)
        SettingsRepository(f.context).setMockMode(false)
        // Prepopulate the self-hosted cache key before a VM can observe a source switch.
        @Suppress("UNCHECKED_CAST")
        val cache = f.apiFactory.javaClass.getDeclaredField("apiCache").apply { isAccessible = true }
            .get(f.apiFactory) as MutableMap<Any, TeslamateApi>
        val fake = cache.values.first()
        f.modes.set(ConnectionMode.SELF_HOSTED)
        f.apiFactory.create("https://synthetic.example")
        f.modes.set(ConnectionMode.TESLA_CLOUD)
        cache.keys.toList().forEach { cache[it] = fake }
        f.sessions.save("synthetic-access", "synthetic-refresh", 3600, "battery-$name-${temp.root.name}")
        val scope = f.vehicleContexts.captureReadScope()
        f.vehicleContexts.resolve(CarData(1, vehicleUid = api.uid.get()), scope)
        f.vehicleContexts.resolve(CarData(2, vehicleUid = "battery-vehicle-B"), scope)
        f.detailResponse = { path -> response(path, api) }
        return f
    }

    private fun response(path: String, api: ApiValues): String? = when {
        path.endsWith("/history-context") -> {
            val id = path.substringBeforeLast('/').substringAfterLast('/').toInt()
            val uid = if (id == 1) api.uid.get() else "battery-vehicle-B"
            """{"data":{"capability_version":1,"car_id":$id,"vehicle_uid":"$uid"}}"""
        }
        path.endsWith("/battery-health") ->
            """{"data":{"battery_health":{"max_capacity":${api.capacity.get()},"current_capacity":60}}}"""
        path.endsWith("/status") ->
            """{"data":{"status":{"display_name":"${api.label.get()}","battery_details":{"battery_level":70}},"units":{"unit_of_length":"km","unit_of_temperature":"C"}}}"""
        path.endsWith("/drives") -> """{"data":{"drives":[]}}"""
        path.endsWith("/charges") -> """{"data":{"charges":[]}}"""
        else -> null
    }

    private fun page(f: HistoryResourceFixture): BatteryViewModel {
        val history = UnifiedHistoryRepository(f.apiRepo, f.vehicleContexts,
            f.db.driveSummaryDao(), f.db.chargeSummaryDao())
        return BatteryViewModel(f.apiRepo, AnalysisHistoryRepository(history, f.vehicleContexts),
            f.vehicleContexts, VehicleStatusStore(f.context, Moshi.Builder().build()))
    }

    private fun sample(id: Int): DriveData {
        val start = Instant.parse("2026-07-01T00:00:00Z").plusSeconds((id - 1L) * 3L * 86_400L)
        return DriveData(id, startDate = start.toString(), endDate = start.plusSeconds(600).toString(),
            durationMin = 10, source = "teslamate_archive", sourceInstanceId = "battery-fixture",
            sourceRecordId = "battery-sample-$id", qualityState = "observed", lengthUnit = "km",
            temperatureUnit = "C", outsideTempAvg = 22.0,
            batteryDetails = DriveBatteryDetails(startBatteryLevel = 80, endBatteryLevel = 79),
            rangeRated = DriveRange(startRange = 320.0 - id / 10.0, endRange = 316.0))
    }

    private suspend fun seedTrend(f: HistoryResourceFixture, car: Int = 1) {
        val local = f.vehicleContexts.cachedContextForRemote(car)!!.localHistoryCarId
        f.db.driveSummaryDao().upsertAll((1..12).map { sample(it).toSyncSummary(local)!! })
    }

    private suspend fun awaitComplete(page: BatteryViewModel): BatteryUiState = withTimeout(20_000) {
        page.uiState.first { !it.isLoading && !it.isRefreshing && it.carStatus != null }
    }

    @Test fun sameVehicleEmptyAndSparseRefreshKeepsUsableTrendAsCached() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = fixture("empty-sparse", ApiValues())
        var page: BatteryViewModel? = null
        try {
            seedTrend(f)
            val vm = page(f); page = vm
            vm.setCarId(1)
            val initial = withTimeout(20_000) { vm.uiState.first {
                !it.isLoading && it.batteryTrend?.source != null &&
                    it.batteryTrend?.source != BatteryTrendSource.UNAVAILABLE && it.carStatus != null
            } }
            val original = initial.batteryTrend!!
            assertEquals(12, original.sampleCount)
            val local = f.vehicleContexts.cachedContextForRemote(1)!!.localHistoryCarId
            f.db.driveSummaryDao().deleteAllForCar(local)
            val charge = ChargeData(50, startDate = "2026-08-01T00:00:00Z",
                endDate = "2026-08-01T01:00:00Z", source = "teslamate_archive", qualityState = "observed")
            f.db.chargeSummaryDao().upsert(charge.toSyncSummary(local)!!)
            vm.refresh()
            val empty = awaitComplete(vm)
            assertEquals(original, empty.batteryTrend)
            assertTrue(empty.isCachedTrend)

            f.db.driveSummaryDao().upsertAll((1..3).map { sample(it).toSyncSummary(local)!! })
            vm.refresh()
            val sparse = awaitComplete(vm)
            assertEquals(original, sparse.batteryTrend)
            assertTrue(sparse.isCachedTrend)

            f.db.driveSummaryDao().upsertAll((1..13).map { sample(it).toSyncSummary(local)!! })
            vm.refresh()
            val improved = withTimeout(20_000) { vm.uiState.first {
                !it.isRefreshing && it.batteryTrend?.sampleCount == 13
            } }
            assertEquals(13, improved.batteryTrend!!.sampleCount)
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close(); Dispatchers.resetMain()
        }
    }

    @Test fun remoteFailureDoesNotEraseSameVehicleTrend() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = fixture("remote-failure", ApiValues())
        var page: BatteryViewModel? = null
        try {
            seedTrend(f)
            val vm = page(f); page = vm
            vm.setCarId(1)
            val initial = withTimeout(20_000) { vm.uiState.first {
                !it.isLoading && it.batteryTrend?.source != null &&
                    it.batteryTrend?.source != BatteryTrendSource.UNAVAILABLE && it.carStatus != null
            } }
            f.status = { path ->
                if (path.endsWith("/drives") || path.endsWith("/status") || path.endsWith("/battery-health")) 503 else 200
            }
            vm.refresh()
            val failed = awaitComplete(vm)
            assertEquals(initial.batteryTrend, failed.batteryTrend)
            assertTrue(failed.isCachedTrend)
            assertEquals(initial.carStatus, failed.carStatus)
            assertTrue(failed.isCachedStatus)
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close(); Dispatchers.resetMain()
        }
    }

    @Test fun emptyRefreshDoesNotEraseAlreadyObservedInsufficientSamples() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val f = fixture("insufficient", ApiValues())
        var page: BatteryViewModel? = null
        try {
            val local = f.vehicleContexts.cachedContextForRemote(1)!!.localHistoryCarId
            f.db.driveSummaryDao().upsertAll((1..3).map { sample(it).toSyncSummary(local)!! })
            val charge = ChargeData(50, startDate = "2026-08-01T00:00:00Z",
                endDate = "2026-08-01T01:00:00Z", source = "teslamate_archive", qualityState = "observed")
            f.db.chargeSummaryDao().upsert(charge.toSyncSummary(local)!!)
            val vm = page(f); page = vm
            vm.setCarId(1)
            val initial = withTimeout(20_000) { vm.uiState.first {
                !it.isLoading && it.carStatus != null && it.batteryTrend?.sampleCount == 3
            } }
            assertEquals(BatteryTrendSource.UNAVAILABLE, initial.batteryTrend!!.source)
            f.db.driveSummaryDao().deleteAllForCar(local)
            vm.refresh()
            val result = awaitComplete(vm)
            assertEquals(3, result.batteryTrend!!.sampleCount)
            assertTrue(result.isCachedTrend)
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close(); Dispatchers.resetMain()
        }
    }

    @Test fun switchingCarAndSourceDoesNotCarryOldVehicleTrend() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val api = ApiValues()
        val f = fixture("car-source", api)
        var page: BatteryViewModel? = null
        try {
            seedTrend(f)
            val vm = page(f); page = vm
            vm.setCarId(1)
            val before = withTimeout(20_000) { vm.uiState.first { it.batteryTrend?.sampleCount == 12 } }
            api.label.set("battery-vehicle-B")
            vm.setCarId(2)
            val carB = withTimeout(20_000) { vm.uiState.first {
                !it.isLoading && it.carStatus?.displayName == "battery-vehicle-B" &&
                    it.batteryTrend?.source == BatteryTrendSource.UNAVAILABLE
            } }
            assertNotEquals(before.batteryTrend, carB.batteryTrend)
            assertEquals(0, carB.batteryTrend!!.sampleCount)

            api.label.set("battery-self-hosted")
            f.modes.set(ConnectionMode.SELF_HOSTED)
            val local = withTimeout(20_000) { vm.uiState.first {
                !it.isLoading && it.carStatus?.displayName == "battery-self-hosted" &&
                    it.batteryTrend?.source == BatteryTrendSource.UNAVAILABLE
            } }
            assertEquals(0, local.batteryTrend!!.sampleCount)
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close(); Dispatchers.resetMain()
        }
    }

    @Test fun sameNumericIdUidRemapClearsOldPageAndRejectsLateResponse() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val api = ApiValues()
        val f = fixture("uid-remap", api)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lateBuilt = CompletableDeferred<Unit>()
        val gateOnce = AtomicBoolean(true)
        val oldThread = AtomicReference<Thread?>(null)
        var page: BatteryViewModel? = null
        try {
            seedTrend(f)
            val vm = page(f); page = vm
            vm.setCarId(1)
            withTimeout(20_000) { vm.uiState.first { it.batteryTrend?.sampleCount == 12 && it.carStatus != null } }
            f.onRequest = { path ->
                if (path.endsWith("/battery-health") && gateOnce.compareAndSet(true, false)) {
                    oldThread.set(Thread.currentThread())
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            f.detailResponse = { path ->
                if (path.endsWith("/battery-health")) {
                    val value = if (Thread.currentThread() === oldThread.get())
                        41.0.also { lateBuilt.complete(Unit) } else 82.0
                    """{"data":{"battery_health":{"max_capacity":$value,"current_capacity":60}}}"""
                } else response(path, api)
            }
            vm.refresh()
            withTimeout(10_000) { entered.await() }
            val scope = f.vehicleContexts.captureReadScope()
            api.uid.set("battery-vehicle-remapped")
            api.label.set("battery-vehicle-remapped")
            f.vehicleContexts.resolve(CarData(1, vehicleUid = api.uid.get()), scope)
            vm.refresh()
            assertNotEquals(12, vm.uiState.value.batteryTrend?.sampleCount)
            val current = withTimeout(20_000) { vm.uiState.first {
                !it.isLoading && it.carStatus?.displayName == "battery-vehicle-remapped" &&
                    it.batteryHealth?.maxCapacity == 82.0
            } }
            assertTrue(current.batteryTrend == null || current.batteryTrend?.source == BatteryTrendSource.UNAVAILABLE)
            release.complete(Unit)
            withTimeout(10_000) { lateBuilt.await() }
            assertEquals("battery-vehicle-remapped", vm.uiState.value.carStatus?.displayName)
            assertEquals(82.0, vm.uiState.value.batteryHealth!!.maxCapacity!!, 0.0)
            assertNotEquals(12, vm.uiState.value.batteryTrend?.sampleCount)
        } finally {
            release.complete(Unit)
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close(); Dispatchers.resetMain()
        }
    }

    @Test fun uidRemapDuringRequestReactivatesWithoutAnotherRefresh() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val api = ApiValues()
        val f = fixture("uid-remap-inflight", api)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gateOnce = AtomicBoolean(true)
        var page: BatteryViewModel? = null
        try {
            seedTrend(f)
            val vm = page(f); page = vm
            vm.setCarId(1)
            withTimeout(20_000) { vm.uiState.first { it.batteryTrend?.sampleCount == 12 && it.carStatus != null } }
            f.onRequest = { path ->
                if (path.endsWith("/battery-health") && gateOnce.compareAndSet(true, false)) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            vm.refresh()
            withTimeout(10_000) { entered.await() }
            val scope = f.vehicleContexts.captureReadScope()
            api.uid.set("battery-vehicle-inflight-B")
            api.label.set("battery-vehicle-inflight-B")
            f.vehicleContexts.resolve(CarData(1, vehicleUid = api.uid.get()), scope)
            release.complete(Unit)
            val current = withTimeout(20_000) { vm.uiState.first {
                !it.isLoading && it.carStatus?.displayName == "battery-vehicle-inflight-B" &&
                    it.batteryTrend?.source == BatteryTrendSource.UNAVAILABLE
            } }
            assertEquals(0, current.batteryTrend!!.sampleCount)
        } finally {
            release.complete(Unit)
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close(); Dispatchers.resetMain()
        }
    }

    @Test fun switchingAccountDoesNotCarryOldTrend() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val api = ApiValues()
        val f = fixture("account", api)
        var page: BatteryViewModel? = null
        try {
            seedTrend(f)
            val vm = page(f); page = vm
            vm.setCarId(1)
            withTimeout(20_000) { vm.uiState.first { it.batteryTrend?.sampleCount == 12 } }
            api.label.set("battery-account-B")
            f.sessions.save("synthetic-access", "synthetic-refresh", 3600, "battery-account-B")
            val current = withTimeout(20_000) { vm.uiState.first {
                !it.isLoading && it.carStatus?.displayName == "battery-account-B" &&
                    it.batteryTrend?.source == BatteryTrendSource.UNAVAILABLE
            } }
            assertEquals(0, current.batteryTrend!!.sampleCount)
        } finally {
            page?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close(); Dispatchers.resetMain()
        }
    }
}

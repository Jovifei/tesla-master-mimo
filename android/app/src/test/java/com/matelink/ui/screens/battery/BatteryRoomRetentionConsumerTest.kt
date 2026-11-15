package com.matelink.ui.screens.battery

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.DriveRange
import com.matelink.data.local.ConnectionMode
import com.matelink.data.local.VehicleStatusStore
import com.matelink.data.repository.ApiErrorKind
import com.matelink.data.repository.ApiResult
import com.matelink.data.repository.CarStatusWithUnits
import com.matelink.data.repository.SettingsRepository
import com.matelink.data.api.TeslamateApi
import com.matelink.data.repository.UnifiedHistoryRepository
import com.matelink.data.sync.HistoryResourceFixture
import com.matelink.data.sync.toSyncSummary
import com.matelink.domain.analytics.AnalysisHistoryRepository
import com.matelink.domain.analytics.AnalysisHistorySnapshot
import com.matelink.domain.analytics.HistoryCoverage
import com.matelink.domain.analytics.PersonalizedRangeSource
import com.matelink.ui.screens.drives.capacityMetadata
import com.matelink.ui.screens.range.RangePageIdentity
import com.matelink.ui.screens.range.RangePageSource
import com.matelink.ui.screens.range.RangeViewModel
import com.matelink.domain.analytics.BatteryTrendSource
import com.squareup.moshi.Moshi
import java.io.File
import java.net.ConnectException
import java.time.LocalDate
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
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

    @Test fun retrofitHealthDistinguishesExplicitAbsenceFromMalformedAndRealFailures() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val api = ApiValues()
        val f = fixture("health-contract", api)
        try {
            val body = AtomicReference("")
            f.detailResponse = { path ->
                if (path.endsWith("/battery-health")) body.get() else response(path, api)
            }

            for (availability in listOf("unsupported", "collecting")) {
                body.set("""{"data":{"battery_health":null,"meta":{"availability":"$availability","source":"fleet_api"}}}""")
                val result = f.apiRepo.getBatteryHealth(1) as ApiResult.Success
                assertEquals(availability, result.metadata?.availability)
                assertEquals("fleet_api", result.metadata?.source)
                assertNull(result.data.currentCapacity)
                assertNull(result.data.maxCapacity)
            }

            for (invalid in listOf(
                """{"data":{"battery_health":null}}""",
                """{"data":{"battery_health":null,"meta":{"availability":"available"}}}""",
                """{"data":{"battery_health":null,"meta":{"availability":"unknown"}}}"""
            )) {
                body.set(invalid)
                assertTrue(f.apiRepo.getBatteryHealth(1) is ApiResult.Error)
            }

            body.set("""{"data":{"battery_health":{"max_capacity":75,"current_capacity":0},"meta":{"availability":"available"}}}""")
            val observedZero = f.apiRepo.getBatteryHealth(1) as ApiResult.Success
            assertEquals(0.0, observedZero.data.currentCapacity!!, 0.0)
            body.set("""{"data":{"battery_health":{"max_capacity":75,"current_capacity":70},"meta":{"availability":"available"}}}""")
            val measured = f.apiRepo.getBatteryHealth(1) as ApiResult.Success
            assertEquals(70.0, measured.data.currentCapacity!!, 0.0)

            for ((code, expected) in listOf(401 to ApiErrorKind.AUTH_REQUIRED, 500 to ApiErrorKind.SERVICE_UNAVAILABLE)) {
                f.status = { path -> if (path.endsWith("/battery-health")) code else 200 }
                val failure = f.apiRepo.getBatteryHealth(1) as ApiResult.Error
                assertEquals(code, failure.code)
                assertEquals(expected, failure.kind)
            }
            f.status = { 200 }
            f.onRequest = { path -> if (path.endsWith("/battery-health")) throw ConnectException("synthetic offline") }
            assertEquals(ApiErrorKind.NETWORK, (f.apiRepo.getBatteryHealth(1) as ApiResult.Error).kind)
        } finally {
            f.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun declaredAbsentHealthKeepsRealBatteryTrendAndHasNoError() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            for ((availability, expected) in listOf(
                "unsupported" to BatteryHealthAvailability.UNSUPPORTED,
                "collecting" to BatteryHealthAvailability.COLLECTING
            )) {
                val api = ApiValues()
                val f = fixture("health-vm-$availability", api)
                var vm: BatteryViewModel? = null
                try {
                    f.detailResponse = { path ->
                        if (path.endsWith("/battery-health"))
                            """{"data":{"battery_health":null,"meta":{"availability":"$availability","source":"fleet_api"}}}"""
                        else response(path, api)
                    }
                    seedTrend(f)
                    val page = page(f); vm = page
                    page.setCarId(1)
                    val state = withTimeout(20_000) { page.uiState.first {
                        !it.isLoading && it.carStatus != null && it.batteryTrend?.sampleCount == 12 &&
                            it.batteryHealthAvailability == expected
                    } }
                    assertNull(state.error)
                    assertNull(state.batteryHealth?.currentCapacity)
                    assertNull(state.batteryHealth?.maxCapacity)
                    assertFalse(page.computeStats()!!.hasCapacityEstimate)
                    assertEquals(12, state.batteryTrend!!.sampleCount)
                } finally {
                    vm?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                    f.close()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun realBatteryVmKeepsTransportFailuresVisibleEvenWhenReadinessSaysUnsupported() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            for ((label, code) in listOf("auth" to 401, "server" to 500, "network" to 0)) {
                val api = ApiValues()
                val f = fixture("health-failure-$label", api)
                var vm: BatteryViewModel? = null
                try {
                    f.detailResponse = { path -> when {
                        path.endsWith("/data-readiness") ->
                            """{"data":{"items":[{"key":"battery_health","status":"unsupported","source":"fleet_api"}]}}"""
                        else -> response(path, api)
                    } }
                    f.status = { path -> if (code != 0 && path.endsWith("/battery-health")) code else 200 }
                    if (code == 0) f.onRequest = { path ->
                        if (path.endsWith("/battery-health")) throw ConnectException("synthetic offline")
                    }
                    val page = page(f); vm = page
                    page.setCarId(1)
                    val state = withTimeout(20_000) { page.uiState.first {
                        !it.isLoading && it.dataReadiness != null && it.error != null
                    } }
                    assertEquals(BatteryHealthAvailability.UNSUPPORTED, state.batteryHealthAvailability)
                    assertNotNull(state.error)
                } finally {
                    vm?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                    f.close()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun absentHealthCannotBecomeRangeOrDriveCapacity() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val api = ApiValues()
        val f = fixture("health-consumers", api)
        var vm: RangeViewModel? = null
        try {
            val healthBody = AtomicReference(
                """{"data":{"battery_health":null,"meta":{"availability":"unsupported","source":"fleet_api"}}}"""
            )
            f.detailResponse = { path ->
                if (path.endsWith("/battery-health")) healthBody.get() else response(path, api)
            }
            val health = f.apiRepo.getBatteryHealth(1) as ApiResult.Success
            val unified = UnifiedHistoryRepository(f.apiRepo, f.vehicleContexts,
                f.db.driveSummaryDao(), f.db.chargeSummaryDao())
            val proof = (unified.resolveContext(1) as ApiResult.Success).data
            val car = CarData(1, vehicleUid = api.uid.get())
            assertNull(proof.capacityMetadata(ApiResult.Success(listOf(car)), health)!!.reportedCapacity)

            val drives = (1..10).map { id ->
                val date = LocalDate.now().minusDays(id.toLong()).toString()
                DriveData(id, startDate = date, endDate = date,
                    odometerDetails = DriveOdometerDetails(distance = 40.0),
                    rangeRated = DriveRange(100.0, 55.0), energyConsumedNet = 8.0,
                    lengthUnit = "km", source = "teslamate_archive", qualityState = "observed")
            }
            val snapshot = AnalysisHistorySnapshot(drives, emptyList(), Instant.now(),
                HistoryCoverage(drives.size, 0), context = proof.context)
            val healthObserved = CompletableDeferred<Unit>()
            val source = object : RangePageSource {
                override val scopeChanges = emptyFlow<Unit>()
                override suspend fun identity(carId: Int) = RangePageIdentity(proof.context) { true }
                override suspend fun history(carId: Int) = ApiResult.Success(snapshot)
                override suspend fun health(carId: Int): ApiResult<BatteryHealth> {
                    val result = f.apiRepo.getBatteryHealth(carId)
                    healthObserved.complete(Unit)
                    return result
                }
                override suspend fun status(carId: Int): ApiResult<CarStatusWithUnits> =
                    ApiResult.Error("synthetic status unavailable")
            }
            val page = RangeViewModel(source); vm = page
            page.setCarId(1)
            withTimeout(20_000) { healthObserved.await() }
            val absent = withTimeout(20_000) { page.uiState.first { it.personalizedRange?.sampleCount == 10 } }
            assertEquals(PersonalizedRangeSource.GLOBAL, absent.personalizedRange!!.source)
            assertNull(absent.personalizedRange!!.rangeKm)

            healthBody.set("""{"data":{"battery_health":{"max_capacity":75,"current_capacity":60},"meta":{"availability":"available"}}}""")
            page.refresh()
            val measured = withTimeout(20_000) { page.uiState.first { it.personalizedRange?.rangeKm != null } }
            assertEquals(300.0, measured.personalizedRange!!.rangeKm!!, 0.001)
        } finally {
            vm?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            f.close()
            Dispatchers.resetMain()
        }
    }
}

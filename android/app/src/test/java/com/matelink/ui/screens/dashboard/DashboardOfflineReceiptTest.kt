package com.matelink.ui.screens.dashboard

import android.app.Application
import android.content.Context
import com.matelink.data.api.models.BatteryDetails
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.ChargingDetails
import com.matelink.data.api.models.DrivingDetails
import com.matelink.data.api.models.Units
import com.matelink.data.local.VehicleStatusStore
import com.matelink.data.repository.ApiErrorKind
import com.matelink.data.repository.ApiResult
import com.matelink.data.sync.HistoryResourceFixture
import com.squareup.moshi.Moshi
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class DashboardOfflineReceiptTest {
    @get:Rule val temp = TemporaryFolder()

    @Before fun clearIsolatedTestStore() {
        RuntimeEnvironment.getApplication().getSharedPreferences("vehicle_cached_status", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun cachedReceiptNeedsPreviouslyVerifiedCloudCarAndCurrentScope() = runBlocking(Dispatchers.IO) {
        val fixture = HistoryResourceFixture(File(temp.root, "dashboard-cache.db"))
        try {
            fixture.initialize(cloud = true)
            val scope = fixture.vehicleContexts.captureReadScope()
            val original = fixture.vehicleContexts.resolveVerifiedHistoryCar(
                CarData(1, vehicleUid = "synthetic-vehicle-1"), scope)
            val store = VehicleStatusStore(fixture.context, Moshi.Builder().build())
            store.saveStatus(scope, original, CarStatus(displayName = "synthetic car",
                batteryDetails = BatteryDetails(batteryLevel = 73)), "2026-01-01T00:00:00Z",
                Units(unitOfLength = "km"), "fleet_api")
            fixture.calls.clear()
            suspend fun read(carId: Int) = readDashboardOfflineReceipt(scope, carId, fixture.vehicleContexts, store) {
                fixture.vehicleContexts.isHistoryContextCurrentNow(scope, it)
            }

            val receipt = read(1)?.second
            assertEquals("synthetic car", receipt?.displayStatus?.displayName)
            assertEquals(73, receipt?.displayStatus?.batteryLevel)
            assertEquals("2026-01-01T00:00:00Z", receipt?.observedAt)
            assertNull(read(2))
            assertTrue(fixture.calls.isEmpty())

            fixture.vehicleContexts.resolveVerifiedHistoryCar(CarData(1, vehicleUid = "replacement"), scope)
            assertNull(read(1))
            assertFalse(fixture.vehicleContexts.isHistoryContextCurrentNow(scope, original))
        } finally { fixture.close() }
    }

    @Test fun staleRequestCannotPublishAcrossAccountChange() = runBlocking(Dispatchers.IO) {
        val fixture = HistoryResourceFixture(File(temp.root, "dashboard-aba.db"))
        try {
            fixture.initialize(cloud = true)
            val scope = fixture.vehicleContexts.captureReadScope()
            val vehicle = fixture.vehicleContexts.resolveVerifiedHistoryCar(
                CarData(1, vehicleUid = "synthetic-vehicle-1"), scope)
            val store = VehicleStatusStore(fixture.context, Moshi.Builder().build())
            store.saveStatus(scope, vehicle, CarStatus(displayName = "first account"))
            var checks = 0
            val receipt = readDashboardOfflineReceipt(scope, 1, fixture.vehicleContexts, store) {
                if (++checks == 2) fixture.sessions.save("other-access", "other-refresh", 3600, "other-account")
                fixture.vehicleContexts.isHistoryContextCurrentNow(scope, it)
            }
            assertNull(receipt)
            assertFalse(fixture.vehicleContexts.isHistoryContextCurrentNow(scope, vehicle))
        } finally { fixture.close() }
    }

    @Test fun cachedReceiptNeverAuthorizesAnotherAccountOrSelfHostedSource() = runBlocking(Dispatchers.IO) {
        val fixture = HistoryResourceFixture(File(temp.root, "dashboard-scope.db"))
        try {
            fixture.initialize(cloud = true)
            val scope = fixture.vehicleContexts.captureReadScope()
            val vehicle = fixture.vehicleContexts.resolveVerifiedHistoryCar(
                CarData(1, vehicleUid = "synthetic-vehicle-1"), scope)
            val store = VehicleStatusStore(fixture.context, Moshi.Builder().build())
            store.saveStatus(scope, vehicle, CarStatus(displayName = "first account"))
            val otherOrigin = scope.copy(effectiveApiOrigin = "https://other.example")
            assertNull(readDashboardOfflineReceipt(otherOrigin, 1, fixture.vehicleContexts, store) { true })
            fixture.sessions.save("other-access", "other-refresh", 3600, "other-account")
            val other = fixture.vehicleContexts.captureReadScope()
            assertNull(readDashboardOfflineReceipt(other, 1, fixture.vehicleContexts, store) { true })
        } finally { fixture.close() }

        val selfHosted = HistoryResourceFixture(File(temp.root, "dashboard-selfhost.db"))
        try {
            selfHosted.initialize()
            val scope = selfHosted.vehicleContexts.captureReadScope()
            assertNull(readDashboardOfflineReceipt(scope, 1, selfHosted.vehicleContexts,
                VehicleStatusStore(selfHosted.context, Moshi.Builder().build())) { true })
        } finally { selfHosted.close() }
    }

    @Test fun onlyTransientDiscoveryFailuresCanUseOfflineCache() {
        val network = ApiResult.Error("offline", kind = ApiErrorKind.NETWORK)
        val auth = ApiResult.Error("unauthorized", code = 401)
        val invalid = ApiResult.Error("invalid", kind = ApiErrorKind.INVALID_RESPONSE)
        assertTrue(dashboardMayReadOfflineCache(network, network, network))
        assertTrue(dashboardMayReadOfflineCache(ApiResult.Error("unavailable", code = 503), network, null))
        assertFalse(dashboardMayReadOfflineCache(ApiResult.Success(emptyList<Any>()), network, null))
        assertFalse(dashboardMayReadOfflineCache(auth, network, null))
        assertFalse(dashboardMayReadOfflineCache(network, auth, network))
        assertFalse(dashboardMayReadOfflineCache(network, ApiResult.Error("missing", code = 404), network))
        assertFalse(dashboardMayReadOfflineCache(network, network, auth))
        assertFalse(dashboardMayReadOfflineCache(network, network, ApiResult.Error("missing", code = 404)))
        assertFalse(dashboardMayReadOfflineCache(
            ApiResult.Error("unauthorized", code = 403, kind = ApiErrorKind.NETWORK), network, null))
        assertFalse(dashboardMayReadOfflineCache(invalid, network, null))
    }

    @Test fun historicalStatusCannotRestartLiveHud() {
        val original = CarStatus(state = "driving", stateSince = "2026-01-01T00:00:00Z",
            batteryDetails = BatteryDetails(batteryLevel = 73),
            drivingDetails = DrivingDetails(speed = 42.0, shiftState = "D", power = 20.0),
            chargingDetails = ChargingDetails(chargingState = "Charging", chargeEnergyAdded = 5.0))
        val historical = dashboardHistoricalStatus(original)
        assertNull(historical.state)
        assertNull(historical.speed)
        assertNull(historical.shiftState)
        assertNull(historical.chargingState)
        assertEquals(73, historical.batteryLevel)
        assertEquals(5.0, historical.chargeEnergyAdded!!, 0.0)
        assertEquals(42.0, original.speed!!, 0.0)
        assertTrue(original.isCharging)
    }
}

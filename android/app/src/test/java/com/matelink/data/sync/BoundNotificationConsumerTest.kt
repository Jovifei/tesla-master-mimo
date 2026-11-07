package com.matelink.data.sync

import android.app.Application
import com.matelink.data.api.TeslamateApi
import com.matelink.data.api.models.*
import com.matelink.data.local.ConnectionMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BoundNotificationConsumerTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun api(onCars: () -> Unit = {}, onStatus: () -> Unit = {}): TeslamateApi =
        Proxy.newProxyInstance(TeslamateApi::class.java.classLoader, arrayOf(TeslamateApi::class.java)) { _, method, _ ->
            when (method.name) {
                "getCars" -> { onCars(); Response.success(CarsResponse(CarsData(listOf(CarData(1, vehicleUid = "synthetic-vehicle-1"))))) }
                "getCarStatus" -> {
                    onStatus()
                    val status = CarStatus(chargingDetails = ChargingDetails(chargingState = "Charging", chargerPhases = 0))
                    Response.success(CarStatusResponse(data = CarStatusData(status = status)))
                }
                else -> error("Unexpected isolated notification call: ${method.name}")
            }
        } as TeslamateApi

    @Test fun lateStatusAfterOriginSwitchCannotEscapeItsCapturedVehicle() = runBlocking {
        val f = HistoryResourceFixture(File(temporary.root, "bound-notification-origin.db"))
        f.initialize(cloud = false)
        try {
            val scope = f.vehicleContexts.captureReadScope()
            var calls = 0
            val session = BoundNotificationSession(scope, api(onStatus = {
                calls++
                runBlocking { f.settings.saveServerUrl("https://other.synthetic.example") }
            }), f.vehicleContexts)
            val vehicle = session.vehicles().single()
            assertTrue(vehicle.context.localHistoryCarId < 0)
            val result = runCatching { session.status(vehicle) }
            assertTrue(result.isFailure)
            assertNull(result.getOrNull())
            assertEquals(1, calls) // Only the already admitted old-source request.
        } finally { f.close() }
    }

    @Test fun cloudSwitchBeforeAReadPreventsTransportAdmission() = runBlocking {
        val f = HistoryResourceFixture(File(temporary.root, "bound-notification-cloud.db"))
        f.initialize(cloud = false)
        try {
            val scope = f.vehicleContexts.captureReadScope()
            var calls = 0
            val session = BoundNotificationSession(scope, api(onStatus = { calls++ }), f.vehicleContexts)
            val vehicle = session.vehicles().single()
            f.sessions.save("next-access", "next-refresh", 3600, "next-account")
            f.modes.set(ConnectionMode.TESLA_CLOUD)
            assertTrue(runCatching { session.status(vehicle) }.isFailure)
            assertEquals(0, calls)
        } finally { f.close() }
    }

    @Test fun aLateCarListCannotBindAnOldAuthenticatedRowToTheNewScope() = runBlocking {
        val f = HistoryResourceFixture(File(temporary.root, "bound-notification-cars.db"))
        f.initialize(cloud = false)
        try {
            val scope = f.vehicleContexts.captureReadScope()
            val session = BoundNotificationSession(scope, api(onCars = {
                runBlocking {
                    f.sessions.save("next-access", "next-refresh", 3600, "next-account")
                    f.modes.set(ConnectionMode.TESLA_CLOUD)
                }
            }), f.vehicleContexts)
            assertTrue(runCatching { session.vehicles() }.isFailure)
            val next = f.vehicleContexts.captureReadScope()
            assertNull(f.vehicleContexts.cachedContextForRemote(1, next))
        } finally { f.close() }
    }
}

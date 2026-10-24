package com.matelink.data.local

import android.app.Application
import android.content.Context
import com.matelink.data.api.models.CarStatus
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class VehicleStatusStoreScopeTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val store: VehicleStatusStore get() = VehicleStatusStore(app, Moshi.Builder().build())

    @Before fun clearStore() {
        app.getSharedPreferences("vehicle_cached_status", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun sameNumericCarNeverCrossesAccountOriginOrVerifiedVehicle() {
        val alice = cloudScope("alice", "https://one.example")
        val bob = cloudScope("bob", "https://one.example")
        val otherOrigin = cloudScope("alice", "https://two.example")
        val car = vehicle(-1)
        store.saveStatus(alice, car, CarStatus(displayName = "Alice car"), "2026-10-23T01:00:00Z")

        assertEquals("Alice car", store.getCachedStatus(alice, car)?.displayName)
        assertNull(store.getCachedStatus(bob, car))
        assertNull(store.getCachedStatus(otherOrigin, car))
        assertNull(store.getCachedStatus(alice, vehicle(-2)))
    }

    @Test fun liveSnapshotWithoutGpsIsDurableAndNeverBorrowsAnotherCarsPosition() {
        val scope = cloudScope("alice", "https://one.example")
        val car = vehicle(-1)
        val otherCar = vehicle(-2)
        store.saveStatus(scope, otherCar, CarStatus(displayName = "Other car"), null)

        store.reconcileAndSave(scope, car, CarStatus(displayName = "No GPS"), "2026-10-23T01:00:00Z")

        val reopened = VehicleStatusStore(app, Moshi.Builder().build())
        assertEquals("No GPS", reopened.getCachedStatus(scope, car)?.displayName)
        assertNull(reopened.getCachedStatus(scope, car)?.latitude)
        assertEquals("2026-10-23T01:00:00Z", reopened.getCachedObservedAt(scope, car))
    }

    @Test fun unscopedLegacySnapshotIsNeverAutoClaimedByNewAccount() {
        app.getSharedPreferences("vehicle_cached_status", Context.MODE_PRIVATE).edit()
            .putString("status_1", Moshi.Builder().build().adapter(CarStatus::class.java)
                .toJson(CarStatus(displayName = "Old account"))).commit()

        assertNull(store.getCachedStatus(cloudScope("bob", "https://one.example"), vehicle(-1)))
    }

    private fun cloudScope(account: String, origin: String) =
        HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", account, origin)

    private fun vehicle(localId: Int) = VehicleContext(
        remoteApiCarId = 1, stableIdentity = "verified:$localId", localHistoryCarId = localId,
        connectionSource = HistoryConnectionSource.CLOUD, serverIdentity = "cloud"
    )
}

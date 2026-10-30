package com.matelink.data.local

import android.app.Application
import android.content.Context
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.CarData
import com.matelink.ui.screens.battery.readCachedBatteryStatus
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
        app.getSharedPreferences("vehicle_history_identity", Context.MODE_PRIVATE).edit().clear().commit()
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

    @Test fun laterGpsLessReceiptKeepsOriginalPositionTimeWithoutMerging() {
        val scope = cloudScope("alice", "https://one.example")
        val car = vehicle(-1)
        store.saveStatus(scope, car,
            CarStatus(carGeodata = com.matelink.data.api.models.CarGeodata(
                latitude = 40.0, longitude = -73.0),
                climateDetails = com.matelink.data.api.models.ClimateDetails(insideTemp = 0.0)),
            "2026-10-23T01:00:00Z")
        val current = store.reconcileAndSave(scope, car,
            CarStatus(climateDetails = com.matelink.data.api.models.ClimateDetails(insideTemp = 21.0)),
            "2026-10-23T02:00:00Z")
        assertNull(current.latitude)
        val reopened = VehicleStatusStore(app, Moshi.Builder().build())
        assertNull(reopened.getCachedStatus(scope, car)?.latitude)
        assertEquals(21.0, reopened.getCachedStatus(scope, car)!!.insideTemp!!, 0.0)
        assertEquals("2026-10-23T02:00:00Z", reopened.getCachedObservedAt(scope, car))
        assertEquals(40.0, reopened.getCachedPosition(scope, car)!!.latitude, 0.0)
        assertEquals("2026-10-23T01:00:00Z", reopened.getCachedPosition(scope, car)!!.observedAt)
    }

    @Test fun positionWithoutObservationTimeIsNotHistoricalProof() {
        val scope = cloudScope("alice", "https://one.example")
        val car = vehicle(-1)
        store.saveStatus(scope, car,
            CarStatus(carGeodata = com.matelink.data.api.models.CarGeodata(
                latitude = 40.0, longitude = -73.0)), null)
        assertNull(store.getCachedPosition(scope, car))
        store.saveStatus(scope, car,
            CarStatus(carGeodata = com.matelink.data.api.models.CarGeodata(
                latitude = 41.0, longitude = -72.0)), "2026-10-23T03:00:00Z")
        assertNull(store.getCachedPosition(cloudScope("bob", "https://one.example"), car))
        assertNull(store.getCachedPosition(scope, vehicle(-2)))
    }

    @Test fun uidVerifiedCloudStatusReopensViaScopedDigestWithoutClaimingAnotherVehicle() {
        val scope = cloudScope("alice", "https://one.example")
        val car = CarData(1, vehicleUid = "provider-a")
        val contexts = VehicleContextStore(app)
        val verified = contexts.resolveVerifiedHistoryCar(car, scope.accountNamespace,
            scope.source, scope.serverIdentity, scope.effectiveApiOrigin)
        store.saveStatus(scope, verified, CarStatus(displayName = "Verified A"), "2026-10-23T01:00:00Z")

        val reopenedContexts = VehicleContextStore(app)
        val reopenedStatus = VehicleStatusStore(app, Moshi.Builder().build())
        val localId = reopenedContexts.findOriginCloudLocalHistoryCarId("alice", scope.effectiveApiOrigin, 1)!!
        val opaque = VehicleContext(1,
            reopenedContexts.originCloudRemoteOpaqueIdentity("alice", scope.effectiveApiOrigin, 1),
            localId, scope.source, scope.serverIdentity)
        val digest = reopenedContexts.findVerifiedOriginCloudIdentityDigest("alice", scope.effectiveApiOrigin, 1, localId)!!
        assertNull(reopenedStatus.getCachedStatus(scope, opaque))
        assertEquals("Verified A", readCachedBatteryStatus(scope, opaque, digest, reopenedStatus)?.displayName)
        assertNull(readCachedBatteryStatus(cloudScope("bob", scope.effectiveApiOrigin), opaque, digest, reopenedStatus))
        assertNull(reopenedContexts.findVerifiedOriginCloudIdentityDigest("alice", "https://other.example", 1, localId))

        val rebound = reopenedContexts.resolveVerifiedHistoryCar(car.copy(vehicleUid = "provider-b"),
            scope.accountNamespace, scope.source, scope.serverIdentity, scope.effectiveApiOrigin)
        assertNull(reopenedContexts.findVerifiedOriginCloudIdentityDigest("alice", scope.effectiveApiOrigin, 1, localId))
        val reboundDigest = reopenedContexts.findVerifiedOriginCloudIdentityDigest("alice",
            scope.effectiveApiOrigin, 1, rebound.localHistoryCarId)!!
        val reboundOpaque = opaque.copy(localHistoryCarId = rebound.localHistoryCarId)
        assertNull(readCachedBatteryStatus(scope, reboundOpaque, reboundDigest, reopenedStatus))
    }

    @Test fun selfHostedBatteryConsumerKeepsItsExistingScopedStatusPath() {
        val scope = HistoryReadScope(HistoryConnectionSource.SELF_HOSTED,
            "https://self.example", null, "https://self.example")
        val vehicle = VehicleContext(1, selfHostedVehicleStableIdentity(scope.serverIdentity, 1),
            -11, scope.source, scope.serverIdentity)
        store.saveStatus(scope, vehicle, CarStatus(displayName = "Self hosted"))
        assertEquals("Self hosted", readCachedBatteryStatus(scope, vehicle, null,
            VehicleStatusStore(app, Moshi.Builder().build()))?.displayName)
    }

    private fun cloudScope(account: String, origin: String) =
        HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", account, origin)

    private fun vehicle(localId: Int) = VehicleContext(
        remoteApiCarId = 1, stableIdentity = "verified:$localId", localHistoryCarId = localId,
        connectionSource = HistoryConnectionSource.CLOUD, serverIdentity = "cloud"
    )
}

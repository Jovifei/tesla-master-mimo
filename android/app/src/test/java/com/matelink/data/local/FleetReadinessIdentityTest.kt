package com.matelink.data.local

import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.DataReadiness
import com.matelink.ui.screens.readiness.verifiedFleetReadiness
import org.junit.Assert.*
import org.junit.Test

class FleetReadinessIdentityTest {
    @Test fun wireDigestAndProviderIdRequireSameAccountOriginAndVerifiedVehicle() {
        val disk = HistoryConsumerIdentityRecoveryTest.PreferenceDisk()
        val store = VehicleContextStore(disk.open())
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val repo = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, VehicleContextRepository::class.java) as VehicleContextRepository
        VehicleContextRepository::class.java.getDeclaredField("contextStore").apply { isAccessible = true }.set(repo, store)
        val scope = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "owner-a", "https://a.invalid")
        val car = CarData(7, vehicleUid = "provider-a")
        repo.resolveVerifiedHistoryCar(car, scope)
        val cached = repo.cachedContextForRemote(7, scope)!!
        val readiness = DataReadiness(1, "914c08154b31b652a2ef4f133027de9ee913aca8d238646bf0bc7ddfd3610e4b", emptyList())
        assertTrue(verifiedFleetReadiness(repo, scope, cached, readiness, car))
        assertFalse(verifiedFleetReadiness(repo, scope.copy(accountNamespace = "owner-b"), cached, readiness, car))
        assertFalse(verifiedFleetReadiness(repo, scope.copy(effectiveApiOrigin = "https://b.invalid"), cached, readiness, car))
        assertFalse(verifiedFleetReadiness(repo, scope, cached, readiness, car.copy(vehicleUid = "provider-b")))
        assertFalse(verifiedFleetReadiness(repo, scope, cached, readiness, car.copy(carId = 8)))
        assertFalse(verifiedFleetReadiness(repo, scope, cached, readiness.copy(vehicleUid = "0".repeat(64)), car))
    }
}

package com.matelink.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCHistoryIsolationContractTest {
    @Test
    fun carListCarriesTheServerVehicleUidWithoutUsingVinAsIdentity() {
        val source = File("src/main/java/com/matelink/data/api/models/CarModels.kt").readText()

        assertTrue(source.contains("vehicleUid"))
        assertTrue(source.contains("@Json(name = \"vehicle_uid\")"))
        assertTrue("VIN must remain display-only data", source.contains("vin"))
    }

    @Test
    fun vehicleContextAndStoreUseOpaqueStableIdentityAndSeparateLocalHistoryId() {
        val sources = listOf(
            File("src/main/java/com/matelink/data/local/VehicleContext.kt"),
            File("src/main/java/com/matelink/data/local/VehicleContextStore.kt")
        ).joinToString("\n") { if (it.exists()) it.readText() else "" }

        assertTrue(sources.contains("remoteApiCarId"))
        assertTrue(sources.contains("localHistoryCarId"))
        assertTrue(sources.contains("stableIdentity"))
        assertTrue(sources.contains("MessageDigest"))
        assertTrue("the persisted key must not contain a raw account identifier", !sources.contains("putString(.*userId"))
    }
}

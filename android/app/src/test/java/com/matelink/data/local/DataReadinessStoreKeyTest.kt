package com.matelink.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataReadinessStoreKeyTest {
    @Test
    fun seenKeyIsOpaqueAndIsolatedByAllIdentityInputs() {
        val base = dataReadinessSeenKey(
            accountNamespace = "user-a",
            vehicleUid = "vehicle-a",
            carId = 7,
            capabilityVersion = 1,
            connectionMode = ConnectionMode.SELF_HOSTED,
            selfHostedServerIdentity = "HTTP://Example.com:8080/"
        )

        assertTrue(base.matches(Regex("seen:v2:[0-9a-f]{64}")))
        assertTrue(!base.contains("user-a"))
        assertNotEquals(
            base,
            dataReadinessSeenKey("user-b", "vehicle-a", 7, 1, ConnectionMode.SELF_HOSTED, "http://example.com:8080")
        )
        assertNotEquals(
            base,
            dataReadinessSeenKey("user-a", "vehicle-b", 7, 1, ConnectionMode.SELF_HOSTED, "http://example.com:8080")
        )
        assertNotEquals(
            base,
            dataReadinessSeenKey("user-a", "vehicle-a", 7, 2, ConnectionMode.SELF_HOSTED, "http://example.com:8080")
        )
        assertNotEquals(
            base,
            dataReadinessSeenKey("user-a", "vehicle-a", 7, 1, ConnectionMode.TESLA_CLOUD, null)
        )
    }

    @Test
    fun vehicleIdFallbackAndServerNormalizationAreDeterministic() {
        val identity = dataReadinessSeenKey(
            accountNamespace = null,
            vehicleUid = null,
            carId = 7,
            capabilityVersion = null,
            connectionMode = ConnectionMode.SELF_HOSTED,
            selfHostedServerIdentity = "http://EXAMPLE.com:80/"
        )

        assertEquals(
            identity,
            dataReadinessSeenKey(null, null, 7, null, ConnectionMode.SELF_HOSTED, "HTTP://example.com:80")
        )
        assertNotEquals(
            identity,
            dataReadinessSeenKey(null, null, 8, null, ConnectionMode.SELF_HOSTED, "http://example.com:80")
        )
    }
}

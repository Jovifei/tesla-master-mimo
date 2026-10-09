package com.matelink.data.local

import org.junit.Assert.*
import org.junit.Test

class HistoryReadScopeTest {
    private val initial = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "account-a", "https://api.example")
    @Test fun accountSwitchOrLogoutInvalidatesInFlightRead() {
        assertNotEquals(initial, initial.copy(accountNamespace = "account-b"))
        assertNotEquals(initial, initial.copy(accountNamespace = null))
    }
    @Test fun effectiveApiOriginAndConnectionModeArePartOfSnapshot() {
        assertNotEquals(initial, initial.copy(effectiveApiOrigin = "https://other.example"))
        assertNotEquals(initial, initial.copy(source = HistoryConnectionSource.SELF_HOSTED))
        assertEquals(initial, initial.copy())
    }
    @Test fun originBoundStableIdentitySeparatesOriginsAccountsAndVehiclesFromLegacy() {
        val original = cloudOriginVehicleStableIdentity("account-a", "https://api.example", "provider-a")
        assertNotEquals(original, cloudOriginVehicleStableIdentity("account-a", "https://other.example", "provider-a"))
        assertNotEquals(original, cloudOriginVehicleStableIdentity("account-b", "https://api.example", "provider-a"))
        assertNotEquals(original, cloudOriginVehicleStableIdentity("account-a", "https://api.example", "provider-b"))
        assertNotEquals(original, cloudVehicleStableIdentity("account-a", "provider-a"))
        assertEquals(original, cloudOriginVehicleStableIdentity("account-a", "https://api.example/", "provider-a"))
    }
    @Test fun persistedCloudNamespaceDoesNotChange() {
        assertEquals("cloud", initial.serverIdentity)
        assertEquals("cloud", initial.copy(effectiveApiOrigin = "https://other.example").serverIdentity)
    }
}

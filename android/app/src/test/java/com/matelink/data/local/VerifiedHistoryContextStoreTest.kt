package com.matelink.data.local

import android.content.SharedPreferences
import com.matelink.data.api.models.CarData
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class VerifiedHistoryContextStoreTest {
    private class MemoryPreferences {
        val ints = mutableMapOf<String, Int>()
        private val writes = mutableMapOf<String, Int>()
        private lateinit var editor: SharedPreferences.Editor
        val preferences: SharedPreferences
        init {
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putInt" -> { writes[args[0] as String] = args[1] as Int; editor }
                    "commit" -> { ints.putAll(writes); writes.clear(); true }
                    else -> error("unexpected editor method ${method.name}")
                }
            } as SharedPreferences.Editor
            preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getInt" -> ints[args[0] as String] ?: args[1]
                    "edit" -> editor
                    else -> error("unexpected preferences method ${method.name}")
                }
            } as SharedPreferences
        }
    }
    private val car = CarData(7, vehicleUid = "provider-a")
    private val source = HistoryConnectionSource.CLOUD
    @Test fun unknownOriginLegacyMappingIsNotEligibleAndOldNamespaceIsPreserved() {
        val memory = MemoryPreferences(); val store = VehicleContextStore(memory.preferences)
        val old = store.resolveCar(car, "account-a", source, "cloud")
        assertNull(store.findOriginCloudLocalHistoryCarId("account-a", "https://api.example", 7))
        val verified = store.resolveVerifiedHistoryCar(car, "account-a", source, "cloud", "https://api.example")
        assertNotEquals(old.localHistoryCarId, verified.localHistoryCarId)
        assertEquals(old.localHistoryCarId, store.findCloudLocalHistoryCarId("account-a", 7))
        assertEquals(old.localHistoryCarId, store.resolveCar(car, "account-a", source, "cloud").localHistoryCarId)
        assertEquals(verified.localHistoryCarId, store.findOriginCloudLocalHistoryCarId("account-a", "https://api.example", 7))
        assertTrue(memory.ints.keys.none { it.contains("account-a") || it.contains("api.example") || it.contains("provider-a") })
    }
    @Test fun originsAccountsAndUidsCannotShareTheVerifiedNamespace() {
        val store = VehicleContextStore(MemoryPreferences().preferences)
        val first = store.resolveVerifiedHistoryCar(car, "account-a", source, "cloud", "https://api.example")
        val same = store.resolveVerifiedHistoryCar(car, "account-a", source, "cloud", "https://api.example/")
        assertEquals(first.localHistoryCarId, same.localHistoryCarId)
        val otherOrigin = store.resolveVerifiedHistoryCar(car, "account-a", source, "cloud", "https://other.example")
        val otherAccount = store.resolveVerifiedHistoryCar(car, "account-b", source, "cloud", "https://api.example")
        val otherUid = store.resolveVerifiedHistoryCar(car.copy(vehicleUid = "provider-b"), "account-a", source, "cloud", "https://api.example")
        assertEquals(4, setOf(first.localHistoryCarId, otherOrigin.localHistoryCarId, otherAccount.localHistoryCarId, otherUid.localHistoryCarId).size)
        assertEquals(first.localHistoryCarId, store.findLocalHistoryCarId(first.stableIdentity))
        assertEquals(otherUid.localHistoryCarId, store.findOriginCloudLocalHistoryCarId("account-a", "https://api.example", 7))
    }
}

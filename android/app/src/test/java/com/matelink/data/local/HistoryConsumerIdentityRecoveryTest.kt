package com.matelink.data.local

import android.content.SharedPreferences
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.ChargeData
import com.matelink.data.sync.completionReceipt
import com.matelink.data.sync.matchesCharge
import com.matelink.data.sync.resolvePendingCompletion
import com.matelink.data.sync.toSyncSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant

/** Actual persisted identity store and resolver adapters; only SharedPreferences I/O is fake. */
class HistoryConsumerIdentityRecoveryTest {
    private val a = HistoryReadScope(HistoryConnectionSource.CLOUD, "cloud", "synthetic-account", "https://a.invalid")
    private val b = a.copy(effectiveApiOrigin = "https://b.invalid")
    private val car = CarData(7, vehicleUid = "provider-a")

    // These tests exercise the synchronous scoped production adapters without constructing
    // unrelated Android DataStores/API clients. No method using an unset dependency is called.
    private fun repository(store: VehicleContextStore): VehicleContextRepository {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val result = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, VehicleContextRepository::class.java) as VehicleContextRepository
        VehicleContextRepository::class.java.getDeclaredField("contextStore").apply { isAccessible = true }.set(result, store)
        return result
    }

    @Test fun syncResolverUsesTheSameOriginNamespaceAsHistoryAndPreservesLegacy() {
        val disk = PreferenceDisk()
        val store = VehicleContextStore(disk.open())
        val legacy = store.resolveCar(car, a.accountNamespace, a.source, a.serverIdentity)
        val repo = repository(store)
        val historyA = repo.resolveVerifiedHistoryCar(car, a)
        val historyB = repo.resolveVerifiedHistoryCar(car, b)
        assertNotEquals(historyA.localHistoryCarId, historyB.localHistoryCarId)
        assertNotEquals(legacy.localHistoryCarId, historyA.localHistoryCarId)
        assertEquals("Sync's production resolver must use the verified history namespace",
            historyA.localHistoryCarId, repo.resolve(car, a).localHistoryCarId)
        assertEquals(historyB.localHistoryCarId, repo.resolve(car, b).localHistoryCarId)
        assertEquals(legacy.localHistoryCarId, store.findCloudLocalHistoryCarId(a.accountNamespace!!, car.carId))
    }

    @Test fun notificationCacheRestoresOnlyTheRequestedOriginAfterStoreAndRepositoryRecreation() = runBlocking {
        val disk = PreferenceDisk()
        val original = repository(VehicleContextStore(disk.open()))
        val historyA = original.resolveVerifiedHistoryCar(car, a)
        val historyB = original.resolveVerifiedHistoryCar(car, b)
        val restored = repository(VehicleContextStore(disk.open()))
        assertEquals("Prompt's actual cached resolver must recover the origin-scoped mapping",
            historyA.localHistoryCarId, restored.cachedContextForRemote(car.carId, a)?.localHistoryCarId)
        assertEquals(historyB.localHistoryCarId, restored.cachedContextForRemote(car.carId, b)?.localHistoryCarId)
        assertNull(restored.cachedContextForRemote(car.carId, a.copy(accountNamespace = "other-account")))
        assertNull(restored.cachedContextForRemote(car.carId, a.copy(effectiveApiOrigin = "https://unknown.invalid")))
    }

    @Test fun unknownOriginLegacyMappingCannotAuthorizeForegroundNotificationRecovery() = runBlocking {
        val disk = PreferenceDisk()
        val store = VehicleContextStore(disk.open())
        val legacy = store.resolveCar(car, a.accountNamespace, a.source, a.serverIdentity)
        val restored = repository(VehicleContextStore(disk.open()))
        assertNull("Unknown-origin legacy data must remain unlinked, not become a pending notification source",
            restored.cachedContextForRemote(car.carId, a))
        assertEquals(legacy.localHistoryCarId, store.findCloudLocalHistoryCarId(a.accountNamespace!!, car.carId))
    }

    @Test fun restoredNotificationResolverLoadsOnlyItsOwnOriginRoomKeyWithIdenticalReceiptIds() = runBlocking {
        val disk = PreferenceDisk()
        val repo = repository(VehicleContextStore(disk.open()))
        val ca = repo.resolve(car, a)
        val cb = repo.resolve(car, b)
        val cutoff = Instant.parse("2026-10-07T00:00:00Z").toEpochMilli()
        val now = Instant.parse("2026-10-07T02:00:00Z").toEpochMilli()
        val wire = ChargeData(12, startDate = "2026-10-07T01:00:00Z",
            endDate = "2026-10-07T01:05:00Z", source = "telemetry_mqtt",
            qualityState = "incomplete", qualityReason = "partial_or_missing_charge_counter")
        val rowA = wire.toSyncSummary(ca.localHistoryCarId)!!
        val rowB = wire.toSyncSummary(cb.localHistoryCarId)!!
        val keyA = completionScopeKey(a, ca.localHistoryCarId, car.carId)
        val baseline = CompletionJournalCodec.record(null, ca.localHistoryCarId, car.carId, "charge", keyA, emptyList(), cutoff)
        val raw = CompletionJournalCodec.record(baseline, ca.localHistoryCarId, car.carId, "charge", keyA,
            listOf(rowA.completionReceipt(a.source, Instant.ofEpochMilli(now))!!), now)
        val event = CompletionJournalCodec.decode(raw, now).events.single()
        val restored = repository(VehicleContextStore(disk.open()))
        val localA = restored.cachedContextForRemote(car.carId, a)!!.localHistoryCarId
        val rows = mapOf(ca.localHistoryCarId to rowA, cb.localHistoryCarId to rowB)
        assertEquals(rowA, resolvePendingCompletion(event, localA, car.carId, keyA, { true },
            { rows[localA] }, { event.matchesCharge(it) }))
        val localB = restored.cachedContextForRemote(car.carId, b)!!.localHistoryCarId
        var wrongOriginReads = 0
        assertNull(resolvePendingCompletion(event, localB, car.carId,
            completionScopeKey(b, localB, car.carId), { true },
            { wrongOriginReads++; rows[localB] }, { event.matchesCharge(it) }))
        assertEquals(0, wrongOriginReads)
        assertFalse(event.appConsumed)
        assertFalse(event.systemConsumed)
    }

    @Test fun malformedCloudUidCannotAllocateACurrentHistoryNamespace() {
        val disk = PreferenceDisk()
        val repo = repository(VehicleContextStore(disk.open()))
        for (uid in listOf("", " provider-a ", "x".repeat(257))) {
            try {
                repo.resolve(car.copy(vehicleUid = uid), a)
                fail("Invalid authenticated identity must not allocate a mapping")
            } catch (_: HistoryIdentityUnavailableException) { }
            assertNull(repo.cachedContextForRemote(car.carId, a))
        }
    }

    @Test fun publicResolverProofRejectsStaleCarAfterAwaitedScopeSwitchWithoutAllocating() = runBlocking {
        val disk = PreferenceDisk()
        val repo = repository(VehicleContextStore(disk.open()))
        var current = a
        var discoveryCalls = 0
        try {
            repo.resolveCurrentCar(car, a, { current }, { _, _ ->
                discoveryCalls++
                current = b
                car
            })
            fail("The suspended old-source car must not allocate into the new active origin")
        } catch (_: HistoryIdentityUnavailableException) { }
        assertEquals(1, discoveryCalls)
        assertNull(repo.cachedContextForRemote(car.carId, a))
        assertNull(repo.cachedContextForRemote(car.carId, b))
    }

    @Test fun publicResolverReusesOnlyMatchingPersistedOriginUidProofAndFreshMismatchCannotAllocate() = runBlocking {
        val disk = PreferenceDisk()
        val repo = repository(VehicleContextStore(disk.open()))
        val authenticated = repo.resolveVerifiedHistoryCar(car, a)
        assertEquals(authenticated, repo.resolveCurrentCar(car, a, { a }, { _, _ ->
            error("Existing current origin/UID proof must avoid another identity request")
        }))
        assertNull(repo.cachedProofForCar(car.copy(vehicleUid = "other-vehicle"), a))
        assertNull(repo.cachedProofForCar(car, b))
        try {
            repo.resolveCurrentCar(car, b, { b }, { _, _ -> car.copy(vehicleUid = "other-vehicle") })
            fail("Fresh current identity must match the supplied car before allocation")
        } catch (_: HistoryIdentityUnavailableException) { }
        assertNull(repo.cachedContextForRemote(car.carId, b))
        assertEquals(authenticated.localHistoryCarId, repo.cachedContextForRemote(car.carId, a)?.localHistoryCarId)
    }

    /** Persistent backing map shared by reconstructed adapters, with real commit boundaries. */
    internal class PreferenceDisk {
        private val values = linkedMapOf<String, Any?>()
        fun open(): SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getInt", "getLong", "getString", "getBoolean", "getFloat", "getStringSet" -> values[args!![0]] ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "getAll" -> values.toMap()
                "edit" -> editor()
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                "toString" -> "SyntheticPreferenceDisk"
                else -> error("Unexpected preferences call ${method.name}")
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val writes = linkedMapOf<String, Any?>()
            var clear = false
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when {
                    method.name.startsWith("put") -> { writes[args!![0] as String] = args[1]; editor }
                    method.name == "remove" -> { writes[args!![0] as String] = null; editor }
                    method.name == "clear" -> { clear = true; editor }
                    method.name == "commit" || method.name == "apply" -> {
                        if (clear) values.clear()
                        writes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        if (method.name == "commit") true else null
                    }
                    else -> error("Unexpected editor call ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}

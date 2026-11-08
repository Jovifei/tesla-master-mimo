package com.matelink.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.TpmsDetails
import com.matelink.data.sync.processSuccessfulTpmsStatus
import kotlinx.coroutines.CancellationException
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

/** Real save/set APIs and actual snapshot/consumer guard; all persistence I/O is synthetic. */
class HistoryScopeAbaRecoveryTest {
    @Test fun sourceAbaDuringTokenValidationCannotPostAnAlreadyClaimedCustomAlert() = runBlocking {
        val fixture = Fixture()
        fixture.modes.set(ConnectionMode.SELF_HOSTED)
        fixture.settings.saveServerUrl("https://a.invalid")
        val before = fixture.repo.captureReadScope()
        val context = fixture.repo.resolve(CarData(7), before)
        val custom = TpmsCustomAlertStateStore(MemoryPreferences())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val now = System.currentTimeMillis()
        var posts = 0
        var cancelled = false
        val processing = async(Dispatchers.Unconfined) {
            try {
                processSuccessfulTpmsStatus(
                    carId = context.localHistoryCarId, carName = "Synthetic", observedAt = now,
                    tpmsDetails = TpmsDetails(pressureFl = 2.0, pressureUnit = "bar",
                        pressureSource = "telemetry_mqtt", pressureTimeBasis = "collector_received_at",
                        pressureObservedAtFl = Instant.ofEpochMilli(now).toString()),
                    profile = TpmsAlertProfile(2.7, 2.3, 3.1, true),
                    saveObservation = { }, pruneOlderThan90Days = { _, _ -> },
                    detectTeslaStateChange = { _, _ -> null }, updateTeslaState = { _, _ -> },
                    resetCustomState = custom::resetForProfile, claimCustomAlerts = custom::claimAlerts,
                    commitCustomAlert = custom::commitClaim, releaseCustomAlert = custom::releaseClaim,
                    notifyTesla = { _, _, _ -> error("No warning expected") },
                    notifyCustom = { _, _, _ -> posts++ }, executionNow = now,
                    ensureCurrent = {
                        if (!fixture.repo.isHistoryContextCurrent(before, context))
                            throw CancellationException("Synthetic source superseded")
                    },
                    validateCustomClaim = { id, claim ->
                        entered.complete(Unit)
                        resume.await()
                        custom.isClaimCurrent(id, claim)
                    },
                    ensureSourceCurrent = {
                        if (!fixture.repo.isHistoryContextCurrentNow(before, context))
                            throw CancellationException("Synthetic source superseded after token await")
                    }
                )
            } catch (_: CancellationException) { cancelled = true }
        }
        entered.await()
        fixture.modes.set(ConnectionMode.TESLA_CLOUD)
        fixture.modes.set(ConnectionMode.SELF_HOSTED)
        resume.complete(Unit)
        processing.await()
        assertTrue(cancelled)
        assertEquals("A token check cannot bypass the source epoch changed during its own await", 0, posts)
        val state = custom.getStates(context.localHistoryCarId).getValue(TirePosition.FL)
        assertNotNull(state.pending)
        assertNull(state.claimToken)
    }

    @Test fun modeSwitchWhileSettingsSnapshotSuspendsCannotAuthorizeOldSelfHostedRequest() = runBlocking {
        val fixture = Fixture()
        fixture.modes.set(ConnectionMode.SELF_HOSTED)
        fixture.settings.saveServerUrl("https://a.invalid")
        val before = fixture.repo.captureReadScope()
        val context = fixture.repo.resolve(CarData(7), before)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        fixture.settingsDisk.nextCollectionEntered = entered
        fixture.settingsDisk.nextCollectionGate = resume
        val suspendedGuard = async(Dispatchers.Unconfined) {
            fixture.repo.isHistoryContextCurrent(before, context)
        }
        if (suspendedGuard.isCompleted) {
            // A non-suspending epoch fence is valid before the mutation, and must
            // reject the same proof immediately afterwards without reading Settings.
            assertTrue(suspendedGuard.await())
            fixture.modes.set(ConnectionMode.TESLA_CLOUD)
            assertFalse(fixture.repo.isHistoryContextCurrent(before, context))
        } else {
            entered.await()
            fixture.modes.set(ConnectionMode.TESLA_CLOUD)
            resume.complete(Unit)
            assertFalse("A mode snapshot cannot remain authoritative across another store's suspended read",
                suspendedGuard.await())
        }
    }

    @Test fun combinedSnapshotCannotReturnOldModeAfterSuspendedSettingsRead() = runBlocking {
        val fixture = Fixture()
        fixture.modes.set(ConnectionMode.SELF_HOSTED)
        fixture.settings.saveServerUrl("https://a.invalid")
        val before = fixture.repo.captureReadScope()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        fixture.settingsDisk.nextCollectionEntered = entered
        fixture.settingsDisk.nextCollectionGate = resume
        val snapshot = async(Dispatchers.Unconfined) {
            try { fixture.repo.captureReadScope() } catch (_: HistoryIdentityUnavailableException) { null }
        }
        entered.await()
        fixture.modes.set(ConnectionMode.TESLA_CLOUD)
        resume.complete(Unit)
        assertNotEquals("A combined snapshot invalidated during its read must fail closed", before, snapshot.await())
    }

    @Test fun cloudCaptureDoesNotSubscribeToSelfHostedSettings() = runBlocking {
        val fixture = Fixture()
        fixture.modes.set(ConnectionMode.TESLA_CLOUD)
        fixture.save("synthetic-account-a")
        repeat(3) { fixture.repo.captureReadScope() }
        assertEquals(0, fixture.settingsDisk.subscriptions)
    }

    @Test fun unrelatedDisplaySettingsDoNotInvalidateServerIdentity() = runBlocking {
        val fixture = Fixture()
        fixture.modes.set(ConnectionMode.SELF_HOSTED)
        fixture.settings.saveServerUrl("https://a.invalid")
        val before = fixture.repo.captureReadScope()
        val context = fixture.repo.resolve(CarData(7), before)
        fixture.settings.saveCurrency("USD")
        assertTrue(fixture.repo.isHistoryContextCurrent(before, context))
    }

    @Test fun everyConnectionMutationInvalidatesCapturedServerProofWithoutChangingNamespace() = runBlocking {
        val mutations: List<suspend (Fixture) -> Unit> = listOf(
            { it.settings.saveSettings("https://a.invalid", "", "synthetic-token", "synthetic-user",
                "synthetic-password", false, "CNY") },
            { it.settings.saveConnectionSettings("https://a.invalid", "synthetic-token", "CNY") },
            { it.settings.saveHttpBasicAuth("synthetic-user", "synthetic-password") },
            { it.settings.clearSettings(); it.settings.saveServerUrl("https://a.invalid") }
        )
        for (mutate in mutations) {
            val fixture = Fixture()
            fixture.modes.set(ConnectionMode.SELF_HOSTED)
            fixture.settings.saveServerUrl("https://a.invalid")
            val before = fixture.repo.captureReadScope()
            val context = fixture.repo.resolve(CarData(7), before)
            mutate(fixture)
            val after = fixture.repo.captureReadScope()
            assertFalse(fixture.repo.isHistoryContextCurrent(before, context))
            assertTrue(fixture.repo.isHistoryContextCurrent(after, context))
            assertEquals(completionScopeKey(before, context.localHistoryCarId, 7),
                completionScopeKey(after, context.localHistoryCarId, 7))
        }
    }

    @Test fun selfHostedServerUrlAbaInvalidatesOldRequestAndPreservesStableHistoryIdentity() = runBlocking {
        val fixture = Fixture()
        fixture.modes.set(ConnectionMode.SELF_HOSTED)
        fixture.settings.saveServerUrl("https://a.invalid")
        val before = fixture.repo.captureReadScope()
        val context = fixture.repo.resolve(CarData(7), before)
        val key = completionScopeKey(before, context.localHistoryCarId, 7)
        fixture.settings.saveServerUrl("https://b.invalid")
        fixture.settings.saveServerUrl("https://a.invalid")
        val after = fixture.repo.captureReadScope()
        assertEquals(before.serverIdentity, after.serverIdentity)
        assertFalse("Restoring the URL text cannot reauthorize an old suspended server request",
            fixture.repo.isHistoryContextCurrent(before, context))
        assertTrue(fixture.repo.isHistoryContextCurrent(after, context))
        assertEquals(key, completionScopeKey(after, context.localHistoryCarId, 7))
    }

    @Test fun selfHostedAuthenticationAbaInvalidatesOldRequestWithoutPrintingOrMigratingCredentials() = runBlocking {
        val fixture = Fixture()
        fixture.modes.set(ConnectionMode.SELF_HOSTED)
        fixture.settings.saveServerUrl("https://a.invalid")
        fixture.settings.saveApiToken("synthetic-a")
        val before = fixture.repo.captureReadScope()
        val context = fixture.repo.resolve(CarData(7), before)
        val key = completionScopeKey(before, context.localHistoryCarId, 7)
        fixture.settings.saveApiToken("synthetic-b")
        fixture.settings.saveApiToken("synthetic-a")
        val after = fixture.repo.captureReadScope()
        assertFalse(fixture.repo.isHistoryContextCurrent(before, context))
        assertTrue(fixture.repo.isHistoryContextCurrent(after, context))
        assertEquals(key, completionScopeKey(after, context.localHistoryCarId, 7))
        assertEquals(context.localHistoryCarId, fixture.repo.cachedContextForRemote(7, after)?.localHistoryCarId)
    }

    @Test fun clearedAndRestoredSessionCannotReuseOldRequestGuardOrDeleteVehicleHistory() = runBlocking {
        val fixture = Fixture()
        fixture.modes.set(ConnectionMode.TESLA_CLOUD)
        fixture.save("synthetic-account-a")
        val before = fixture.repo.captureReadScope()
        val context = fixture.repo.resolve(CarData(7, vehicleUid = "synthetic-vehicle"), before)
        fixture.sessions.clear()
        fixture.save("synthetic-account-a")
        val after = fixture.repo.captureReadScope()
        assertFalse(fixture.repo.isHistoryContextCurrent(before, context))
        assertTrue(fixture.repo.isHistoryContextCurrent(after, context))
        assertEquals(context.localHistoryCarId, fixture.repo.cachedContextForRemote(7, after)?.localHistoryCarId)
        assertEquals(completionScopeKey(before, context.localHistoryCarId, 7),
            completionScopeKey(after, context.localHistoryCarId, 7))
    }

    @Test fun sessionAccountAbaInvalidatesOldRequestWithoutChangingStableHistoryOrJournalIdentity() = runBlocking {
        val fixture = Fixture()
        fixture.modes.set(ConnectionMode.TESLA_CLOUD)
        fixture.save("synthetic-account-a")
        val before = fixture.repo.captureReadScope()
        val context = fixture.repo.resolve(CarData(7, vehicleUid = "synthetic-vehicle"), before)
        val key = completionScopeKey(before, context.localHistoryCarId, 7)
        fixture.save("synthetic-account-b")
        fixture.save("synthetic-account-a")
        val after = fixture.repo.captureReadScope()
        assertEquals(before.accountNamespace, after.accountNamespace)
        assertFalse("A restored account string cannot reauthorize an old suspended request",
            fixture.repo.isHistoryContextCurrent(before, context))
        assertTrue(fixture.repo.isHistoryContextCurrent(after, context))
        assertEquals(key, completionScopeKey(after, context.localHistoryCarId, 7))
        assertEquals(context.localHistoryCarId, fixture.repo.cachedContextForRemote(7, after)?.localHistoryCarId)
        assertEquals(context.localHistoryCarId, fixture.repo.resolveCurrentCar(
            CarData(7, vehicleUid = "synthetic-vehicle"), after, { after },
            { _, _ -> error("Returning to the same verified identity must not require another network request") }
        ).localHistoryCarId)
    }

    @Test fun connectionModeAbaInvalidatesOldRequestWithoutChangingStableJournalKey() = runBlocking {
        val fixture = Fixture()
        fixture.modes.set(ConnectionMode.TESLA_CLOUD)
        fixture.save("synthetic-account-a")
        val before = fixture.repo.captureReadScope()
        val context = fixture.repo.resolve(CarData(7, vehicleUid = "synthetic-vehicle"), before)
        val key = completionScopeKey(before, context.localHistoryCarId, 7)
        fixture.modes.set(ConnectionMode.SELF_HOSTED)
        fixture.modes.set(ConnectionMode.TESLA_CLOUD)
        val after = fixture.repo.captureReadScope()
        assertEquals(before.source, after.source)
        assertFalse("Restoring the same connection mode cannot reauthorize an old request",
            fixture.repo.isHistoryContextCurrent(before, context))
        assertTrue(fixture.repo.isHistoryContextCurrent(after, context))
        assertEquals(key, completionScopeKey(after, context.localHistoryCarId, 7))
    }

    @Test fun tpmsCloudIdentityDoesNotDependOnSelfHostedUrl() = runBlocking {
        val fixture = Fixture()
        fixture.modes.set(ConnectionMode.TESLA_CLOUD)
        fixture.save("synthetic-account-a")
        assertFalse(fixture.settings.settings.first().isConfigured)
        val scope = fixture.repo.captureReadScope()
        assertEquals(HistoryConnectionSource.CLOUD, scope.source)
        assertEquals("synthetic-account-a", scope.accountNamespace)
    }

    @Test fun tpmsSelfHostedIdentityRejectsBlankServerUrl() = runBlocking {
        val fixture = Fixture()
        fixture.modes.set(ConnectionMode.SELF_HOSTED)
        assertFalse(fixture.settings.settings.first().isConfigured)
        val failure = runCatching { fixture.repo.captureReadScope() }.exceptionOrNull()
        assertTrue(failure is HistoryIdentityUnavailableException)
    }

    private class Fixture {
        private val disk = HistoryConsumerIdentityRecoveryTest.PreferenceDisk()
        val sessions = JourVoltSessionStore(HistoryConsumerIdentityRecoveryTest.PreferenceDisk().open())
        val modes = ConnectionModeStore(MemoryPreferences())
        val settingsDisk = MemoryPreferences()
        val settings: SettingsDataStore
        val repo: VehicleContextRepository
        init {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val secure = unsafeClass.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, SecureSettingsDataStore::class.java) as SecureSettingsDataStore
            fun secureField(name: String, value: Any) {
                SecureSettingsDataStore::class.java.getDeclaredField(name).apply { isAccessible = true }.set(secure, value)
            }
            secureField("securePrefs", HistoryConsumerIdentityRecoveryTest.PreferenceDisk().open())
            secureField("_apiTokenFlow", MutableStateFlow(""))
            secureField("_httpBasicPasswordFlow", MutableStateFlow(""))
            secureField("_amapKeyFlow", MutableStateFlow(""))
            settings = SettingsDataStore(settingsDisk, secure,
                HistoryConsumerIdentityRecoveryTest.PreferenceDisk().open())
            repo = unsafeClass.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, VehicleContextRepository::class.java) as VehicleContextRepository
            fun field(name: String, value: Any) {
                VehicleContextRepository::class.java.getDeclaredField(name).apply { isAccessible = true }.set(repo, value)
            }
            field("contextStore", VehicleContextStore(disk.open()))
            field("sessionStore", sessions)
            field("connectionModeStore", modes)
            field("settingsDataStore", settings)
        }
        fun save(account: String) = sessions.save("synthetic-access", "synthetic-refresh", 3600, account)
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        var subscriptions = 0
        var nextCollectionGate: CompletableDeferred<Unit>? = null
        var nextCollectionEntered: CompletableDeferred<Unit>? = null
        override val data = flow {
            val gate = nextCollectionGate
            nextCollectionGate = null
            if (gate != null) {
                nextCollectionEntered?.complete(Unit)
                nextCollectionEntered = null
                gate.await()
            }
            subscriptions++
            emitAll(state)
        }
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }
}

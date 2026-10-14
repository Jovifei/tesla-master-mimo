package com.matelink.data.local

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ContinuationIdentityEpochTest {
    @get:Rule val temp = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private val unsafe: Any get() = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        .apply { isAccessible = true }.get(null)
    private fun <T> allocate(type: Class<T>): T = type.cast(unsafe.javaClass
        .getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    private fun field(target: Any, name: String, value: Any) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(target, value)

    // Same secure-store fixture seam as HistoryScopeAbaRecoveryTest, with actual SharedPreferences.
    private fun secure(): SecureSettingsDataStore = allocate(SecureSettingsDataStore::class.java).also {
        field(it, "securePrefs", context.getSharedPreferences("epoch-secure-${temp.root.name}", Context.MODE_PRIVATE))
        field(it, "_apiTokenFlow", MutableStateFlow(it.getApiToken()))
        field(it, "_httpBasicPasswordFlow", MutableStateFlow(it.getHttpBasicPassword()))
        field(it, "_amapKeyFlow", MutableStateFlow(it.getAmapKey()))
    }

    private inner class Disk {
        val job = SupervisorJob()
        val modes = ConnectionModeStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job), produceFile = { File(temp.root, "mode.preferences_pb") }))
        val settings = SettingsDataStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job), produceFile = { File(temp.root, "settings.preferences_pb") }),
            secure(), context.getSharedPreferences("epoch-language-${temp.root.name}", Context.MODE_PRIVATE))
        val sessions = JourVoltSessionStore(context.getSharedPreferences("epoch-session-${temp.root.name}", Context.MODE_PRIVATE))
        fun repository() = allocate(VehicleContextRepository::class.java).also {
            field(it, "connectionModeStore", modes)
            field(it, "settingsDataStore", settings)
            field(it, "sessionStore", sessions)
        }
        suspend fun close() = job.cancelAndJoin()
    }

    @Test fun modeAbaEpochSurvivesFileBackedDataStoreReconstruction() = runBlocking {
        var disk = Disk()
        try {
            disk.modes.set(ConnectionMode.SELF_HOSTED)
            val before = disk.modes.persistentIdentityEpoch()
            disk.modes.set(ConnectionMode.TESLA_CLOUD)
            disk.modes.set(ConnectionMode.SELF_HOSTED)
            assertEquals(before + 2, disk.modes.persistentIdentityEpoch())
            disk.close()
            disk = Disk()
            assertEquals(ConnectionMode.SELF_HOSTED, disk.modes.mode.first())
            assertEquals(before + 2, disk.modes.persistentIdentityEpoch())
            disk.modes.set(ConnectionMode.SELF_HOSTED)
            assertEquals("unchanged mode is not an identity transition", before + 2, disk.modes.persistentIdentityEpoch())
        } finally { disk.close() }
    }

    @Test fun settingsUrlAuthAndClearKeepMonotonicEpochAcrossRebuild() = runBlocking {
        var disk = Disk()
        try {
            val initial = disk.settings.persistentIdentityEpoch()
            disk.settings.saveServerUrl("https://fixture-a.invalid")
            disk.settings.saveHttpBasicAuth("fixture-user", "fixture-password")
            disk.settings.saveApiToken("fixture-token")
            assertEquals(initial + 3, disk.settings.persistentIdentityEpoch())
            disk.settings.clearSettings()
            assertEquals(initial + 4, disk.settings.persistentIdentityEpoch())
            disk.close()
            disk = Disk()
            assertEquals(initial + 4, disk.settings.persistentIdentityEpoch())
            assertEquals("", disk.settings.settings.first().serverUrl)
            disk.settings.saveServerUrl("https://fixture-a.invalid")
            assertEquals(initial + 5, disk.settings.persistentIdentityEpoch())
        } finally { disk.close() }
    }

    @Test fun sessionSaveClearSameAccountSaveSurvivesStoreReconstruction() = runBlocking {
        val disk = Disk()
        try {
            val prefs = context.getSharedPreferences("epoch-session-${temp.root.name}", Context.MODE_PRIVATE)
            disk.sessions.save("fixture-access", "fixture-refresh", 3600, "fixture-account")
            val saved = disk.sessions.persistentIdentityEpoch()
            disk.sessions.clear()
            val cleared = JourVoltSessionStore(prefs)
            assertNull(cleared.current())
            assertEquals(saved + 1, cleared.persistentIdentityEpoch())
            cleared.save("fixture-access-2", "fixture-refresh-2", 3600, "fixture-account")
            val reopened = JourVoltSessionStore(prefs)
            assertEquals("fixture-account", reopened.current()!!.userId)
            assertEquals(saved + 2, reopened.persistentIdentityEpoch())
        } finally { disk.close() }
    }

    @Test fun actualContinuationFenceSurvivesRestartButRejectsAbaForAllThreeStores() = runBlocking {
        var disk = Disk()
        try {
            disk.modes.set(ConnectionMode.SELF_HOSTED)
            disk.settings.saveServerUrl("https://fixture-a.invalid")
            val original = disk.repository().captureContinuationFence()
            disk.close()
            disk = Disk()
            assertEquals("process generations must not invalidate a durable continuation", original,
                disk.repository().captureContinuationFence())
            disk.modes.set(ConnectionMode.TESLA_CLOUD)
            disk.modes.set(ConnectionMode.SELF_HOSTED)
            val afterMode = disk.repository().captureContinuationFence()
            assertNotEquals(original, afterMode)
            disk.settings.saveServerUrl("https://fixture-b.invalid")
            disk.settings.saveServerUrl("https://fixture-a.invalid")
            val afterSettings = disk.repository().captureContinuationFence()
            assertNotEquals(afterMode, afterSettings)
            disk.sessions.save("fixture-access", "fixture-refresh", 3600, "fixture-account")
            disk.sessions.clear()
            val afterSession = disk.repository().captureContinuationFence()
            assertNotEquals(afterSettings, afterSession)
            disk.close()
            disk = Disk()
            assertEquals(afterSession, disk.repository().captureContinuationFence())
        } finally { disk.close() }
    }
}

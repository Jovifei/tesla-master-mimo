package com.matelink.data.local

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import com.matelink.data.local.dao.ChargeCostOverrideDao
import com.matelink.data.local.entity.ChargeCostOverride
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class LegacyChargeCostMigrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val rawKey = stringPreferencesKey("charge_total_overrides")
    private val migratedKey = booleanPreferencesKey("charge_total_overrides_migrated")
    private val context get() = RuntimeEnvironment.getApplication()

    private fun secure(): SecureSettingsDataStore {
        val type = Class.forName("sun.misc.Unsafe")
        val unsafe = type.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val store = type.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, SecureSettingsDataStore::class.java) as SecureSettingsDataStore
        fun field(name: String, value: Any) = SecureSettingsDataStore::class.java
            .getDeclaredField(name).apply { isAccessible = true }.set(store, value)
        field("securePrefs", context.getSharedPreferences("legacy-secure-${temp.root.name}", Context.MODE_PRIVATE))
        field("_apiTokenFlow", MutableStateFlow(""))
        field("_httpBasicPasswordFlow", MutableStateFlow(""))
        field("_amapKeyFlow", MutableStateFlow(""))
        return store
    }

    private inner class Disk(name: String) {
        private val job = SupervisorJob()
        val preferences = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(temp.root, "$name.preferences_pb") }
        )
        val settings = SettingsDataStore(preferences, secure(),
            context.getSharedPreferences("legacy-language-${temp.root.name}", Context.MODE_PRIVATE))
        val database = Room.databaseBuilder(context, StatsDatabase::class.java,
            File(temp.root, "$name.db").absolutePath).allowMainThreadQueries().build()
        val dao = database.chargeCostOverrideDao()

        suspend fun setRaw(value: String) { preferences.edit { it[rawKey] = value } }
        suspend fun raw() = preferences.data.first()[rawKey]
        suspend fun migrated() = preferences.data.first()[migratedKey] ?: false
        suspend fun close() { database.close(); job.cancelAndJoin() }
    }

    @Test fun completeMigrationKeepsOriginalAndSurvivesReopen() = runBlocking {
        val raw = """{"-1:2":0,"-1:3":12.75}"""
        var disk = Disk("complete")
        try {
            disk.setRaw(raw)
            val store = ChargeCostOverrideStore(disk.dao, disk.settings)
            assertEquals(listOf(0.0, 12.75), store.getAllRecords().sortedBy { it.chargeId }.map { it.manualTotalAmount })
            assertTrue(disk.migrated())
            assertEquals(raw, disk.raw())
            assertEquals(2, store.getAllRecords().size)
            disk.close()
            disk = Disk("complete")
            assertEquals(raw, disk.raw())
            assertEquals(2, ChargeCostOverrideStore(disk.dao, disk.settings).getAllRecords().size)
        } finally { disk.close() }
    }

    @Test fun corruptJsonIsRetainedAndLegacyEditCannotOverwriteIt() = runBlocking {
        val disk = Disk("corrupt")
        try {
            val raw = "{"
            disk.setRaw(raw)
            val store = ChargeCostOverrideStore(disk.dao, disk.settings)
            assertTrue(store.getAllRecords().isEmpty())
            assertFalse(disk.migrated())
            assertEquals(raw, disk.raw())
            try {
                disk.settings.saveChargeTotalOverride("-1:2", 9.0)
                fail("unreadable original must not be rewritten")
            } catch (_: org.json.JSONException) { }
            assertEquals(raw, disk.raw())
            disk.setRaw("""{"-1:2":9}""")
            assertEquals(9.0, store.getAllRecords().single().manualTotalAmount, 0.0)
            assertTrue(disk.migrated())
        } finally { disk.close() }
    }

    @Test fun partialLegacyDataIsRetainedAndValidAmountsMigrateIdempotently() = runBlocking {
        val disk = Disk("partial")
        try {
            val raw = """{"-1:2":12.5,"bad-key":6,"-1:3":"not-a-number","-1:4":-1}"""
            disk.setRaw(raw)
            val store = ChargeCostOverrideStore(disk.dao, disk.settings)
            repeat(2) { assertEquals(listOf(12.5), store.getAllRecords().map { it.manualTotalAmount }) }
            assertFalse(disk.migrated())
            assertEquals(raw, disk.raw())

            // A legacy edit must retain unrelated fields, even while they cannot migrate.
            disk.settings.saveChargeTotalOverride("-1:5", 0.0)
            val edited = JSONObject(disk.raw()!!)
            assertEquals(6.0, edited.getDouble("bad-key"), 0.0)
            assertEquals("not-a-number", edited.getString("-1:3"))
            assertEquals(-1.0, edited.getDouble("-1:4"), 0.0)
            assertEquals(setOf(2, 5), store.getAllRecords().map { it.chargeId }.toSet())
            assertFalse(disk.migrated())

            val repaired = """{"-1:2":12.5,"-1:5":0}"""
            disk.setRaw(repaired)
            assertEquals(setOf(2, 5), store.getAllRecords().map { it.chargeId }.toSet())
            assertTrue(disk.migrated())
            assertEquals(repaired, disk.raw())
        } finally { disk.close() }
    }

    @Test fun interruptedRoomInsertCanRetryWithoutLosingOrDuplicatingAmounts() = runBlocking {
        var disk = Disk("interrupted")
        try {
            val raw = """{"-1:2":12.5,"-1:3":8.5}"""
            disk.setRaw(raw)
            val dao = disk.dao
            var failOnce = true
            val interrupted = object : ChargeCostOverrideDao by dao {
                override suspend fun insertLegacyIfAbsent(overrides: List<ChargeCostOverride>) {
                    if (failOnce) {
                        failOnce = false
                        dao.insertLegacyIfAbsent(overrides.take(1))
                        throw IllegalStateException("synthetic interruption")
                    }
                    dao.insertLegacyIfAbsent(overrides)
                }
            }
            val store = ChargeCostOverrideStore(interrupted, disk.settings)
            try {
                store.ensureLegacyMigrated()
                fail("injected failure must be propagated")
            } catch (e: IllegalStateException) { assertEquals("synthetic interruption", e.message) }
            assertFalse(disk.migrated())
            assertEquals(raw, disk.raw())
            assertEquals(1, dao.getAll().size)
            assertEquals(setOf(12.5, 8.5), store.getAllRecords().map { it.manualTotalAmount }.toSet())
            assertTrue(disk.migrated())
            disk.close()
            disk = Disk("interrupted")
            assertEquals(raw, disk.raw())
            assertEquals(2, ChargeCostOverrideStore(disk.dao, disk.settings).getAllRecords().size)
        } finally { disk.close() }
    }

    @Test fun concurrentEditOrExistingDifferentValueNeverCompletesOrDiscardsOriginal() = runBlocking {
        val disk = Disk("conflict")
        try {
            val original = """{"-1:2":12.5}"""
            val edited = """{"-1:2":19.0}"""
            disk.setRaw(original)
            val dao = disk.dao
            var changeOnce = true
            val racing = object : ChargeCostOverrideDao by dao {
                override suspend fun insertLegacyIfAbsent(overrides: List<ChargeCostOverride>) {
                    dao.insertLegacyIfAbsent(overrides)
                    if (changeOnce) { changeOnce = false; disk.setRaw(edited) }
                }
            }
            val store = ChargeCostOverrideStore(racing, disk.settings)
            store.ensureLegacyMigrated()
            assertFalse(disk.migrated())
            assertEquals(edited, disk.raw())
            store.ensureLegacyMigrated()
            assertFalse(disk.migrated())
            assertEquals(edited, disk.raw())
            assertEquals(12.5, dao.getAll().single().manualTotalAmount, 0.0)
        } finally { disk.close() }
    }
}

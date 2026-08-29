package com.matelink.data.local

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDataStoreTpmsAlertProfileAndroidTest {
    private lateinit var context: Context
    private lateinit var secureStore: SecureSettingsDataStore
    private lateinit var settings: SettingsDataStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        secureStore = SecureSettingsDataStore(context)
        settings = SettingsDataStore(context, secureStore)
        runBlocking {
            settings.clearSettings()
        }
        secureStore.clearAll()
    }

    @After
    fun tearDown() {
        runBlocking { settings.clearSettings() }
        secureStore.clearAll()
    }

    @Test
    fun defaultProfileIsDisabledAndAbsent() = runBlocking {
        assertNull(settings.getTpmsAlertProfile(1))
        assertFalse(TpmsAlertProfile.modelYSuggestion.enabled)
    }

    @Test
    fun invalidProfileCannotBecomeEnabled() = runBlocking {
        listOf(
            TpmsAlertProfile(Double.NaN, 2.6, 3.4, enabled = true),
            TpmsAlertProfile(Double.POSITIVE_INFINITY, 2.6, 3.4, enabled = true),
            TpmsAlertProfile(2.9, 0.0, 3.4, enabled = true),
            TpmsAlertProfile(2.9, 3.1, 3.4, enabled = true),
            TpmsAlertProfile(2.9, 2.6, 2.6, enabled = true)
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { settings.saveTpmsAlertProfile(1, invalid) }
            }
            assertNull(settings.getTpmsAlertProfile(1))
        }
    }

    @Test
    fun validExplicitlySavedProfileBecomesEnabled() = runBlocking {
        settings.saveTpmsAlertProfile(1, TpmsAlertProfile(2.9, 2.6, 3.4, enabled = true))

        assertEquals(true, settings.getTpmsAlertProfile(1)?.enabled)
    }

    @Test
    fun malformedEntryDoesNotEraseValidEntriesWhenSavingOrClearing() = runBlocking {
        val validCarOne = TpmsAlertProfile(2.9, 2.6, 3.4, enabled = true)
        val validCarThree = TpmsAlertProfile(3.0, 2.7, 3.5, enabled = true)
        context.dataStore.edit { preferences ->
            preferences[stringPreferencesKey("tpms_alert_profiles")] =
                """{"1":${validCarOne.toJson()},"2":{"targetBar":"bad"}}"""
        }

        settings.saveTpmsAlertProfile(3, validCarThree)
        assertEquals(validCarOne, settings.getTpmsAlertProfile(1))
        assertNull(settings.getTpmsAlertProfile(2))
        assertEquals(validCarThree, settings.getTpmsAlertProfile(3))

        settings.clearTpmsAlertProfile(3)
        assertEquals(validCarOne, settings.getTpmsAlertProfile(1))
        assertNull(settings.getTpmsAlertProfile(3))
    }

    @Test
    fun validProfilesRoundTripIndependentlyForMultipleCars() = runBlocking {
        val carOne = TpmsAlertProfile(2.9, 2.6, 3.4, enabled = true)
        val carTwo = TpmsAlertProfile(3.0, 2.7, 3.5, enabled = true)

        settings.saveTpmsAlertProfile(1, carOne)
        settings.saveTpmsAlertProfile(2, carTwo)

        assertTrue(settings.getTpmsAlertProfile(1)?.enabled == true)
        assertEquals(carOne, settings.getTpmsAlertProfile(1))
        assertEquals(carTwo, settings.getTpmsAlertProfile(2))
    }
}

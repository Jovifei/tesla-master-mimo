package com.matelink.data.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AmapSettingsStoreTest {
    @Test
    fun savesEncryptedKeyAndClearsItWithoutChangingPrivacyConsent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val secureStore = SecureSettingsDataStore(context)
        val store = AmapSettingsStore(context, secureStore)
        store.clearKey()
        store.setPrivacyAgreed(true)

        assertTrue(store.saveKey(" synthetic-amap-key ", sdkWasInitialized = false))
        assertEquals("synthetic-amap-key", secureStore.getAmapKey())

        assertTrue(store.saveKey("replacement-key", sdkWasInitialized = true))
        assertTrue(store.settings.first().restartRequired)
        assertTrue(store.saveKey("replacement-key", sdkWasInitialized = true))
        assertTrue(store.settings.first().restartRequired)

        store.clearKey()
        assertEquals("", secureStore.getAmapKey())
        assertFalse(store.settings.first().hasKey)
        assertFalse(store.settings.first().restartRequired)
        assertTrue(store.settings.first().privacyAgreed)
    }
}

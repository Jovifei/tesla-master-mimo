package com.matelink.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDataStoreCurrencyTest {
    @Test
    fun freshInstallDefaultsToCny() {
        assertEquals("CNY", AppSettings().currencyCode)
        assertEquals("CNY", defaultCurrencyCode(null, ""))
    }

    @Test
    fun legacyConnectionKeepsEuroCompatibilityDefault() {
        assertEquals("EUR", defaultCurrencyCode("https://teslamate.example", ""))
        assertEquals("EUR", defaultCurrencyCode(null, "legacy-token"))
    }
}

package com.matelink.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDataStoreCurrencyMigrationTest {
    @Test
    fun missingLegacyCurrencyKeepsTheSelfHostedCompatibilityDefault() {
        assertEquals("EUR", defaultCurrencyCode("http://192.168.1.10:8080", "legacy-token"))
    }
}

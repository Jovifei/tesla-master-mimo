package com.matelink.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsViewModelTest {
    @Test fun newInstall_displaysHttpsPrefixWithoutPersistingAnything() {
        assertEquals("https://", serverUrlForDisplay(""))
    }

    @Test fun existingAddress_isPreservedForUpgradeCompatibility() {
        assertEquals("http://192.168.0.104:8080", serverUrlForDisplay("http://192.168.0.104:8080"))
        assertEquals("https://api.example.com", serverUrlForDisplay("https://api.example.com"))
    }
}

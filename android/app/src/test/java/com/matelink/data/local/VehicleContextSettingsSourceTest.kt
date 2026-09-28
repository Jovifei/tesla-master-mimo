package com.matelink.data.local

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleContextSettingsSourceTest {
    @Test
    fun selfHostedScopeUsesTheSameSettingsStoreAsTheApiClient() {
        val source = File("src/main/java/com/matelink/data/local/VehicleContextRepository.kt").readText()

        assertTrue(source.contains("private val settingsDataStore: SettingsDataStore"))
        assertTrue(source.contains("settingsDataStore.settings.first().serverUrl"))
        assertFalse(source.contains("private val settingsRepository: SettingsRepository"))
    }

    @Test
    fun transientCloudVehicleDiscoveryFailureUsesThePersistedScopedMapping() {
        val source = File("src/main/java/com/matelink/data/local/VehicleContextRepository.kt").readText()
        val fallback = source.indexOf("cachedContextForRemote(remoteApiCarId, scope)?.let { return it }")
        val failure = source.indexOf("throw HistoryIdentityUnavailableException()", fallback)

        assertTrue(fallback >= 0)
        assertTrue(failure > fallback)
    }
}

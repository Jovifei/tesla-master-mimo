package com.matelink.data.local

import com.matelink.data.model.Instance
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionModeMigrationTest {
    @Test
    fun freshInstallDefaultsToTeslaCloud() {
        assertEquals(
            ConnectionMode.TESLA_CLOUD,
            migratedConnectionMode(AppSettings(), emptyList(), hasJourVoltSession = false)
        )
    }

    @Test
    fun savedLegacyConnectionRemainsSelfHosted() {
        val settings = AppSettings(serverUrl = "https://teslamate.example", apiToken = "token")
        assertEquals(
            ConnectionMode.SELF_HOSTED,
            migratedConnectionMode(settings, emptyList(), hasJourVoltSession = false)
        )
    }

    @Test
    fun savedInstanceAlsoMigratesToSelfHosted() {
        val instance = Instance(
            id = "legacy",
            name = "Legacy",
            serverUrl = "https://teslamate.example",
            carId = 1
        )
        assertEquals(
            ConnectionMode.SELF_HOSTED,
            migratedConnectionMode(AppSettings(), listOf(instance), hasJourVoltSession = false)
        )
    }

    @Test
    fun existingJourVoltSessionUsesCloudMode() {
        val settings = AppSettings(serverUrl = "https://legacy.example")
        assertEquals(
            ConnectionMode.TESLA_CLOUD,
            migratedConnectionMode(settings, emptyList(), hasJourVoltSession = true)
        )
    }

    @Test
    fun persistedCloudModeDoesNotOverrideLegacySelfHostedConnection() {
        assertEquals(
            ConnectionMode.SELF_HOSTED,
            resolveInitialConnectionMode(
                persistedMode = ConnectionMode.TESLA_CLOUD,
                settings = AppSettings(serverUrl = "https://legacy.example", apiToken = "token"),
                instances = emptyList(),
                hasJourVoltSession = false
            )
        )
    }
}

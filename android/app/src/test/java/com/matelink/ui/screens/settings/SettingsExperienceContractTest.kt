package com.matelink.ui.screens.settings

import java.io.File
import com.matelink.data.local.ConnectionMode
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsExperienceContractTest {
    @Test
    fun cloudSettingsDoNotTreatTheHttpsPlaceholderAsASelfHostedServer() {
        assertTrue(!hasExplicitServerUrl("https://"))
        assertTrue(!hasExplicitServerUrl("  https://  "))
        assertTrue(hasExplicitServerUrl("https://teslamate.example.com"))
        assertTrue(shouldKeepCloudSettings(ConnectionMode.TESLA_CLOUD, "https://", false))
        assertTrue(!shouldSwitchToSelfHosted("https://", false))
        assertTrue(shouldSwitchToSelfHosted("https://selfhosted.example.com", false))
    }

    @Test
    fun advancedNetworkExplainsCloudAndSelfHostedModesInPanels() {
        val source = File("src/main/java/com/matelink/ui/screens/settings/SettingsScreen.kt").readText()
        val viewModel = File("src/main/java/com/matelink/ui/screens/settings/SettingsViewModel.kt").readText()

        assertTrue(source.contains("ConnectionMode.TESLA_CLOUD"))
        assertTrue(source.contains("settings_cloud_connection_description"))
        assertTrue(source.contains("settings_self_hosted_connection_description"))
        assertTrue(source.contains("SettingsPanelCard"))
        assertTrue(viewModel.contains("connectionMode == ConnectionMode.SELF_HOSTED"))
    }

    @Test
    fun connectionModeCanSwitchWithoutDeletingEitherSavedConnection() {
        val source = File("src/main/java/com/matelink/ui/screens/settings/SettingsScreen.kt").readText()
        val viewModel = File("src/main/java/com/matelink/ui/screens/settings/SettingsViewModel.kt").readText()

        assertTrue(source.contains("onConnectionModeChange"))
        assertTrue(source.contains("settings_switch_to_cloud"))
        assertTrue(source.contains("settings_switch_to_self_hosted"))
        assertTrue(viewModel.contains("fun switchConnectionMode(mode: ConnectionMode"))
        assertTrue(viewModel.contains("connectionModeStore.set(mode)"))
        val shell = File("src/main/java/com/matelink/ui/navigation/StartDestinationViewModel.kt").readText()
        assertTrue(shell.contains("connectionModeStore.mode.collect"))
        assertTrue(shell.contains("_connectionMode.value = updatedMode"))
    }

    @Test
    fun currentReleaseShowsVersionAndLocalizedRepairNotes() {
        val gradle = File("build.gradle.kts").readText()
        // The candidate has an explicit release identity, but neither release
        // note view may hardcode a stale version independent of BuildConfig.
        val code = Regex("versionCode\\s*=\\s*(\\d+)").find(gradle)
            ?.groupValues?.get(1)?.toInt()
        val version = Regex("versionName\\s*=\\s*\"([^\"]+)\"").find(gradle)
            ?.groupValues?.get(1)
        assertEquals(46, code)
        assertEquals("2.1.27", version)
        assertEquals(version, com.matelink.BuildConfig.VERSION_NAME)
        val settings = File("src/main/java/com/matelink/ui/screens/settings/SettingsScreen.kt").readText()
        assertEquals(2, Regex("stringResource\\(R\\.string\\.settings_release_notes_version,\\s*com\\.matelink\\.BuildConfig\\.VERSION_NAME\\)")
            .findAll(settings).count())
        assertEquals("MateLink %1\$s", stringValue("values", "settings_release_notes_version"))
        assertEquals("MateLink %1\$s", stringValue("values-zh", "settings_release_notes_version"))
        assertEquals(
            "本次更新",
            stringValue("values-zh", "settings_release_notes_title")
        )
        val zh = stringValue("values-zh", "settings_release_notes_body")
        val en = stringValue("values", "settings_release_notes_body")
        assertTrue(zh.contains("自动"))
        assertTrue(zh.contains("充电") && zh.contains("未知") && zh.contains("估算"))
        assertTrue(en.contains("automatic", ignoreCase = true))
        assertTrue(en.contains("charging", ignoreCase = true))
        assertTrue(en.contains("unknown", ignoreCase = true))
        assertTrue(en.contains("estimated", ignoreCase = true))
    }

    private fun stringValue(directory: String, name: String): String {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/res/$directory/strings.xml"))
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length)
            .map { strings.item(it) }
            .first { it.attributes.getNamedItem("name").nodeValue == name }
            .textContent
    }
}

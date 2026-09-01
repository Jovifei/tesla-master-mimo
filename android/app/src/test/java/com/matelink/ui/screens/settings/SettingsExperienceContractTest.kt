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
    fun currentReleaseShowsVersionAndLocalizedRepairNotes() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 15"))
        assertTrue(gradle.contains("versionName = \"1.4.3\""))
        assertEquals(
            "本次更新",
            stringValue("values-zh", "settings_release_notes_title")
        )
        assertTrue(stringValue("values-zh", "settings_release_notes_body").contains("待机"))
        assertTrue(stringValue("values", "settings_release_notes_body").contains("standby", ignoreCase = true))
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

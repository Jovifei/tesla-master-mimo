package com.matelink.p0

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.MainActivity
import com.matelink.data.local.SettingsDataStore
import com.matelink.receiver.DebugEndpointReceiver
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class QualificationFullAppSmokeTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun appRendersFixtureDashboardDrivesAndCharges() {
        runBlocking { seedConnection() }

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntilAtLeastOne("Synthetic Qualification Vehicle")
            composeRule.onNodeWithText("Synthetic Qualification Vehicle", substring = true).assertExists()

            composeRule.onNodeWithContentDescription("Drives", useUnmergedTree = true).performClick()
            composeRule.waitUntilAtLeastOne("Fixture Office")
            composeRule.assertAtLeastOne("Fixture Office")

            composeRule.onNodeWithContentDescription("Charges", useUnmergedTree = true).performClick()
            composeRule.waitUntilAtLeastOne("Fixture Charger")
            composeRule.assertAtLeastOne("Fixture Charger")
        }
    }

    private suspend fun seedConnection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = settingsDataStore(context)
        settings.saveConnectionSettings(
            serverUrl = "http://10.0.2.2:18080",
            apiToken = "synthetic-qualification-key",
            currencyCode = "EUR"
        )
        settings.saveLastSelectedCarId(101)
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilAtLeastOne(text: String) {
        waitUntil(timeoutMillis = 30_000) {
            onAllNodes(hasText(text, substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.assertAtLeastOne(text: String) {
        check(onAllNodes(hasText(text, substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty()) {
            "expected at least one node with requested fixture text"
        }
    }

    private fun settingsDataStore(context: Context): SettingsDataStore =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            DebugEndpointReceiver.SettingsEntryPoint::class.java
        ).settingsDataStore()
}

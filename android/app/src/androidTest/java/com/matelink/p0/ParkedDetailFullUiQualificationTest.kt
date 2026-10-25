package com.matelink.p0

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.MainActivity
import com.matelink.R
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ParkedDetailFullUiQualificationTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun parkedDetailNavigatesFromDrivesAndRendersPartialValuesHonestly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parkedTitle = context.getString(R.string.drive_history_parked_at, "Fixture Office")
        val energyTitle = context.getString(R.string.parked_energy_title)
        val environmentTitle = context.getString(R.string.parked_environment_title)
        runBlocking { prepareQualificationState(context, scenario = "normal") }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNodeWithText(context.getString(R.string.nav_drives), useUnmergedTree = true).performClick()
            scrollUntilText(parkedTitle)
            composeRule.onNodeWithText(parkedTitle).performClick()
            composeRule.waitForText(energyTitle)
            composeRule.onNodeWithText(environmentTitle).assertExists()
            // Entirely synthetic fixture in the isolated API35 emulator, never
            // a phone screenshot or an address from a real user's history.
            captureFixtureScreen("matelink_fixture_parked_full")

            scenario.recreate()
            composeRule.waitForText(energyTitle)
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
            scrollUntilText(parkedTitle)

            setFixtureScenario("parked_partial")
            composeRule.onNodeWithText(parkedTitle).performClick()
            composeRule.waitForText(context.getString(R.string.parked_no_energy_data))
            composeRule.onNodeWithText(context.getString(R.string.parked_no_battery_data)).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.parked_no_temperature_data)).assertExists()
            composeRule.onNodeWithText(energyTitle).assertExists()
            captureFixtureScreen("matelink_fixture_parked_partial")
        }
    }

    private fun captureFixtureScreen(name: String) {
        check(name in setOf("matelink_fixture_parked_full", "matelink_fixture_parked_partial"))
        composeRule.waitForIdle()
        // screencap runs under the emulator shell identity and writes outside
        // test app data; later tests may reset their own isolated Room state.
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /sdcard/Download/$name.png"
        )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { stream ->
            stream.copyTo(java.io.ByteArrayOutputStream())
        }
    }

    private fun scrollUntilText(text: String) {
        val verticalList = hasScrollAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(verticalList, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        repeat(8) {
            if (composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            composeRule.onNode(verticalList, useUnmergedTree = true).performTouchInput { swipeUp() }
        }
        composeRule.waitForText(text)
    }
}

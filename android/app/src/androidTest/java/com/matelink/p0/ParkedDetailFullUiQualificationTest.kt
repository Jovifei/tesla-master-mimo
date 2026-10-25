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
        val batteryTitle = context.getString(R.string.parked_battery_section_title)
        val cloudTitle = context.getString(R.string.parked_cloud_section_title)
        runBlocking { prepareQualificationState(context, scenario = "normal") }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNodeWithText(context.getString(R.string.nav_drives), useUnmergedTree = true).performClick()
            // The archived synthetic July 18 events are older than the default seven-day window.
            // Explicitly select a history window that includes the fixture before asserting cards.
            composeRule.selectFixtureHistoryWindow(context)
            scrollUntilText(parkedTitle)
            composeRule.onNodeWithText(parkedTitle).performClick()
            composeRule.waitForText(batteryTitle)
            // The local Room battery section can render before the remote telemetry receipt.
            composeRule.waitForText(cloudTitle)
            composeRule.onNodeWithText(context.getString(R.string.parked_start_battery)).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.parked_end_battery)).assertExists()
            // Entirely synthetic fixture in the isolated API35 emulator, never
            // a phone screenshot or an address from a real user's history.
            captureFixtureScreen("matelink_fixture_parked_full")

            scenario.recreate()
            composeRule.waitForText(batteryTitle)
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
            scrollUntilText(parkedTitle)

            setFixtureScenario("parked_partial")
            composeRule.onNodeWithText(parkedTitle).performClick()
            composeRule.waitForText(context.getString(R.string.parked_cloud_detail_pending))
            composeRule.onNodeWithText(batteryTitle).assertExists()
            composeRule.onNodeWithText(cloudTitle).assertDoesNotExist()
            composeRule.onNodeWithText(context.getString(R.string.parked_estimated_energy)).assertDoesNotExist()
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

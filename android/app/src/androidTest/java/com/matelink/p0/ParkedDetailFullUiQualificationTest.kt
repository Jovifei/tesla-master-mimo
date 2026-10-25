package com.matelink.p0

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
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
        // Capture the actual Compose surface. Shell screencap can produce valid
        // full-black PNGs under the headless API35 graphics backend.
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        check(bitmap.width >= 320 && bitmap.height >= 480)
        val observed = mutableSetOf<Int>()
        for (iy in 1..9) for (ix in 1..9) {
            observed += bitmap.getPixel(bitmap.width * ix / 10, bitmap.height * iy / 10)
        }
        check(observed.size >= 3) { "Synthetic Compose screenshot has no visible page content" }
        val dir = checkNotNull(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        )
        java.io.File(dir, "$name.png").outputStream().use { output ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
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

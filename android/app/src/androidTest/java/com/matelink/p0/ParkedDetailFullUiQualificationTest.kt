package com.matelink.p0

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

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
            composeRule.scrollUntilText(parkedTitle)
            composeRule.onNodeWithText(parkedTitle).performClick()
            composeRule.waitForText(batteryTitle)
            // Wait for the server's synthetic historical temperatures, not the
            // immediately visible Room-only placeholder panel.
            composeRule.waitForText("21.8")
            composeRule.onNodeWithText(cloudTitle).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.parked_start_battery)).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.parked_end_battery)).assertExists()
            assertSixTelemetryLabels(context)
            composeRule.onNodeWithText("12 ${context.getString(R.string.parked_sample_unit)}").assertExists()
            // Entirely synthetic fixture in the isolated API35 emulator, never
            // a phone screenshot or an address from a real user's history.
            captureFixtureScreen("matelink_fixture_parked_full")

            scenario.recreate()
            composeRule.waitForText(batteryTitle)
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
            composeRule.scrollUntilText(parkedTitle)

            setFixtureScenario("parked_partial")
            composeRule.onNodeWithText(parkedTitle).performClick()
            composeRule.waitUntil(30_000) {
                composeRule.onAllNodesWithText(context.getString(R.string.not_available))
                    .fetchSemanticsNodes().size >= 3
            }
            composeRule.onNodeWithText(batteryTitle).assertExists()
            composeRule.onNodeWithText(cloudTitle).assertExists()
            assertSixTelemetryLabels(context)
            composeRule.onNodeWithText(context.getString(R.string.parked_telemetry_missing_note)).assertExists()
            captureFixtureScreen("matelink_fixture_parked_partial")
        }
    }

    @Test fun parkedDetailShowsTwoDistinctChargeSessionsAndOnlyQualifiedIdleSocRate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parkedTitle = context.getString(R.string.drive_history_parked_at, "Fixture Office")
        runBlocking { prepareQualificationState(context, scenario = "parked_multi_charge") }
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithText(context.getString(R.string.nav_drives), useUnmergedTree = true).performClick()
            composeRule.selectFixtureHistoryWindow(context)
            composeRule.scrollUntilText(parkedTitle)
            composeRule.onNodeWithText(parkedTitle).performClick()
            composeRule.waitForText(context.getString(R.string.parked_recorded_charge_count, 2))
            composeRule.onNodeWithText("6.00 kWh/hour", substring = true).assertExists()
            composeRule.onNodeWithText("4.00 kWh/hour", substring = true).assertExists()
            composeRule.onNodeWithText("1.7 pp/hour", substring = true).assertExists()
            assertSixTelemetryLabels(context)
            composeRule.onNodeWithText(context.getString(R.string.parked_telemetry_missing_note)).assertExists()
            captureFixtureScreen("matelink_fixture_parked_multi_charge")
        }
    }

    private fun assertSixTelemetryLabels(context: Context) {
        listOf(R.string.parked_estimated_energy, R.string.parked_energy_per_hour,
            R.string.parked_inside_temp, R.string.parked_outside_temp,
            R.string.parked_coverage, R.string.parked_sample_count).forEach { label ->
            composeRule.onNodeWithText(context.getString(label)).assertExists()
        }
    }

    private fun captureFixtureScreen(name: String) {
        check(name in setOf("matelink_fixture_parked_full", "matelink_fixture_parked_partial",
            "matelink_fixture_parked_multi_charge"))
        val wake = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "input keyevent KEYCODE_WAKEUP"
        )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(wake).use { it.readBytes() }
        composeRule.waitForIdle()
        // Capture the actual Compose root. Headless emulator framebuffer
        // screencap can be an all-black PNG even after semantics assertions pass.
        val bitmap = composeRule.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        var dark = 0
        var bright = 0
        for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
            val color = bitmap.getPixel(x, y)
            val level = (android.graphics.Color.red(color) + android.graphics.Color.green(color) +
                android.graphics.Color.blue(color)) / 3
            if (level < 80) dark++
            if (level > 180) bright++
        }
        val output = ByteArrayOutputStream()
        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        val bytes = output.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val dir = checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        val image = File(dir, "$name.png").apply { writeBytes(bytes) }
        val proof = File(dir, "$name.proof").apply {
            writeText("$sha ${bitmap.width} ${bitmap.height} $dark $bright")
        }
        // Public Download is limited to synthetic emulator artifacts. No real
        // phone or owner history is read or copied by this instrumentation.
        for (file in listOf(image, proof)) {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "cp ${file.absolutePath} /sdcard/Download/${file.name}"
            )
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        check(dark > 30 && bright > 30) { "synthetic Compose screenshot lacks visible UI contrast" }
    }

}

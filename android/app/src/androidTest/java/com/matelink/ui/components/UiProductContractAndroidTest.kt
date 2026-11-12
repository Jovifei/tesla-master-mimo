package com.matelink.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Only synthetic records, no MainActivity, authorization or provider calls. */
@RunWith(AndroidJUnit4::class)
class UiProductContractAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val dates = (0..3).map { LocalDate.of(2026, 11, 9).minusDays(it.toLong()) }
    private val groups = dates.mapIndexed { i, d -> HistoryDateGroup(d, d.toString(), listOf("drive-$i", "parked-$i")) }
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.getExternalFilesDir(null), "ui-product-evidence").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun stackExposesOnlyOlderDatesAndNavigatesWithVerticalHeaderGestures() {
        val scope = mutableStateOf("synthetic-A")
        compose.setContent { MateLinkTheme(darkTheme = false) {
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                VerticalDayStack(groups, scope.value) { group ->
                    Column(Modifier.padding(16.dp)) { group.items.forEach { Text(it) } }
                }
            }
        } }
        compose.onNodeWithText("drive-0").assertIsDisplayed()
        compose.onNodeWithText("parked-0").assertIsDisplayed()
        compose.onNodeWithText("drive-1").assertDoesNotExist()
        compose.onNodeWithTag("day_stack_rear_1").assertIsDisplayed()
        compose.onNodeWithTag("day_stack_rear_2").assertIsDisplayed()
        capture("stack-neutral")
        compose.onNodeWithTag("day_stack_gesture").performTouchInput { swipeUp() }
        compose.onNodeWithText("drive-1").assertIsDisplayed()
        compose.onNodeWithText("parked-1").assertIsDisplayed()
        compose.onNodeWithTag("day_stack_gesture").performTouchInput { swipeDown() }
        compose.onNodeWithText("drive-0").assertIsDisplayed()
        compose.onNodeWithTag("day_stack_rear_2").performClick()
        compose.onNodeWithText("drive-2").assertIsDisplayed()
        compose.runOnIdle { scope.value = "synthetic-B" }
        compose.onNodeWithText("drive-0").assertIsDisplayed()
        compose.onNodeWithTag("day_stack_newer").assertIsNotEnabled()
        repeat(3) { compose.onNodeWithTag("day_stack_older").performClick() }
        compose.onNodeWithTag("day_stack_older").assertIsNotEnabled()
        compose.onNodeWithTag("day_stack_gesture").performTouchInput { swipeUp() }
        compose.onNodeWithText("drive-3").assertIsDisplayed()
    }
    @Test fun sevenDatesCanBeRaisedAndCollapsedWithoutDroppingParking() {
        val end = LocalDate.of(2026, 11, 12)
        val week = (0..6).map { n -> val date = end.minusDays(n.toLong())
            HistoryDateGroup(date, date.toString(), listOf("trip-$n", "parking-$n")) }
        compose.setContent { MateLinkTheme(darkTheme = false) {
            Column(Modifier.width(393.dp).verticalScroll(rememberScrollState())) {
                VerticalDayStack(week, "synthetic-week") { day ->
                    Column(Modifier.padding(16.dp)) { day.items.forEach { Text(it) } }
                }
            }
        } }
        compose.onNodeWithTag("day_stack_rear_6").assertExists()
        compose.onNodeWithTag("day_stack_rear_1").performClick()
        compose.onNodeWithText("parking-1").assertIsDisplayed()
        capture("week-raised-yesterday")
        compose.onNodeWithTag("day_stack_rear_1").performClick()
        compose.onNodeWithText("parking-0").assertIsDisplayed()
        compose.onNodeWithTag("day_stack_rear_6").performClick()
        compose.onNodeWithText("trip-6").assertIsDisplayed()
        compose.onNodeWithTag("day_stack_close").performClick()
        compose.onNodeWithText("trip-0").assertIsDisplayed()
    }
    @Test fun bodyScrollDoesNotSwitchDayAndFontScalingKeepsDatesReachable() {
        compose.setContent { MateLinkTheme(darkTheme = false) {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f),
                LocalAppearance provides AppearanceState(reducedMotion = true)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    VerticalDayStack(groups, "synthetic-large") { group ->
                        Column(Modifier.testTag("day_body")) { repeat(30) { Text("${group.dateKey} item $it", Modifier.height(44.dp)) } }
                    }
                }
            }
        } }
        compose.onNodeWithTag("day_stack_rear_1").assertIsDisplayed()
        compose.onNodeWithTag("day_body").performTouchInput { swipeUp(startY = 200f, endY = 40f) }
        compose.onAllNodesWithText("2026-11-08 item 0").assertCountEquals(0)
    }
    @Test fun preferencesPersistWithoutChangingOtherSettingsAndGridRetainsZero() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = AppearancePreferences(context)
        prefs.setPalette(AppearancePalette.NEUTRAL); prefs.setReducedMotion(false)
        var renderedColor = 0L
        val scene = mutableStateOf(0)
        compose.setContent { MateLinkTheme(darkTheme = false) {
            renderedColor = MaterialTheme.colorScheme.background.value.toLong()
            Column(Modifier.width(320.dp).background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                if (scene.value == 0) AppearancePreferencePanel() else ChargeActivityGrid(listOf(
                    ChargeActivityCell("11/07", null, "Unknown"), ChargeActivityCell("11/08", 0.0, "0.00 kWh"),
                    ChargeActivityCell("11/09", 24.2, "24.2 kWh")))
            }
        } }
        compose.waitForIdle()
        val neutral = renderedColor
        compose.onNodeWithTag("appearance_cream").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(AppearancePalette.CREAM, AppearancePreferences(context).palette()); assertNotEquals(neutral, renderedColor) }
        compose.onNodeWithTag("appearance_reduced_motion").performClick()
        compose.runOnIdle { assertTrue(AppearancePreferences(context).reducedMotion()); scene.value = 1 }
        compose.onNodeWithTag("charge_cell_11/08").performClick()
        compose.onNodeWithTag("charge_cell_selection").assertTextEquals("11/08 · 0.00 kWh")
        compose.onNodeWithTag("charge_cell_11/07").performClick()
        compose.onNodeWithTag("charge_cell_selection").assertTextEquals("11/07 · Unknown")
        capture("charge-grid-cream")
        prefs.setPalette(AppearancePalette.NEUTRAL); prefs.setReducedMotion(false)
    }
    @Test fun refreshKeepsTheSelectedDateButUsesFreshRecords() {
        val source = mutableStateOf(groups)
        compose.setContent { MateLinkTheme(darkTheme = false) {
            VerticalDayStack(source.value, "refresh-scope") { group -> group.items.forEach { Text(it) } }
        } }
        compose.onNodeWithTag("day_stack_older").performClick()
        compose.runOnIdle { source.value = groups.map { if (it.dateKey == groups[1].dateKey) it.copy(items = listOf("corrected-day-1")) else it } }
        compose.onNodeWithText("corrected-day-1").assertIsDisplayed()
        compose.onNodeWithText("drive-1").assertDoesNotExist()
        compose.onNodeWithTag("day_stack_gesture").performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -30f)); cancel()
        }
        compose.onNodeWithText("corrected-day-1").assertIsDisplayed()
        compose.runOnIdle { source.value = groups.take(1) }
        compose.onNodeWithText("drive-0").assertIsDisplayed()
        compose.runOnIdle { source.value = groups }
        compose.onNodeWithText("drive-0").assertIsDisplayed()
    }
    @Test fun chargeCellsKeepPrecisionQualityAndDistinctStableKeys() {
        compose.setContent { MateLinkTheme(darkTheme = false) {
            ChargeActivityGrid(listOf(
                ChargeActivityCell("11/09", 0.0, "0 sessions", key = "2025-11-09", precision = 0),
                ChargeActivityCell("11/09", 1.25, "estimated 1.25", key = "2026-11-09", precision = 2, approximate = true)))
        } }
        compose.onNodeWithText("0").assertIsDisplayed()
        compose.onNodeWithText("≈1.25").assertIsDisplayed()
        compose.onNodeWithTag("charge_cell_2025-11-09").performClick().assertIsSelected()
        compose.onNodeWithTag("charge_cell_selection").assertTextEquals("11/09 · 0 sessions")
        compose.onNodeWithTag("charge_cell_2026-11-09").performClick().assertIsSelected()
        compose.onNodeWithTag("charge_cell_selection").assertTextEquals("11/09 · estimated 1.25")
    }

}

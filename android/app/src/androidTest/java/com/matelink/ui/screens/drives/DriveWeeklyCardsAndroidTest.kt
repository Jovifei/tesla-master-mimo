package com.matelink.ui.screens.drives

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.ui.components.HistoryDateGroup
import com.matelink.ui.components.RouteIndicator
import com.matelink.ui.components.WeeklyDayStack
import com.matelink.ui.theme.AppearanceState
import com.matelink.ui.theme.LocalAppearance
import com.matelink.ui.theme.MateLinkTheme
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.File

@RunWith(AndroidJUnit4::class)
class DriveWeeklyCardsAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val groups = (0L..6L).map { offset ->
        val date = LocalDate.of(2026, 11, 11).minusDays(offset)
        HistoryDateGroup(date, date.toString(), listOf("body-$offset"))
    }

    @Test fun allDatesRemainReachableAndSelectedDateReturnsToTodayOnSecondTap() {
        compose.setContent { MateLinkTheme(darkTheme = false) {
            CompositionLocalProvider(LocalAppearance provides AppearanceState(reducedMotion = true)) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    WeeklyDayStack(groups, "synthetic-week") { group -> Text(group.items.single()) }
                }
            }
        } }
        groups.forEach { compose.onNodeWithTag("weekly_day_${it.dateKey}").assertIsDisplayed() }
        compose.onNodeWithText("body-0").assertIsDisplayed()
        val rear = compose.onNodeWithTag("weekly_day_2026-11-10")
        val front = compose.onNodeWithTag("weekly_day_2026-11-11")
        val rearLayer = compose.onNodeWithTag("weekly_day_layer_2026-11-10").getUnclippedBoundsInRoot()
        val exposedEdge = rear.getUnclippedBoundsInRoot()
        val frontHeader = front.getUnclippedBoundsInRoot()
        assertTrue(rearLayer.bottom - frontHeader.top >= 50.dp)
        assertTrue(exposedEdge.bottom <= frontHeader.top + 1.dp)
        rear.performTouchInput { click() }
        compose.onNodeWithText("body-1").assertIsDisplayed()
        compose.onNodeWithText("body-0").assertDoesNotExist()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (InstrumentationRegistry.getArguments().getString("uiEvidence") == "true") {
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-product-evidence").apply { mkdirs() }
            File(folder, "weekly-raised.png").outputStream().use {
                compose.onNodeWithTag("weekly_day_stack").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithTag("weekly_day_2026-11-10").performTouchInput { click() }
        compose.onNodeWithText("body-0").assertIsDisplayed()
        compose.onNodeWithTag("weekly_day_newer").assertIsNotEnabled()
    }

    @Test fun headerSwipeAndButtonsNavigateWithoutWrapping() {
        compose.setContent { MateLinkTheme(darkTheme = false) {
            CompositionLocalProvider(LocalAppearance provides AppearanceState(reducedMotion = true)) {
                WeeklyDayStack(groups, "synthetic-gestures") { group -> Text(group.items.single()) }
            }
        } }
        compose.onNodeWithTag("weekly_day_2026-11-11").performTouchInput {
            down(center); moveBy(Offset(0f, -400f)); up()
        }
        compose.onNodeWithText("body-1").assertIsDisplayed()
        compose.onNodeWithTag("weekly_day_2026-11-10").performClick()
        compose.onNodeWithText("body-0").assertIsDisplayed()
        compose.onNodeWithTag("weekly_day_older").performClick()
        compose.onNodeWithTag("weekly_day_2026-11-10").performTouchInput {
            down(center); moveBy(Offset(0f, 400f)); up()
        }
        compose.onNodeWithText("body-0").assertIsDisplayed()
        compose.onNodeWithTag("weekly_day_2026-11-05").performClick()
        compose.onNodeWithTag("weekly_day_older").assertIsNotEnabled()
    }

    @Test fun bodyScrollAtLargeFontDoesNotChangeSelectedDay() {
        compose.setContent { MateLinkTheme(darkTheme = false) {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f),
                LocalAppearance provides AppearanceState(reducedMotion = true)) {
                Column(Modifier.width(320.dp).height(500.dp).verticalScroll(rememberScrollState())) {
                    WeeklyDayStack(groups, "synthetic-scroll") { group ->
                        Column(Modifier.testTag("weekly_day_body")) {
                            repeat(30) { Text("${group.dateKey} item $it", Modifier.height(44.dp)) }
                        }
                    }
                }
            }
        } }
        compose.onNodeWithText("2026-11-11 item 3").performScrollTo().performTouchInput {
            down(center); moveBy(Offset(0f, -160f)); up()
        }
        compose.onAllNodesWithText("2026-11-10 item 0").assertCountEquals(0)
    }

    @Test fun listCanHideRouteCarWithoutChangingTheSharedDefault() {
        compose.setContent { MateLinkTheme(darkTheme = false) {
            Column {
                RouteIndicator("Start", "End")
                RouteIndicator("Start", "End", showCar = false)
            }
        } }
        compose.onAllNodesWithTag("route_indicator_car", useUnmergedTree = true).assertCountEquals(1)
    }
}

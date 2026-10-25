package com.matelink.p0

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.MainActivity
import com.matelink.R
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class DriveHistoryFilterUiTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun restoredDistanceChipsChangeTheActualDriveList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val verticalList = hasScrollAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        runBlocking { prepareQualificationState(context, scenario = "normal") }
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithText(context.getString(R.string.nav_drives), useUnmergedTree = true).performClick()
            // The fixture is outside the default seven-day window. Cached Room
            // rows must not leak into that window's list or summary.
            composeRule.scrollUntilText(context.getString(R.string.no_drives_found))
            composeRule.onNodeWithText("Fixture Garage").assertDoesNotExist()

            composeRule.selectFixtureHistoryWindow(context)
            composeRule.scrollUntilText("Fixture Garage")
            composeRule.waitForText("Fixture Garage")

            // Scroll back to top so distance filter chips are composed and reachable
            composeRule.onNode(verticalList, useUnmergedTree = true).performScrollToIndex(0)
            composeRule.waitForIdle()

            val commute = context.getString(R.string.filter_commute_km)
            val dayTrip = context.getString(R.string.filter_day_trip_km)
            // Day Trip (50–200km) excludes the 18km and 12km fixture drives.
            composeRule.onNodeWithTag("drive_distance_filters").performScrollToNode(hasText(dayTrip))
            composeRule.onNodeWithText(dayTrip).assertExists().performClick()
            composeRule.scrollUntilText(context.getString(R.string.no_drives_found))
            composeRule.onNodeWithText("Fixture Garage").assertDoesNotExist()

            // Scroll back to top so distance filter chips are reachable again
            composeRule.onNode(verticalList, useUnmergedTree = true).performScrollToIndex(0)
            composeRule.waitForIdle()

            // Commute (< 50km) includes both 18km and 12km fixture drives.
            composeRule.onNodeWithTag("drive_distance_filters").performScrollToNode(hasText(commute))
            composeRule.onNodeWithText(commute).assertExists().performClick()
            composeRule.scrollUntilText("Fixture Garage")
            composeRule.waitForText("Fixture Garage")
            composeRule.onNodeWithText(context.getString(R.string.no_drives_found)).assertDoesNotExist()
        }
    }
}

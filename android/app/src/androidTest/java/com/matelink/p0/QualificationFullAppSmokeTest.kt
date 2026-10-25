package com.matelink.p0

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.MainActivity
import com.matelink.R
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class QualificationFullAppSmokeTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun appRendersFixtureDashboardDrivesAndCharges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { prepareQualificationState(context, scenario = "normal") }
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitForText(SYNTHETIC_VEHICLE_NAME)
            composeRule.onNodeWithText(SYNTHETIC_VEHICLE_NAME, substring = true).assertExists()
            composeRule.onNodeWithText(context.getString(R.string.nav_drives), useUnmergedTree = true).performClick()
            // The archived synthetic October 1 events are older than the default seven-day window.
            // Explicitly select a history window that includes the fixture before asserting cards.
            composeRule.selectFixtureHistoryWindow(context)
            showHistoryRecord("Fixture Office")
            composeRule.onNodeWithText(context.getString(R.string.nav_charges), useUnmergedTree = true).performClick()
            showHistoryRecord("Fixture Charger")
        }
    }

    private fun showHistoryRecord(text: String) {
        val list = hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(list, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(list, useUnmergedTree = true).performScrollToNode(hasText(text, substring = true))
        composeRule.waitForText(text)
    }
}

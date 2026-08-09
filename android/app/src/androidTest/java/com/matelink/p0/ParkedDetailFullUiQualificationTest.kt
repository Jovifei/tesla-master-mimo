package com.matelink.p0

import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
        runBlocking { prepareQualificationState(context, scenario = "normal") }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.onNodeWithText(context.getString(R.string.nav_drives), useUnmergedTree = true).performClick()
            composeRule.waitForText(parkedTitle)
            composeRule.onNodeWithText(parkedTitle).performClick()
            composeRule.waitForText("驻车消耗")
            composeRule.onNodeWithText("环境与数据").assertExists()

            scenario.recreate()
            composeRule.waitForText("驻车消耗")
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
            scrollUntilText(parkedTitle)

            setFixtureScenario("parked_partial")
            composeRule.onNodeWithText(parkedTitle).performClick()
            composeRule.waitForText(context.getString(R.string.not_available))
            composeRule.onNodeWithText("驻车消耗").assertExists()
        }
    }

    private fun scrollUntilText(text: String) {
        repeat(8) {
            if (composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return
            composeRule.onNode(hasScrollAction(), useUnmergedTree = true)
                .performTouchInput { swipeUp() }
        }
        composeRule.waitForText(text)
    }
}

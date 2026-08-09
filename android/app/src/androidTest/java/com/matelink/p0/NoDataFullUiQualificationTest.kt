package com.matelink.p0

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.MainActivity
import com.matelink.R
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class NoDataFullUiQualificationTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun normalResponsePullToRefreshRequestsDrivesAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { prepareQualificationState(context, scenario = "normal") }
        resetFixtureRequests()

        ActivityScenario.launch(MainActivity::class.java).use {
            resetFixtureRequests()
            composeRule.onNodeWithText(context.getString(R.string.nav_drives), useUnmergedTree = true).performClick()
            composeRule.waitUntil(30_000) {
                fixturePathRequestCount("/api/v1/cars/101/drives") +
                    fixturePathRequestCount("/api/v1/cars/1/drives") > 0
            }
            composeRule.waitForIdle()
            resetFixtureRequests()
            composeRule.onNodeWithTag("drivesPullToRefresh", useUnmergedTree = true)
                .assertHasClickAction()
                .performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(10_000) {
                fixturePathRequestCount("/api/v1/cars/101/drives") +
                    fixturePathRequestCount("/api/v1/cars/1/drives") > 0
            }
        }
    }

    @Test fun drivesEmptyResponseShowsStableEmptyStateAndReturns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { prepareQualificationState(context, scenario = "no_drives") }
        resetFixtureRequests()

        ActivityScenario.launch(MainActivity::class.java).use {
            resetFixtureRequests()
            composeRule.onNodeWithText(context.getString(R.string.nav_drives), useUnmergedTree = true).performClick()
            composeRule.waitForText(context.getString(R.string.no_drives_found))
            composeRule.onNodeWithText("Fixture Garage").assertDoesNotExist()
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
            composeRule.waitForText(context.getString(R.string.nav_dashboard))
        }
    }

    @Test fun chargesEmptyResponseShowsStableEmptyStateAndReturns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { prepareQualificationState(context, scenario = "no_charges") }
        resetFixtureRequests()

        ActivityScenario.launch(MainActivity::class.java).use {
            resetFixtureRequests()
            composeRule.onNodeWithText(context.getString(R.string.nav_charges), useUnmergedTree = true).performClick()
            composeRule.waitForText(context.getString(R.string.no_charges_found))
            composeRule.onNodeWithText("Fixture Charger").assertDoesNotExist()
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
            composeRule.waitForText(context.getString(R.string.nav_dashboard))
        }
    }

    @Test fun drivesEmptyResponsePullToRefreshRequestsDrivesAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { prepareQualificationState(context, scenario = "no_drives") }
        resetFixtureRequests()

        ActivityScenario.launch(MainActivity::class.java).use {
            resetFixtureRequests()
            composeRule.onNodeWithText(context.getString(R.string.nav_drives), useUnmergedTree = true).performClick()
            composeRule.waitForText(context.getString(R.string.no_drives_found))
            composeRule.waitUntil(30_000) {
                fixturePathRequestCount("/api/v1/cars/101/drives") +
                    fixturePathRequestCount("/api/v1/cars/1/drives") > 0
            }
            composeRule.waitForIdle()
            resetFixtureRequests()
            composeRule.onNodeWithTag("drivesPullToRefresh", useUnmergedTree = true)
                .assertHasClickAction()
                .performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(10_000) {
                fixturePathRequestCount("/api/v1/cars/101/drives") +
                    fixturePathRequestCount("/api/v1/cars/1/drives") > 0
            }
        }
    }

    @Test fun chargesEmptyResponsePullToRefreshRequestsChargesAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { prepareQualificationState(context, scenario = "no_charges") }
        resetFixtureRequests()

        ActivityScenario.launch(MainActivity::class.java).use {
            resetFixtureRequests()
            composeRule.onNodeWithText(context.getString(R.string.nav_charges), useUnmergedTree = true).performClick()
            composeRule.waitForText(context.getString(R.string.no_charges_found))
            composeRule.waitUntil(30_000) {
                fixturePathRequestCount("/api/v1/cars/101/charges") +
                    fixturePathRequestCount("/api/v1/cars/1/charges") > 0
            }
            composeRule.waitForIdle()
            resetFixtureRequests()
            composeRule.onNodeWithTag("chargesPullToRefresh", useUnmergedTree = true)
                .assertHasClickAction()
                .performSemanticsAction(SemanticsActions.OnClick)
            composeRule.waitUntil(10_000) {
                fixturePathRequestCount("/api/v1/cars/101/charges") +
                    fixturePathRequestCount("/api/v1/cars/1/charges") > 0
            }
        }
    }

}

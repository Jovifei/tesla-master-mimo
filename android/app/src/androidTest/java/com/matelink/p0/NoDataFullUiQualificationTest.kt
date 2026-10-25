package com.matelink.p0

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.MainActivity
import com.matelink.R
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class NoDataFullUiQualificationTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val historyList = hasScrollAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    @Test fun normalResponsePullToRefreshRequestsDrivesAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { prepareQualificationState(context, scenario = "normal") }
        resetFixtureRequests()
        ActivityScenario.launch(MainActivity::class.java).use {
            openHistory(context.getString(R.string.nav_drives))
            composeRule.selectFixtureHistoryWindow(context)
            assertPullRefresh("drives") { showNormalDrive() }
        }
    }

    @Test fun drivesEmptyResponseShowsStableEmptyStateAndReturns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { prepareQualificationState(context, scenario = "no_drives") }
        resetFixtureRequests()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openHistory(context.getString(R.string.nav_drives))
            composeRule.waitForText(context.getString(R.string.no_drives_found))
            composeRule.onNodeWithText("Fixture Garage").assertDoesNotExist()
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                check(!activity.isFinishing) { "Empty-history Back must return inside the app" }
            }
            composeRule.waitForIdle()
            composeRule.waitForText(SYNTHETIC_VEHICLE_NAME)
        }
    }

    @Test fun chargesEmptyResponseShowsStableEmptyStateAndReturns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { prepareQualificationState(context, scenario = "no_charges") }
        resetFixtureRequests()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openHistory(context.getString(R.string.nav_charges))
            composeRule.waitForText(context.getString(R.string.no_charges_found))
            composeRule.onNodeWithText("Fixture Charger").assertDoesNotExist()
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                check(!activity.isFinishing) { "Empty-history Back must return inside the app" }
            }
            composeRule.waitForIdle()
            composeRule.waitForText(SYNTHETIC_VEHICLE_NAME)
        }
    }

    @Test fun drivesEmptyResponsePullToRefreshRequestsDrivesAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { prepareQualificationState(context, scenario = "no_drives") }
        resetFixtureRequests()
        ActivityScenario.launch(MainActivity::class.java).use {
            openHistory(context.getString(R.string.nav_drives))
            assertPullRefresh("drives") { composeRule.waitForText(context.getString(R.string.no_drives_found)) }
            composeRule.onNodeWithText("Fixture Garage").assertDoesNotExist()
        }
    }

    @Test fun chargesEmptyResponsePullToRefreshRequestsChargesAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { prepareQualificationState(context, scenario = "no_charges") }
        resetFixtureRequests()
        ActivityScenario.launch(MainActivity::class.java).use {
            openHistory(context.getString(R.string.nav_charges))
            assertPullRefresh("charges") { composeRule.waitForText(context.getString(R.string.no_charges_found)) }
            composeRule.onNodeWithText("Fixture Charger").assertDoesNotExist()
        }
    }

    private fun openHistory(tab: String) {
        composeRule.waitForText(SYNTHETIC_VEHICLE_NAME)
        check(composeRule.onAllNodes(isDialog(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            "Qualification history navigation is blocked by an unrelated dialog"
        }
        composeRule.onNodeWithText(tab, useUnmergedTree = true).performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(historyList, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun showNormalDrive() {
        composeRule.onNode(historyList, useUnmergedTree = true).performScrollToNode(hasText("Fixture Office", substring = true))
        composeRule.waitForText("Fixture Office")
    }

    private fun assertPullRefresh(resource: String, ready: () -> Unit) {
        ready()
        composeRule.onNode(historyList, useUnmergedTree = true).performScrollToIndex(0)
        composeRule.waitForIdle()
        // No in-activity reset: startup may have already fetched this page before navigation.
        val before = quietHistoryCount(resource)
        check(composeRule.onAllNodes(isDialog(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            "Qualification pull is blocked by an unrelated dialog"
        }
        val list = composeRule.onNode(historyList, useUnmergedTree = true)
        val node = list.fetchSemanticsNode()
        val axis = node.config[SemanticsProperties.VerticalScrollAxisRange]
        val position = axis.value()
        check(position <= 0.001f) { "Pull must start at list top; position=$position" }
        val gestureContext = "bounds=${node.boundsInRoot}; vertical=$position/${axis.maxValue()}"
        val startY = 4f * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        // Start inside the list's 8dp TOP content padding, centered away from system
        // back edges and the interactive chips/charts. The whole drag remains real input.
        list.performTouchInput {
            swipe(start = Offset(width * 0.5f, startY), end = Offset(width * 0.5f, height * 0.90f), durationMillis = 900)
        }
        // Material3 1.3.1 awaits animateToThreshold() before invoking onRefresh.
        // A slow HTTP condition advances waitUntil's Compose clock only one frame per
        // request; finish this UI animation before starting the wall-clock network wait.
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        try {
            composeRule.waitUntil(10_000) { historyCount(resource) > before }
        } catch (failure: Throwable) {
            val afterPosition = runCatching {
                list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            }.getOrNull()
            throw AssertionError("Single real pull did not request car 101 $resource; before=$before; " +
                "$gestureContext; afterVertical=$afterPosition; ${fixtureRequestDiagnostics()}", failure)
        }
        ready()
        val delta = quietHistoryCount(resource) - before
        check(delta in 1..4) { "Pull request count must be bounded, delta=$delta; ${fixtureRequestDiagnostics()}" }
    }

    private fun historyCount(resource: String): Int = fixturePathRequestCount("/api/v1/cars/101/$resource")

    private fun quietHistoryCount(resource: String): Int {
        var count = historyCount(resource)
        var quietSince = SystemClock.uptimeMillis()
        composeRule.waitUntil(10_000) {
            val current = historyCount(resource)
            if (current != count) {
                count = current
                quietSince = SystemClock.uptimeMillis()
            }
            SystemClock.uptimeMillis() - quietSince >= 1_000
        }
        return count
    }
}

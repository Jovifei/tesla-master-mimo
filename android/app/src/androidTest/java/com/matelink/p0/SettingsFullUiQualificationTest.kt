package com.matelink.p0

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.MainActivity
import com.matelink.R
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class SettingsFullUiQualificationTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun lanTestIsVisibleAndDoesNotPersistBeforeSave() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unsavedUrl = "http://10.0.2.2:18081"
        runBlocking { prepareQualificationState(context, savedServerUrl = unsavedUrl) }

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.navigateToSettings(context)
            composeRule.onNodeWithTag("serverAddressInput").performTextClearance()
            composeRule.onNodeWithTag("serverAddressInput").performTextInput(FIXTURE_BASE_URL)
            composeRule.onNodeWithTag("serverAddressInput").assertTextContains(FIXTURE_BASE_URL)
            resetFixtureRequests()
            composeRule.onNodeWithTag("testConnectionButton").performScrollTo().performClick()
            composeRule.waitForTag("connectionTestResultSuccess")

            check(fixtureConnectionTestRequestCount() > 0) { "LAN test did not reach the synthetic fixture" }
            check(runBlocking { savedQualificationSettings(context).serverUrl } == unsavedUrl) {
                "Test Connection persisted configuration"
            }
        }
    }

    @Test fun saveAfterSuccessfulLanTestPersistsConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unsavedUrl = "http://10.0.2.2:18081"
        runBlocking { prepareQualificationState(context, savedServerUrl = unsavedUrl) }

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.navigateToSettings(context)
            composeRule.onNodeWithTag("serverAddressInput").performTextClearance()
            composeRule.onNodeWithTag("serverAddressInput").performTextInput(FIXTURE_BASE_URL)
            composeRule.onNodeWithTag("serverAddressInput").assertTextContains(FIXTURE_BASE_URL)
            resetFixtureRequests()
            composeRule.onNodeWithTag("testConnectionButton").performScrollTo().performClick()
            composeRule.waitForTag("connectionTestResultSuccess")
            check(runBlocking { savedQualificationSettings(context).serverUrl } == unsavedUrl) {
                "Test Connection persisted configuration"
            }
            composeRule.onNodeWithTag("saveConfigurationButton")
                .performScrollTo()
                .assertIsEnabled()
                .assertHasClickAction()
                .performClick()
            composeRule.waitUntil(30_000) {
                runBlocking { savedQualificationSettings(context).serverUrl == FIXTURE_BASE_URL }
            }
            val saved = runBlocking { savedQualificationSettings(context) }
            check(saved.apiToken == VALID_SYNTHETIC_TOKEN) {
                "Save Configuration did not persist the encrypted API token"
            }
        }
    }

    @Test fun savedConfigurationLoadsAfterFreshActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val saved = runBlocking { savedQualificationSettings(context) }
        check(saved.serverUrl == FIXTURE_BASE_URL) { "saved server URL is not the fixture URL" }
        check(saved.apiToken == VALID_SYNTHETIC_TOKEN) { "saved API token is not the synthetic token" }
        runBlocking { setFixtureScenario("normal") }
        resetFixtureRequests()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitForText(context.getString(R.string.nav_dashboard))
            composeRule.waitUntil(30_000) { fixtureAppRequestCount() > 0 }
        }
        check(fixtureResponseCount(401) == 0) { "fresh activity used an invalid persisted token" }
    }

    @Test fun settingsConnectionControlsAndFailuresAreFullUiControlled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { prepareQualificationState(context) }

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.navigateToSettings(context)
            composeRule.onAllNodesWithTag("serverAddressInput").assertCountEquals(1)
            composeRule.onAllNodesWithTag("apiKeyInput").assertCountEquals(1)
            composeRule.onNodeWithTag("apiKeyInput")
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            composeRule.onNodeWithTag("testConnectionButton").assertExists()
            composeRule.onNodeWithTag("saveConfigurationButton").assertExists()
            composeRule.onNodeWithTag("tokenInput").assertDoesNotExist()

            resetFixtureRequests()
            composeRule.onNodeWithTag("apiKeyInput").performTextClearance()
            composeRule.onNodeWithTag("apiKeyInput").performTextInput(INVALID_SYNTHETIC_TOKEN)
            composeRule.onNodeWithTag("testConnectionButton").performScrollTo().performClick()
            composeRule.waitForTag("connectionTestResultFailure")
            check(fixtureResponseCount(401) > 0) { "wrong synthetic key did not receive 401" }
            check(runBlocking { savedQualificationSettings(context).apiToken } == VALID_SYNTHETIC_TOKEN) {
                "401 test persisted the temporary key"
            }
            composeRule.onNodeWithText(INVALID_SYNTHETIC_TOKEN).assertDoesNotExist()

            composeRule.onNodeWithTag("apiKeyInput").performTextClearance()
            composeRule.onNodeWithTag("apiKeyInput").performTextInput(VALID_SYNTHETIC_TOKEN)
            composeRule.onNodeWithTag("testConnectionButton").performClick()
            composeRule.waitForTag("connectionTestResultSuccess")

            assertInvalidUrlsDoNotReachFixture(context)
            assertPublicHttpDoesNotReachFixture(context)
        }
    }

    @Test fun timeoutIsShownAndFixtureCanBeReset() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { prepareQualificationState(context) }

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.navigateToSettings(context)
            resetFixtureRequests()
            setFixtureScenario("timeout")
            composeRule.onNodeWithTag("testConnectionButton").performScrollTo().performClick()
            composeRule.waitForTag("connectionTestResultFailure", timeoutMillis = 45_000)
            check(runBlocking { savedQualificationSettings(context).serverUrl } == FIXTURE_BASE_URL) {
                "timeout test persisted a temporary configuration"
            }

            setFixtureScenario("normal")
        }
    }

    private fun assertInvalidUrlsDoNotReachFixture(context: android.content.Context) {
        val invalidCases = listOf(
            "https://" to "地址格式不正确",
            "api.example.com" to "地址格式不正确",
            "https://https://example.com" to "地址格式不正确",
            "https://api.example.com/api/v1" to "只填写服务器根地址",
            "https://api.example.com?token=test" to "地址格式不正确",
            "https://api.example.com#fragment" to "地址格式不正确",
            "https://user:pass@example.com" to "地址格式不正确",
            "https://api.example.com:99999" to "地址格式不正确",
            "ftp://api.example.com" to "地址格式不正确"
        )
        invalidCases.forEach { (url, _) ->
            resetFixtureRequests()
            composeRule.onNodeWithTag("serverAddressInput").performTextClearance()
            composeRule.onNodeWithTag("serverAddressInput").performTextInput(url)
            composeRule.onNodeWithTag("serverAddressInput").assertTextContains(url)
            composeRule.onNodeWithTag("testConnectionButton").performClick()
            composeRule.waitForTag("connectionTestResultFailure")
            check(fixtureConnectionTestRequestCount() == 0) { "invalid URL reached the fixture: $url" }
            check(runBlocking { savedQualificationSettings(context).serverUrl } == FIXTURE_BASE_URL) {
                "invalid URL was persisted"
            }
        }
    }

    private fun assertPublicHttpDoesNotReachFixture(context: android.content.Context) {
        resetFixtureRequests()
        composeRule.onNodeWithTag("serverAddressInput").performTextClearance()
        composeRule.onNodeWithTag("serverAddressInput").performTextInput("http://198.51.100.10")
        composeRule.onNodeWithTag("serverAddressInput").assertTextContains("http://198.51.100.10")
        composeRule.onNodeWithTag("testConnectionButton").performClick()
        composeRule.waitForTag("connectionTestResultFailure")
        check(fixtureConnectionTestRequestCount() == 0) { "public HTTP reached the fixture" }
        check(runBlocking { savedQualificationSettings(context).serverUrl } == FIXTURE_BASE_URL) {
            "public HTTP was persisted"
        }
    }
}

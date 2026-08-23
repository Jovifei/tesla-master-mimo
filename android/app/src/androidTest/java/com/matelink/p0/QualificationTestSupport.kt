package com.matelink.p0

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.R
import com.matelink.data.local.AppSettings
import com.matelink.data.local.StatsDatabase
import com.matelink.locale.LocaleHelper
import com.matelink.receiver.DebugEndpointReceiver
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal const val FIXTURE_BASE_URL = "http://10.0.2.2:18080"
internal const val VALID_SYNTHETIC_TOKEN = "synthetic-qualification-key"
internal const val INVALID_SYNTHETIC_TOKEN = "synthetic-invalid-key"

internal suspend fun prepareQualificationState(
    context: Context,
    scenario: String = "normal",
    savedServerUrl: String = FIXTURE_BASE_URL
) {
    setFixtureScenario(scenario)
    context.deleteDatabase(StatsDatabase.DATABASE_NAME)
    settingsDataStore(context).saveConnectionSettings(
        serverUrl = savedServerUrl,
        apiToken = VALID_SYNTHETIC_TOKEN,
        currencyCode = "EUR"
    )
    settingsDataStore(context).saveLanguageCode("en")
    LocaleHelper.applyLocale(context.applicationContext, "en")
    settingsDataStore(context).saveLastSelectedCarId(101)
}

internal suspend fun savedQualificationSettings(context: Context): AppSettings =
    settingsDataStore(context).settings.first()

internal fun resetFixtureRequests() {
    fixtureRequest("/_qualification/reset")
}

internal fun setFixtureScenario(scenario: String) {
    check(fixtureRequest("/_qualification/scenario?name=$scenario").getString("scenario") == scenario)
}

internal fun fixtureAppRequestCount(): Int = fixtureRequest("/_qualification/state").getInt("app_request_count")

internal fun fixtureConnectionTestRequestCount(): Int =
    fixtureRequest("/_qualification/state").getInt("connection_test_request_count")

internal fun fixturePathRequestCount(path: String): Int =
    fixtureRequest("/_qualification/state").getJSONObject("requests_by_path").optInt(path)

internal fun fixtureResponseCount(status: Int): Int =
    fixtureRequest("/_qualification/state").getJSONObject("responses_by_status").optInt(status.toString())

private fun fixtureRequest(path: String): JSONObject {
    val connection = (URL("$FIXTURE_BASE_URL$path").openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = 5_000
    }
    return try {
        check(connection.responseCode == HttpURLConnection.HTTP_OK) { "fixture control request failed" }
        JSONObject(connection.inputStream.bufferedReader().use { reader -> reader.readText() })
    } finally {
        connection.disconnect()
    }
}

private fun settingsDataStore(context: Context) =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        DebugEndpointReceiver.SettingsEntryPoint::class.java
    ).settingsDataStore()

internal fun ComposeTestRule.waitForText(text: String, timeoutMillis: Long = 30_000) {
    waitUntil(timeoutMillis) {
        onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
}

internal fun ComposeTestRule.waitForTag(tag: String, timeoutMillis: Long = 30_000) {
    waitUntil(timeoutMillis) {
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
}

internal fun ComposeTestRule.navigateToSettings(context: Context) {
    val more = context.getString(R.string.nav_more)
    waitForText(more, timeoutMillis = 15_000)
    onNodeWithText(more, useUnmergedTree = true).performClick()
    scrollMoreTo(context.getString(R.string.settings_title))
    onNodeWithText(context.getString(R.string.settings_title), useUnmergedTree = true)
        .performClick()
    onNodeWithTag("advancedNetworkSection").performClick()
    onNodeWithTag("serverAddressInput").assertExists()
}

internal fun ComposeTestRule.navigateToAbout(context: Context) {
    val more = context.getString(R.string.nav_more)
    waitForText(more, timeoutMillis = 15_000)
    onNodeWithText(more, useUnmergedTree = true).performClick()
    scrollMoreTo(context.getString(R.string.about))
    onNodeWithText(context.getString(R.string.about), useUnmergedTree = true)
        .performClick()
    onNodeWithTag("helpLink").assertExists()
}

private fun ComposeTestRule.scrollMoreTo(text: String) {
    repeat(4) {
        if (onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()) return
        onNode(hasScrollAction(), useUnmergedTree = true).performTouchInput { swipeUp() }
    }
}

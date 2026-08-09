package com.matelink.ui.screens.about

import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.CompositionLocalProvider
import com.matelink.ui.theme.MateLinkTheme
import org.junit.Rule
import org.junit.Test

class AboutScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun publicInfoEntriesShowUnconfiguredMessageWithoutOpeningUri() {
        val uriHandler = RecordingUriHandler()

        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                MateLinkTheme {
                    AboutScreen(onNavigateBack = {})
                }
            }
        }

        listOf("helpLink", "legalLink", "changelogLink").forEach { tag ->
            composeRule.onNodeWithTag(tag).assertIsDisplayed().performClick()
            composeRule.onNodeWithText("Public pages are not configured yet.").assertIsDisplayed()
            check(uriHandler.openedUris.isEmpty()) { "unconfigured public info entry opened a URI" }
        }
    }

    private class RecordingUriHandler : UriHandler {
        val openedUris = mutableListOf<String>()

        override fun openUri(uri: String) {
            openedUris += uri
        }
    }
}

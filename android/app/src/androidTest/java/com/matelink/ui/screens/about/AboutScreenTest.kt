package com.matelink.ui.screens.about

import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.CompositionLocalProvider
import com.matelink.BuildConfig
import com.matelink.ui.common.PublicInfoLinks
import com.matelink.ui.theme.MateLinkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AboutScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun configuredPublicInfoEntriesSendOnlyTheirVerifiedHttpsUrisToTheHandler() {
        val uriHandler = RecordingUriHandler()

        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                MateLinkTheme {
                    AboutScreen(onNavigateBack = {})
                }
            }
        }

        val entries = listOf("helpLink" to PublicInfoLinks.Page.HELP,
            "legalLink" to PublicInfoLinks.Page.LEGAL,
            "changelogLink" to PublicInfoLinks.Page.CHANGELOG)
        entries.forEach { (tag, page) ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
            assertEquals(PublicInfoLinks.url(BuildConfig.MATELINK_PUBLIC_INFO_BASE_URL, page),
                uriHandler.openedUris.last())
        }
        assertEquals(entries.size, uriHandler.openedUris.size)
    }

    private class RecordingUriHandler : UriHandler {
        val openedUris = mutableListOf<String>()

        override fun openUri(uri: String) {
            openedUris += uri
        }
    }
}

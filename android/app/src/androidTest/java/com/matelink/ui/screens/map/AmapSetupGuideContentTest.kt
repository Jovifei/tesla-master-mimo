package com.matelink.ui.screens.map

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.matelink.domain.map.InstalledAppIdentity
import com.matelink.ui.theme.MateLinkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AmapSetupGuideContentTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun guideShowsAndroidOnlyInstructionsMaskedKeyAndCopyActions() {
        var copied = ""
        composeRule.setContent {
            MateLinkTheme {
                AmapSetupGuideContent(
                    identity = InstalledAppIdentity("com.matelink", "AA:BB", "Debug"),
                    uiState = AmapSetupUiState(),
                    onCopyPackage = { copied = "package" },
                    onCopySha1 = { copied = "sha1" },
                    onKeyChange = {}, onSaveKey = {}, onClearKey = {}, onPrivacyAgreedChange = {}, onNavigateToPreview = {}
                )
            }
        }

        composeRule.onNodeWithText("12. Open the map preview.").assertIsDisplayed()
        composeRule.onNodeWithText("Do not use a Web Service Key or JS API Key.").assertIsDisplayed()
        composeRule.onNodeWithText("Copy package name").performClick()
        assertEquals("package", copied)
        assertTrue(composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().config.contains(SemanticsProperties.Password))
        composeRule.onNodeWithText("Open map preview").assertIsNotEnabled()
    }

    @Test
    fun settingsEntryShowsMapServicesStatus() {
        composeRule.setContent {
            MateLinkTheme { AmapSettingsEntryContent(AmapSetupUiState(), onNavigateToSetup = {}) }
        }
        composeRule.onNodeWithText("AMap").assertIsDisplayed()
        composeRule.onNodeWithText("Not configured").assertIsDisplayed()
    }
}

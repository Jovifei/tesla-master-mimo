package com.matelink.ui.screens.map

import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.R
import com.matelink.domain.map.InstalledAppIdentity
import com.matelink.ui.theme.MateLinkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AmapSetupGuideContentTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun guideShowsAndroidOnlyInstructionsMaskedKeyAndCopyActions() {
        var copied = ""
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MateLinkTheme {
                AmapSetupGuideContent(
                    identity = InstalledAppIdentity("com.matelink", "AA:BB", "Debug"),
                    uiState = AmapSetupUiState(),
                    onCopyPackage = { copied = "package" },
                    onCopySha1 = { copied = "sha1" },
                    onKeyChange = {}, onVerifyDraftKey = {}, onVerifySavedKey = {}, onChangeKey = {},
                    onPrivacyAgreedChange = {}, onNavigateToPreview = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.amap_setup_steps), substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.amap_setup_warning), substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.amap_key_not_saved)).fetchSemanticsNode()
        composeRule.onNodeWithText(context.getString(R.string.amap_copy_package)).performClick()
        assertEquals("package", copied)
        assertTrue(composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().config.contains(SemanticsProperties.Password))
        composeRule.onNodeWithText(context.getString(R.string.amap_verify_and_save)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.amap_preview)).assertIsNotEnabled()
    }

    @Test
    fun settingsEntryShowsMapServicesStatus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MateLinkTheme { AmapSettingsEntryContent(AmapSetupUiState(), onNavigateToSetup = {}) }
        }
        composeRule.onNodeWithText(context.getString(R.string.amap_settings_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.amap_status_unconfigured)).assertIsDisplayed()
    }

    @Test
    fun guideConfirmsVerifiedKeyWithoutShowingAnInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MateLinkTheme {
                AmapSetupGuideContent(
                    identity = InstalledAppIdentity("com.matelink", "AA:BB", "Debug"),
                    uiState = AmapSetupUiState(hasKey = true, mapLoaded = true),
                    onCopyPackage = {}, onCopySha1 = {}, onKeyChange = {}, onVerifyDraftKey = {}, onVerifySavedKey = {}, onChangeKey = {},
                    onPrivacyAgreedChange = {}, onNavigateToPreview = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.amap_key_verified)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.amap_change_key)).assertIsDisplayed()
    }

    @Test
    fun savedUnverifiedKeyCanBeTestedWithoutShowingIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MateLinkTheme {
                AmapSetupGuideContent(
                    identity = InstalledAppIdentity("com.matelink", "AA:BB", "Debug"),
                    uiState = AmapSetupUiState(hasKey = true, privacyAgreed = true),
                    onCopyPackage = {}, onCopySha1 = {}, onKeyChange = {}, onVerifyDraftKey = {}, onVerifySavedKey = {}, onChangeKey = {},
                    onPrivacyAgreedChange = {}, onNavigateToPreview = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.amap_key_saved_unverified)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.amap_verify_saved_key)).assertIsDisplayed()
    }

    @Test
    fun draftKeyExplainsWhyTestingIsDisabledUntilPrivacyIsAccepted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MateLinkTheme {
                AmapSetupGuideContent(
                    identity = InstalledAppIdentity("com.matelink", "AA:BB", "Debug"),
                    uiState = AmapSetupUiState(keyInput = "candidate-key"),
                    onCopyPackage = {}, onCopySha1 = {}, onKeyChange = {}, onVerifyDraftKey = {}, onVerifySavedKey = {}, onChangeKey = {},
                    onPrivacyAgreedChange = {}, onNavigateToPreview = {}
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.amap_verification_requires_privacy)).fetchSemanticsNode()
        composeRule.onNodeWithText(context.getString(R.string.amap_verify_and_save)).assertIsNotEnabled()
    }

    @Test
    fun mapStringsUseChineseForZhAndEnglishForEn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val zhConfig = Configuration(context.resources.configuration).apply { setLocale(Locale.CHINESE) }
        val enConfig = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }

        assertEquals("地图服务", context.createConfigurationContext(zhConfig).getString(R.string.amap_settings_section))
        assertEquals("Map services", context.createConfigurationContext(enConfig).getString(R.string.amap_settings_section))
    }
}

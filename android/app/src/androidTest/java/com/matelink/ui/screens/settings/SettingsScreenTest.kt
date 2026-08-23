package com.matelink.ui.screens.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import com.matelink.ui.theme.MateLinkTheme
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun connectionSection_exposesOnlySafeTwoFieldControls() {
        composeRule.setContent {
            MateLinkTheme {
                SettingsContent(
                    uiState = SettingsUiState(
                        serverUrl = "https://",
                        apiToken = "test-key",
                        isLoading = false
                    ),
                    onServerUrlChange = {},
                    onSecondaryServerUrlChange = {},
                    onApiTokenChange = {},
                    onHttpBasicAuthUsernameChange = {},
                    onHttpBasicAuthPasswordChange = {},
                    onAcceptInvalidCertsChange = {},
                    onCurrencyChange = {},
                    onShowShortDrivesChargesChange = {},
                    onTestConnection = {},
                    onSave = {}
                )
            }
        }

        composeRule.onNodeWithTag("advancedNetworkSection").performClick()
        composeRule.onNodeWithTag("serverAddressInput").assertExists()
        composeRule.onNodeWithTag("tokenInput").assertExists()
        composeRule.onNodeWithTag("testConnectionButton").assertExists()
        composeRule.onNodeWithTag("saveConfigurationButton").assertExists()
        composeRule.onNodeWithTag("tokenInput")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule.onAllNodesWithTag("serverAddressInput").assertCountEquals(1)
        composeRule.onAllNodesWithTag("tokenInput").assertCountEquals(1)
    }

    @Test fun testConnectionAndSaveButtonsAreIndependent() {
        var testClicks = 0
        var saveClicks = 0

        composeRule.setContent {
            MateLinkTheme {
                SettingsContent(
                    uiState = SettingsUiState(
                        serverUrl = "http://10.0.2.2:18080",
                        apiToken = "synthetic-key",
                        mockMode = false,
                        isLoading = false
                    ),
                    onServerUrlChange = {},
                    onSecondaryServerUrlChange = {},
                    onApiTokenChange = {},
                    onHttpBasicAuthUsernameChange = {},
                    onHttpBasicAuthPasswordChange = {},
                    onAcceptInvalidCertsChange = {},
                    onCurrencyChange = {},
                    onShowShortDrivesChargesChange = {},
                    onTestConnection = { testClicks += 1 },
                    onSave = { saveClicks += 1 }
                )
            }
        }

        composeRule.onNodeWithTag("testConnectionButton").performClick()
        composeRule.onNodeWithTag("saveConfigurationButton").performClick()

        assert(testClicks == 1)
        assert(saveClicks == 1)
    }
}

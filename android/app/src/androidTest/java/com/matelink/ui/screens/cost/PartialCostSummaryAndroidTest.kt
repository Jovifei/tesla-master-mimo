package com.matelink.ui.screens.cost

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.R
import com.matelink.ui.theme.MateLinkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PartialCostSummaryAndroidTest {
    @get:Rule val compose = createComposeRule()
    @Test fun partialZeroIsVisibleButNeverLabeledAsAWholeBillOrWholeEnergy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MateLinkTheme {
                Column(Modifier.width(320.dp)) {
                    SummarySection(CostUiState(loading = false, totalCharges = 1,
                        partialEstimatedCost = 0.0, partialEstimatedCoverage = 1), "¥")
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.cost_partial_observation_estimate)).assertIsDisplayed()
        compose.onNodeWithText("≈", substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cost_recorded_manual_total)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.cost_energy_added)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.cost_no_paired_values)).assertDoesNotExist()
    }
}

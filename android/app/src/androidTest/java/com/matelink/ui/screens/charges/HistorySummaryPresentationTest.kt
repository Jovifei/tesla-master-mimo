package com.matelink.ui.screens.charges

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.R
import com.matelink.data.api.models.Units
import com.matelink.ui.screens.drives.DriveDateFilter
import com.matelink.ui.screens.drives.DriveDistanceFilter
import com.matelink.ui.screens.drives.DrivesSummary
import com.matelink.ui.screens.drives.SummaryCard as DriveSummaryCard
import com.matelink.ui.theme.MateLinkTheme
import com.matelink.ui.theme.telemetryPalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistorySummaryPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chargeSummaryKeepsNumbersAndApproximationWithoutCoverageText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val summary = mutableStateOf(ChargesSummary(totalCharges = 2, totalEnergyAdded = 10.0,
            totalCost = 5.0, avgCostPerCharge = 2.5, energyCoverage = 1, costCoverage = 1))
        compose.setContent {
            MateLinkTheme {
                SummaryCard(summary.value, "¥", telemetryPalette(), DateFilter.LAST_30_DAYS,
                    null, null, {}, { _, _ -> })
            }
        }
        compose.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("≈10.0 kWh", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("≈¥5.00", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("≈¥2.50", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.stats_analysis_cost_coverage),
            substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText(context.getString(R.string.energy_coverage),
            substring = true, useUnmergedTree = true).assertCountEquals(0)

        compose.runOnIdle { summary.value = summary.value.copy(energyCoverage = 2, costCoverage = 2) }
        compose.onNodeWithText("10.0 kWh", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("¥5.00", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("¥2.50", useUnmergedTree = true).assertIsDisplayed()

        compose.runOnIdle { summary.value = summary.value.copy(partialEnergyCount = 1, estimatedCostCount = 1) }
        compose.onNodeWithText("≈10.0 kWh", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("≈¥5.00", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("≈¥2.50", useUnmergedTree = true).assertIsDisplayed()

        compose.runOnIdle { summary.value = summary.value.copy(totalEnergyAdded = null, totalCost = null,
            avgCostPerCharge = null, energyCoverage = 0, costCoverage = 0) }
        compose.onAllNodesWithText(context.getString(R.string.not_available), useUnmergedTree = true)
            .assertCountEquals(3)
    }

    @Test fun driveSummaryMarksIncompleteValuesWithoutCoverageText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val summary = mutableStateOf(DrivesSummary(totalDrives = 3, totalDistanceKm = 12.5,
            totalDurationMin = 40, distanceSampleCount = 2, durationSampleCount = 2))
        compose.setContent {
            MateLinkTheme {
                DriveSummaryCard(summary.value, Units(unitOfLength = "km"), telemetryPalette(),
                    DriveDateFilter.LAST_7_DAYS, null, null, DriveDistanceFilter.ALL, {}, { _, _ -> }, {})
            }
        }
        compose.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("≈12.5", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("≈40", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.stats_analysis_data_coverage),
            substring = true, useUnmergedTree = true).assertCountEquals(0)

        compose.runOnIdle { summary.value = summary.value.copy(distanceSampleCount = 3, durationSampleCount = 3) }
        compose.onNodeWithText("12.5", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("40", useUnmergedTree = true).assertIsDisplayed()

        compose.runOnIdle { summary.value = summary.value.copy(totalDistanceKm = null, totalDurationMin = null,
            distanceSampleCount = 0, durationSampleCount = 0) }
        compose.onAllNodesWithText(context.getString(R.string.not_available), useUnmergedTree = true)
            .assertCountEquals(3) // Distance, duration and maximum speed.
    }
}

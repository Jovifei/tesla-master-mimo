package com.matelink.ui.screens.drives

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
import com.matelink.ui.theme.MateLinkTheme
import com.matelink.ui.theme.telemetryPalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriveChartNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun incompleteTimeOrDistanceShowsOneShortNoticeAndCompleteDoesNot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chart = mutableStateOf(DriveChartData("10/30", count = 2, totalDistance = 12.0,
            totalDurationMin = 20, maxSpeed = 60, sortKey = 1,
            distanceSampleCount = 1, durationSampleCount = 1))
        val type = mutableStateOf(DrivesChartType.TIME)
        compose.setContent {
            MateLinkTheme {
                DrivesChartPage(listOf(chart.value), DriveChartGranularity.DAILY, type.value,
                    Units(unitOfLength = "km"), telemetryPalette())
            }
        }
        val shortNotice = context.getString(R.string.history_chart_partial_data)
        compose.onNodeWithText(shortNotice).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.stats_analysis_data_coverage),
            substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(context.getString(R.string.stats_analysis_coverage_records, 1, 2),
            substring = true).assertCountEquals(0)

        compose.runOnIdle { type.value = DrivesChartType.DISTANCE }
        compose.onNodeWithText(shortNotice).assertIsDisplayed()
        compose.runOnIdle { chart.value = chart.value.copy(distanceSampleCount = 2, durationSampleCount = 2) }
        compose.onAllNodesWithText(shortNotice).assertCountEquals(0)
        compose.runOnIdle { type.value = DrivesChartType.TIME }
        compose.onAllNodesWithText(shortNotice).assertCountEquals(0)

        compose.runOnIdle {
            chart.value = chart.value.copy(totalDurationMin = null, durationSampleCount = 0)
        }
        compose.onNodeWithText(shortNotice).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.not_available)).assertIsDisplayed()
        compose.runOnIdle { type.value = DrivesChartType.COUNT }
        compose.onAllNodesWithText(shortNotice).assertCountEquals(0)
    }
}

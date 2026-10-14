package com.matelink.ui.screens.drives

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.matelink.data.api.models.DrivePosition
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.Units
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production curve cards and their selected tooltip; synthetic inputs only. */
@RunWith(AndroidJUnit4::class)
class DriveCurvesUnitsAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualCurvePageUsesReceiptUnitAndHidesExplicitUnknownLength() {
        val detail = mutableStateOf(DriveDetail(driveId = 1, lengthUnit = "mi", speedMax = 60,
            odometerDetails = DriveOdometerDetails(distance = 10.0),
            positions = listOf(DrivePosition(speed = 20.0), DrivePosition(speed = 40.0), DrivePosition(speed = 60.0))))
        compose.setContent {
            MaterialTheme { DriveCurvesContent(detail.value, null, Units(unitOfLength = "km")) }
        }
        compose.onNodeWithText("10.0 mi").assertIsDisplayed()
        compose.onNodeWithText("60 mph").assertIsDisplayed()
        compose.runOnIdle { detail.value = detail.value.copy(lengthUnit = "") }
        compose.onNodeWithText("10.0 km").assertDoesNotExist()
        compose.onNodeWithText("60 km/h").assertDoesNotExist()
        compose.onNodeWithText("10.0 mi").assertDoesNotExist()
        compose.onNodeWithText("60 mph").assertDoesNotExist()
    }

    @Test fun rawPreferredCurveValuesAreDisplayedOnceInBothUnitSystems() {
        val imperial = mutableStateOf(true)
        val positions = listOf(DrivePosition(speed = 20.0), DrivePosition(speed = 40.0), DrivePosition(speed = 60.0))
        compose.setContent {
            MaterialTheme {
                val units = Units(unitOfLength = if (imperial.value) "mi" else "km")
                Column {
                    SpeedCurveCard(positions, units, emptyList(), 1f, {}, { "" })
                    DistanceCurveCard(positions, 10.0, units, emptyList(), 1f, {}, { "" })
                }
            }
        }
        compose.onNodeWithText("60.0 mph", substring = true).assertIsDisplayed()
        compose.onNodeWithText("10.0 mi", substring = true).assertIsDisplayed()
        compose.runOnIdle { imperial.value = false }
        compose.onNodeWithText("60.0 km/h", substring = true).assertIsDisplayed()
        compose.onNodeWithText("10.0 km", substring = true).assertIsDisplayed()
    }
}

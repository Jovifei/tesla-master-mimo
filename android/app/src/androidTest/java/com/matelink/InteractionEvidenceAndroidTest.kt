package com.matelink

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.data.local.TirePosition
import com.matelink.domain.model.VisualWheelReading
import com.matelink.domain.model.VisualWheelState
import com.matelink.ui.components.*
import com.matelink.ui.theme.MateLinkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InteractionEvidenceAndroidTest {
    @get:Rule val compose = createComposeRule()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun openingsExposeOpenClosedAndUnknownWithoutColorOrIndividualDoorClaims() {
        val door = mutableStateOf<Boolean?>(true)
        compose.setContent { MateLinkTheme { VehicleOpeningsVisual(door.value, false, null, null) } }
        compose.onNodeWithTag("interaction-opening-doors").assertTextContains(label(R.string.interaction_evidence_open), substring = true)
        compose.onNodeWithTag("interaction-opening-windows").assertTextContains(label(R.string.interaction_evidence_closed), substring = true)
        compose.onNodeWithTag("interaction-opening-frunk").assertTextContains(label(R.string.interaction_evidence_unknown), substring = true)
        compose.runOnIdle { door.value = null }
        compose.onNodeWithTag("interaction-opening-doors").assertTextContains(label(R.string.interaction_evidence_unknown), substring = true)
        compose.onNodeWithText(label(R.string.interaction_openings_aggregate)).assertExists()
    }
    @Test fun openingsLegendSurvivesNarrowLargeTextLayout() {
        compose.setContent { MateLinkTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                Column(Modifier.width(280.dp).padding(12.dp)) { VehicleOpeningsVisual(true, false, null, true) }
            }
        } }
        for (key in listOf("doors", "windows", "frunk", "trunk")) {
            compose.onNodeWithTag("interaction-opening-$key").assertIsDisplayed()
        }
    }
    @Test fun intervalSemanticsTrackAnUnknownEndInsteadOfImplyingCompletion() {
        val complete = mutableStateOf(false)
        compose.setContent { MateLinkTheme { RecordedChargeRail(complete.value) } }
        compose.onNodeWithTag("interaction-recorded-interval").assertContentDescriptionEquals(label(R.string.interaction_evidence_interval_open))
        compose.runOnIdle { complete.value = true }
        compose.onNodeWithTag("interaction-recorded-interval").assertContentDescriptionEquals(label(R.string.interaction_evidence_interval_complete))
    }
    @Test fun failedHistoryIsNotAnnouncedAsNoHistory() {
        val failed = mutableStateOf(true)
        compose.setContent { MateLinkTheme { EmptyHistoryVisual(charging = false, failed = failed.value) } }
        compose.onNodeWithTag("interaction-empty-drive").assertContentDescriptionEquals(label(R.string.interaction_evidence_read_failed))
        compose.runOnIdle { failed.value = false }
        compose.onNodeWithTag("interaction-empty-drive").assertContentDescriptionEquals(label(R.string.interaction_evidence_drive_empty))
    }
    @Test fun wheelConsumerRejectsRawUnknownValueButRetainsQualifiedZeroAndIndependentTimes() {
        val selected = mutableStateOf<TirePosition?>(null)
        val readings = mapOf(
            TirePosition.FL to VisualWheelReading(0.0, 1800000000000L, VisualWheelState.RECORDED),
            TirePosition.FR to VisualWheelReading(9.99, 1800000010000L, VisualWheelState.UNKNOWN))
        compose.setContent { MateLinkTheme { TpmsVehicleVisual(readings, selected.value, { selected.value = it }) } }
        compose.onNodeWithText("9.99 bar").assertDoesNotExist()
        compose.onNodeWithTag("interaction-wheel-observed-at").assertExists()
        compose.onNodeWithTag("interaction-wheel-FL").performClick().assertIsSelected()
        compose.onNodeWithTag("interaction-wheel-FR").performClick().assertIsSelected()
        compose.onNodeWithTag("interaction-wheel-observed-at").assertTextContains(label(R.string.interaction_evidence_time_unknown), substring = true)
    }
}

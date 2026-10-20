package com.matelink.ui.screens.charges

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.R
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.ChargerDetails
import com.matelink.ui.theme.MateLinkTheme
import com.matelink.util.formatTime
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated emulator UI; never operates on the owner's installed app. */
@RunWith(AndroidJUnit4::class)
class ChargeSingleObservationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneRealZeroSampleShowsTimeValueAndUnitWithoutAChart() {
        val observedAt = Instant.parse("2026-10-20T10:00:00Z")
        val time = observedAt.atZone(ZoneId.systemDefault()).toLocalDateTime()
            .formatTime(Locale.getDefault(), true)
        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.charge_single_observation)
        compose.setContent {
            MateLinkTheme {
                SingleChargeObservation("Charging power", observedAt, "0.0 kW", Color.Green, true)
            }
        }
        compose.onNodeWithText("Charging power · $label").assertIsDisplayed()
        compose.onNodeWithText(time).assertIsDisplayed()
        compose.onNodeWithText("0.0 kW").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("charge-single-observation").getUnclippedBoundsInRoot()
        assertTrue("single point should remain compact", (bounds.bottom - bounds.top).value < 100f)
    }

    private fun renderUnknownMode(detail: ChargeDetail) {
        compose.setContent {
            MateLinkTheme {
                ChargeDetailContent(
                    localArchiveLinkPending = false,
                    historySyncWarning = null,
                    detail = detail,
                    stats = ChargeStatsCalculator.calculateStats(detail),
                    costPresentation = ChargeDetailCostPresentation(null, ChargeDetailCostState.UNAVAILABLE),
                    units = null,
                    currencySymbol = "¥",
                    isDcCharge = null,
                    manualTotalAmount = null,
                    containingTrip = null,
                    onNavigateToTripDetail = {},
                    onRemoveFromTrip = {},
                    onSaveManualTotal = {}
                )
            }
        }
    }

    @Test fun unknownModeStillShowsRealVoltageAndCurrentSamples() {
        val start = "2026-10-20T10:00:00Z"
        val end = "2026-10-20T10:30:00Z"
        renderUnknownMode(ChargeDetail(1, startDate = start, endDate = end,
            chargePoints = listOf(
                ChargePoint(date = start, chargerDetails = ChargerDetails(
                    chargerVoltage = 0.0, chargerActualCurrent = 0.0)),
                ChargePoint(date = end, chargerDetails = ChargerDetails(
                    chargerVoltage = 400.0, chargerActualCurrent = 16.0))
            )))
        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.charge_electrical_samples)
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("400 V").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("16 A").performScrollTo().assertIsDisplayed()
    }

    @Test fun unknownModeWithoutElectricalSamplesDoesNotCreateElectricalCard() {
        val start = "2026-10-20T10:00:00Z"
        val end = "2026-10-20T10:30:00Z"
        renderUnknownMode(ChargeDetail(1, startDate = start, endDate = end,
            chargePoints = listOf(ChargePoint(date = start,
                chargerDetails = ChargerDetails(chargerPower = 5.0)))))
        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.charge_electrical_samples)
        compose.onNodeWithText(label).assertDoesNotExist()
    }

    @Test fun savedLegacyAndUnpricedSourceAmountsRemainVisibleWithoutCurrencySymbol() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MateLinkTheme {
                ChargeDetailContent(
                    localArchiveLinkPending = false, historySyncWarning = null,
                    detail = ChargeDetail(7, startDate = "2026-10-20T10:00:00Z",
                        endDate = "2026-10-20T11:00:00Z", cost = 8.0),
                    stats = null,
                    costPresentation = ChargeDetailCostPresentation(null, ChargeDetailCostState.UNAVAILABLE),
                    units = null, currencySymbol = "¥", isDcCharge = null,
                    manualTotalAmount = null, pendingLegacyAmount = 12.34,
                    sourceUnconfirmedAmount = 8.0,
                    containingTrip = null, onNavigateToTripDetail = {},
                    onRemoveFromTrip = {}, onSaveManualTotal = {}
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.charge_cost_saved_unconfirmed, 12.34))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.charge_cost_source_unconfirmed, 8.0))
            .performScrollTo().assertIsDisplayed()
    }
}

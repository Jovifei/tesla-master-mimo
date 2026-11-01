package com.matelink.ui.screens.charges

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.R
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.EnergyContract
import com.matelink.data.api.models.EnergyMetric
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.ChargerDetails
import com.matelink.ui.theme.MateLinkTheme
import com.matelink.ui.theme.telemetryPalette
import com.matelink.domain.analytics.ChargeCostSource
import com.matelink.domain.analytics.EffectiveChargeCost
import com.matelink.util.formatTime
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
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
        compose.onNodeWithText(context.getString(R.string.charge_cost_currency_pending_short), useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("12.34", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("¥12.34", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.charge_cost_source_unconfirmed, 8.0))
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun pendingAmountAndEstimateRemainVisibleAndEditableInDetail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MateLinkTheme {
                ChargeDetailContent(
                    localArchiveLinkPending = false, historySyncWarning = null,
                    detail = ChargeDetail(7, startDate = "2026-10-20T10:00:00Z",
                        endDate = "2026-10-20T11:00:00Z"),
                    stats = null, costPresentation = ChargeDetailCostPresentation(15.0, ChargeDetailCostState.ESTIMATE),
                    units = null, currencySymbol = "¥", isDcCharge = null, manualTotalAmount = null,
                    pendingBoundAmount = "CNY" to 23.0,
                    containingTrip = null, onNavigateToTripDetail = {}, onRemoveFromTrip = {}, onSaveManualTotal = {}
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.charge_cost_pending_short), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("¥23.00", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.charge_cost_estimate) + " ≈¥15.00", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("¥23.00").performClick()
        compose.onNodeWithText(context.getString(R.string.charge_cost_saved_unbound, "CNY", 23.0))
            .assertIsDisplayed()
    }

    @Test fun narrowChargeCardShowsPendingAndEstimateWithoutMetricOverlap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.getString(R.string.charge_cost_pending_short)
        var edited = false
        compose.setContent {
            MateLinkTheme {
                Box(Modifier.width(320.dp)) {
                    ChargeItem(ChargeData(7), isDcCharge = null, currencySymbol = "¥",
                        effectiveCost = EffectiveChargeCost(15.0, ChargeCostSource.ESTIMATE),
                        pendingLegacyAmount = null, pendingBoundAmount = "CNY" to 23.0,
                        otherCurrencyAmount = null, palette = telemetryPalette(),
                        onEditCost = { edited = true }, onClick = {})
                }
            }
        }
        compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("23.00", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.charge_cost_estimate) + " ≈¥15.00", useUnmergedTree = true)
            .assertIsDisplayed()
        val bounds = (0..3).map { compose.onNodeWithTag("history-metric-value-$it", useUnmergedTree = true)
            .getUnclippedBoundsInRoot() }
        bounds.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left) }
        compose.onNodeWithContentDescription("$label: 23.00 ¥").performClick()
        compose.runOnIdle { assertTrue(edited) }
    }

    @Test fun partialEstimatedUnitCostUsesOnlyTheObservedEnergy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = "2026-10-20T10:00:00Z"
        val end = "2026-10-20T11:00:00Z"
        val detail = ChargeDetail(7, startDate = start, endDate = end, source = "telemetry_mqtt",
            energyContract = EnergyContract(batteryInput = EnergyMetric(
                method = "session_counter_delta", measurementPoint = "battery_input", source = "telemetry_mqtt",
                sourceField = "DCChargingEnergyIn", quality = "unknown", reason = "partial_counter_window",
                startDate = start, endDate = end, observedStartAt = "2026-10-20T10:05:00Z",
                observedEndAt = "2026-10-20T10:55:00Z", timeBasis = "collector_received_at",
                coverageKind = "endpoints", coverageRatio = 5.0 / 6.0, coveredEnergyKwh = 10.0)))
        assertNull(ChargeStatsCalculator.calculateStats(detail).energyAdded)
        assertNull(ChargeStatsCalculator.calculateStats(detail).efficiency)
        compose.setContent {
            MateLinkTheme {
                ChargeDetailContent(localArchiveLinkPending = false, historySyncWarning = null,
                    detail = detail, stats = ChargeStatsCalculator.calculateStats(detail),
                    costPresentation = ChargeDetailCostPresentation(15.0, ChargeDetailCostState.ESTIMATE,
                        "estimate_partial_counter"), units = null, currencySymbol = "¥", isDcCharge = null,
                    manualTotalAmount = null, containingTrip = null, onNavigateToTripDetail = {},
                    onRemoveFromTrip = {}, onSaveManualTotal = {})
            }
        }
        compose.onAllNodesWithText(context.getString(R.string.charge_partial_energy), useUnmergedTree = true)
            .assertCountEquals(2)
        compose.onAllNodesWithText("≈10.00 kWh", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("≈¥1.500").performScrollTo().assertIsDisplayed()
    }

    @Test fun completeAndMissingEnergyDoNotBecomePartialEstimates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detail = mutableStateOf(ChargeDetail(7, startDate = "2026-10-20T10:00:00Z",
            endDate = "2026-10-20T11:00:00Z", chargeEnergyAdded = 12.0))
        compose.setContent {
            MateLinkTheme {
                ChargeDetailContent(localArchiveLinkPending = false, historySyncWarning = null,
                    detail = detail.value, stats = ChargeStatsCalculator.calculateStats(detail.value),
                    costPresentation = ChargeDetailCostPresentation(null, ChargeDetailCostState.UNAVAILABLE),
                    units = null, currencySymbol = "¥", isDcCharge = null, manualTotalAmount = null,
                    containingTrip = null, onNavigateToTripDetail = {}, onRemoveFromTrip = {}, onSaveManualTotal = {})
            }
        }
        compose.onAllNodesWithText("12.00 kWh", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText(context.getString(R.string.charge_partial_energy), useUnmergedTree = true)
            .assertCountEquals(0)

        compose.runOnIdle { detail.value = detail.value.copy(chargeEnergyAdded = null) }
        compose.onAllNodesWithText("12.00 kWh", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText(context.getString(R.string.charge_partial_energy), useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.energy_added), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        assertNull(ChargeStatsCalculator.calculateStats(detail.value).energyAdded)
    }

    @Test fun verifiedManualAmountBeatsPendingCandidateOnCard() {
        val pendingLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.charge_cost_pending_short)
        compose.setContent {
            MateLinkTheme {
                Box(Modifier.width(320.dp)) {
                    ChargeItem(ChargeData(7), isDcCharge = null, currencySymbol = "¥",
                        effectiveCost = EffectiveChargeCost(23.0, ChargeCostSource.MANUAL),
                        pendingLegacyAmount = 31.0, pendingBoundAmount = "CNY" to 31.0,
                        otherCurrencyAmount = null, palette = telemetryPalette(), onEditCost = {}, onClick = {})
                }
            }
        }
        compose.onNodeWithText("23.00", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(pendingLabel, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("31.00", useUnmergedTree = true).assertDoesNotExist()
    }
}

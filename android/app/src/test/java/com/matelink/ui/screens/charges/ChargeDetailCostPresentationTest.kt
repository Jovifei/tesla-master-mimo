package com.matelink.ui.screens.charges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeDetailCostPresentationTest {

    @Test
    fun verifiedManualWinsOverUnverifiedCandidates() {
        assertNull(pendingChargeAmountForDisplay(true, "CNY" to 23.0, 31.0))
    }

    @Test
    fun pendingAmountKeepsItsOwnCurrencyAndLegacyHasNone() {
        assertEquals(PendingChargeAmount(23.0, "¥"), pendingChargeAmountForDisplay(false, "CNY" to 23.0, 31.0))
        assertEquals(PendingChargeAmount(31.0, null), pendingChargeAmountForDisplay(false, null, 31.0))
        assertEquals(PendingChargeAmount(0.0, null), pendingChargeAmountForDisplay(false, null, 0.0))
        assertNull(pendingChargeAmountForDisplay(false, "CNY" to Double.NaN, Double.POSITIVE_INFINITY))
        assertEquals(PendingChargeAmount(4.0, null), pendingChargeAmountForDisplay(false, "unknown" to 4.0, null))
    }

    @Test
    fun partialEnergySupportsOnlyTheMatchingEstimatedUnitCost() {
        val estimated = ChargeDetailCostPresentation(15.0, ChargeDetailCostState.ESTIMATE, "estimate_partial_counter")
        val rate = requireNotNull(chargeUnitCost(estimated, null, 10.0))
        assertEquals(1.5, rate.amount, 0.0)
        assertTrue(rate.estimated)
        assertNull(chargeUnitCost(estimated, null, 0.0))
        assertNull(chargeUnitCost(estimated, null, Double.NaN))
        assertNull(chargeUnitCost(estimated, 0.0, 10.0))
        assertNull(chargeUnitCost(estimated.copy(qualificationReason = null), null, 10.0))
        for (state in listOf(ChargeDetailCostState.MANUAL, ChargeDetailCostState.ACTUAL, ChargeDetailCostState.FREE))
            assertNull(chargeUnitCost(ChargeDetailCostPresentation(15.0, state), null, 10.0))
        val manual = requireNotNull(chargeUnitCost(ChargeDetailCostPresentation(15.0, ChargeDetailCostState.MANUAL), 5.0, 10.0))
        assertEquals(3.0, manual.amount, 0.0)
        assertFalse(manual.estimated)
    }

    @Test
    fun configuredDefaultRateIsClearlyEstimated() {
        val presentation = presentChargeDetailCost(energyKwh = 40.6, defaultPricePerKwh = 1.14)
        assertEquals(ChargeDetailCostState.ESTIMATE, presentation.state)
        assertEquals(46.284, presentation.cost!!, 0.00001)
    }

    @Test
    fun teslaMateCostIsActual() {
        val presentation = presentChargeDetailCost(teslaMateCost = 6.5, energyKwh = 8.0)

        assertEquals(ChargeDetailCostState.ACTUAL, presentation.state)
        assertEquals(6.5, presentation.cost ?: error("cost missing"), 0.0001)
    }

    @Test
    fun explicitlyFreeChargeIsFree() {
        val presentation = presentChargeDetailCost(manuallyFree = true, teslaMateCost = 6.5, energyKwh = 8.0)

        assertEquals(ChargeDetailCostState.FREE, presentation.state)
        assertEquals(0.0, presentation.cost ?: error("cost missing"), 0.0001)
    }

    @Test
    fun missingCostWithValidEnergyIsUnavailable() {
        val presentation = presentChargeDetailCost(teslaMateCost = null, energyKwh = 8.0)

        assertEquals(ChargeDetailCostState.UNAVAILABLE, presentation.state)
        assertNull(presentation.cost)
    }

    @Test
    fun missingCostAndEnergyIsUnavailable() {
        val presentation = presentChargeDetailCost(teslaMateCost = null, energyKwh = null)

        assertEquals(ChargeDetailCostState.UNAVAILABLE, presentation.state)
        assertNull(presentation.cost)
    }

    @Test
    fun zeroCostWithoutExplicitFreeIsUnavailable() {
        val presentation = presentChargeDetailCost(teslaMateCost = 0.0, energyKwh = 8.0)

        assertEquals(ChargeDetailCostState.UNAVAILABLE, presentation.state)
        assertNull(presentation.cost)
    }

    @Test
    fun invalidCostsRemainUnavailable() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { cost ->
            val presentation = presentChargeDetailCost(teslaMateCost = cost, energyKwh = 8.0)

            assertEquals(ChargeDetailCostState.UNAVAILABLE, presentation.state)
            assertNull(presentation.cost)
        }
    }

    @Test
    fun trueZeroEnergyDoesNotInventAZeroCost() {
        val presentation = presentChargeDetailCost(teslaMateCost = null, energyKwh = 0.0)

        assertEquals(ChargeDetailCostState.UNAVAILABLE, presentation.state)
        assertNull(presentation.cost)
    }

    @Test
    fun invalidEnergyLeavesCostUnavailable() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0).forEach { energyKwh ->
            val presentation = presentChargeDetailCost(teslaMateCost = null, energyKwh = energyKwh)

            assertEquals(ChargeDetailCostState.UNAVAILABLE, presentation.state)
            assertNull(presentation.cost)
        }
    }

    @Test
    fun manualCostStateIsPreservedForFutureInputs() {
        val presentation = presentChargeDetailCost(manualAmount = 5.0, energyKwh = 8.0)

        assertEquals(ChargeDetailCostState.MANUAL, presentation.state)
        assertEquals(5.0, presentation.cost ?: error("cost missing"), 0.0001)
    }

    @Test
    fun presentationIsDeterministic() {
        assertEquals(
            presentChargeDetailCost(teslaMateCost = null, energyKwh = 8.0),
            presentChargeDetailCost(teslaMateCost = null, energyKwh = 8.0)
        )
    }

    @Test
    fun estimatedAmountKeepsTheApproxMarker() {
        val text = formatChargeDetailCostText(
            state = ChargeDetailCostState.ESTIMATE,
            cost = 2.39,
            currencySymbol = "¥",
            freeLabel = "免费",
            unavailableLabel = "暂无数据"
        )

        assertEquals("≈¥2.39", text)
    }

    @Test
    fun billedAmountsCarryNoApproxMarker() {
        listOf(ChargeDetailCostState.ACTUAL, ChargeDetailCostState.MANUAL).forEach { state ->
            val text = formatChargeDetailCostText(
                state = state,
                cost = 6.5,
                currencySymbol = "¥",
                freeLabel = "免费",
                unavailableLabel = "暂无数据"
            )

            assertEquals("¥6.50", text)
        }
    }

    @Test
    fun freeAndUnavailableStatesShowLabelsInsteadOfAmounts() {
        assertEquals(
            "免费",
            formatChargeDetailCostText(
                state = ChargeDetailCostState.FREE,
                cost = 0.0,
                currencySymbol = "¥",
                freeLabel = "免费",
                unavailableLabel = "暂无数据"
            )
        )
        assertEquals(
            "暂无数据",
            formatChargeDetailCostText(
                state = ChargeDetailCostState.UNAVAILABLE,
                cost = null,
                currencySymbol = "¥",
                freeLabel = "免费",
                unavailableLabel = "暂无数据"
            )
        )
    }
}

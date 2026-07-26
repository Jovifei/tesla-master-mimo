package com.matelink.ui.screens.charges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargeDetailCostPresentationTest {

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
    fun missingCostWithValidEnergyIsEstimated() {
        val presentation = presentChargeDetailCost(teslaMateCost = null, energyKwh = 8.0)

        assertEquals(ChargeDetailCostState.ESTIMATED, presentation.state)
        assertEquals(8.8, presentation.cost ?: error("cost missing"), 0.0001)
    }

    @Test
    fun missingCostAndEnergyIsUnavailable() {
        val presentation = presentChargeDetailCost(teslaMateCost = null, energyKwh = null)

        assertEquals(ChargeDetailCostState.UNAVAILABLE, presentation.state)
        assertNull(presentation.cost)
    }

    @Test
    fun zeroCostWithoutExplicitFreeIsEstimated() {
        val presentation = presentChargeDetailCost(teslaMateCost = 0.0, energyKwh = 8.0)

        assertEquals(ChargeDetailCostState.ESTIMATED, presentation.state)
        assertEquals(8.8, presentation.cost ?: error("cost missing"), 0.0001)
    }

    @Test
    fun invalidCostsUseExistingEstimateRule() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { cost ->
            val presentation = presentChargeDetailCost(teslaMateCost = cost, energyKwh = 8.0)

            assertEquals(ChargeDetailCostState.ESTIMATED, presentation.state)
            assertEquals(8.8, presentation.cost ?: error("cost missing"), 0.0001)
        }
    }

    @Test
    fun trueZeroEnergyCanProduceAnEstimatedZero() {
        val presentation = presentChargeDetailCost(teslaMateCost = null, energyKwh = 0.0)

        assertEquals(ChargeDetailCostState.ESTIMATED, presentation.state)
        assertEquals(0.0, presentation.cost ?: error("cost missing"), 0.0001)
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
}

package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EffectiveChargeCostResolverTest {

    @Test
    fun explicitManualAmount_hasHighestPriority() {
        val result = EffectiveChargeCostResolver.resolve(
            EffectiveChargeCostInput(
                manualAmount = 12.5,
                manuallyFree = true,
                teslaMateCost = 8.0,
                energyKwh = 10.0
            )
        )

        assertEquals(12.5, result.cost!!, 0.0)
        assertEquals(ChargeCostSource.MANUAL, result.source)
    }

    @Test
    fun explicitManualFree_winsOverTeslaMateAndEstimate() {
        val result = EffectiveChargeCostResolver.resolve(
            EffectiveChargeCostInput(
                manuallyFree = true,
                teslaMateCost = 8.0,
                energyKwh = 10.0
            )
        )

        assertEquals(0.0, result.cost!!, 0.0)
        assertEquals(ChargeCostSource.FREE, result.source)
    }

    @Test
    fun positiveTeslaMateCost_isUsedWithoutEstimating() {
        val result = EffectiveChargeCostResolver.resolve(
            EffectiveChargeCostInput(teslaMateCost = 8.0, energyKwh = 10.0)
        )

        assertEquals(8.0, result.cost!!, 0.0)
        assertEquals(ChargeCostSource.TESLAMATE, result.source)
    }

    @Test
    fun zeroTeslaMateCost_isNotTreatedAsFree() {
        val result = EffectiveChargeCostResolver.resolve(
            EffectiveChargeCostInput(teslaMateCost = 0.0, energyKwh = 10.0)
        )

        assertEquals(11.0, result.cost!!, 0.0)
        assertEquals(ChargeCostSource.ESTIMATE, result.source)
    }

    @Test
    fun negativeManualAmount_isIgnoredInsteadOfCreatingAnInvalidCost() {
        val result = EffectiveChargeCostResolver.resolve(
            EffectiveChargeCostInput(
                manualAmount = -5.0,
                energyKwh = 10.0
            )
        )

        assertEquals(ChargeCostSource.ESTIMATE, result.source)
        assertEquals(11.0, result.cost!!, 0.0001)
    }

    @Test
    fun missingTeslaMateCost_isNotTreatedAsFree() {
        val result = EffectiveChargeCostResolver.resolve(
            EffectiveChargeCostInput(teslaMateCost = null, energyKwh = 10.0)
        )

        assertEquals(11.0, result.cost!!, 0.0)
        assertEquals(ChargeCostSource.ESTIMATE, result.source)
    }

    @Test
    fun negativeAndNonFiniteEnergyCannotProduceEstimatedCost() {
        listOf(
            -1.0,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY
        ).forEach { energy ->
            val result = EffectiveChargeCostResolver.resolve(
                EffectiveChargeCostInput(energyKwh = energy)
            )

            assertNull(result.cost)
            assertEquals(ChargeCostSource.ESTIMATE, result.source)
        }
    }

    @Test
    fun observedZeroEnergyRemainsAValidEstimatedZero() {
        val result = EffectiveChargeCostResolver.resolve(
            EffectiveChargeCostInput(energyKwh = 0.0)
        )

        assertEquals(0.0, result.cost!!, 0.0)
        assertEquals(ChargeCostSource.ESTIMATE, result.source)
    }
}

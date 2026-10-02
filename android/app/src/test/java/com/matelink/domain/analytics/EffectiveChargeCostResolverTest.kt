package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveChargeCostResolverTest {

    @Test fun configuredRateEstimatesObservedEnergy() {
        val result = EffectiveChargeCostResolver.resolve(EffectiveChargeCostInput(energyKwh = 40.6, defaultPricePerKwh = 1.14))
        assertEquals(46.284, result.cost!!, 0.00001)
        assertEquals(ChargeCostSource.ESTIMATE, result.source)
    }

    @Test fun missingEnergyCannotBeEstimated() {
        assertEquals(null, EffectiveChargeCostResolver.resolve(EffectiveChargeCostInput(defaultPricePerKwh = 1.14)).cost)
    }

    @Test fun manualAndActualAmountsOverrideConfiguredRate() {
        assertEquals(5.0, EffectiveChargeCostResolver.resolve(EffectiveChargeCostInput(manualAmount = 5.0, teslaMateCost = 8.0, energyKwh = 10.0, defaultPricePerKwh = 1.14)).cost!!, 0.0)
        assertEquals(8.0, EffectiveChargeCostResolver.resolve(EffectiveChargeCostInput(teslaMateCost = 8.0, energyKwh = 10.0, defaultPricePerKwh = 1.14)).cost!!, 0.0)
    }

    @Test fun invalidRateOrEnergyStaysUnavailable() {
        for (value in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(null, EffectiveChargeCostResolver.resolve(EffectiveChargeCostInput(energyKwh = value, defaultPricePerKwh = 1.14)).cost)
            assertEquals(null, EffectiveChargeCostResolver.resolve(EffectiveChargeCostInput(energyKwh = 10.0, defaultPricePerKwh = value)).cost)
        }
    }

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

        assertEquals(null, result.cost)
        assertEquals(ChargeCostSource.UNAVAILABLE, result.source)
    }

    @Test
    fun negativeManualAmount_isIgnoredInsteadOfCreatingAnInvalidCost() {
        val result = EffectiveChargeCostResolver.resolve(
            EffectiveChargeCostInput(
                manualAmount = -5.0,
                energyKwh = 10.0
            )
        )

        assertEquals(ChargeCostSource.UNAVAILABLE, result.source)
        assertEquals(null, result.cost)
    }

    @Test
    fun missingTeslaMateCost_isNotTreatedAsFree() {
        val result = EffectiveChargeCostResolver.resolve(
            EffectiveChargeCostInput(teslaMateCost = null, energyKwh = 10.0)
        )

        assertEquals(null, result.cost)
        assertEquals(ChargeCostSource.UNAVAILABLE, result.source)
    }
}

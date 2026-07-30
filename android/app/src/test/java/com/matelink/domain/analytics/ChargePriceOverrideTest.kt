package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChargePriceOverrideTest {

    @Test
    fun calculatesManualAmountFromUnitPriceAndEnergy() {
        assertEquals(8.8, manualChargeAmount(1.1, 8.0)!!, 0.0001)
    }

    @Test
    fun keepsVehicleAndChargeKeysIsolated() {
        assertEquals("4:12", chargePriceOverrideKey(4, 12))
        assertEquals("5:12", chargePriceOverrideKey(5, 12))
    }

    @Test
    fun rejectsInvalidPriceOrEnergy() {
        assertNull(manualChargeAmount(-1.0, 8.0))
        assertNull(manualChargeAmount(Double.NaN, 8.0))
        assertNull(manualChargeAmount(1.0, Double.POSITIVE_INFINITY))
        assertNull(manualChargeAmount(1.0, null))
    }

    @Test
    fun allowsExplicitZeroPrice() {
        assertEquals(0.0, manualChargeAmount(0.0, 8.0)!!, 0.0)
    }

    @Test
    fun manualUnitPriceOverridesRecordedCost() {
        val result = resolveChargeCost(
            pricePerKwh = 0.8,
            freeSupercharging = false,
            isDcCharge = false,
            teslaMateCost = 12.0,
            energyKwh = 10.0
        )

        assertEquals(8.0, result.cost!!, 0.0001)
        assertEquals(ChargeCostSource.MANUAL, result.source)
    }

    @Test
    fun freeSuperchargingOnlyAppliesToDcSessions() {
        val ac = resolveChargeCost(null, true, false, null, 10.0)
        val dc = resolveChargeCost(null, true, true, null, 10.0)

        assertEquals(ChargeCostSource.ESTIMATE, ac.source)
        assertEquals(ChargeCostSource.FREE, dc.source)
    }
}

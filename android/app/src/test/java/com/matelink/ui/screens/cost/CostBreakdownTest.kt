package com.matelink.ui.screens.cost

import com.matelink.data.api.models.ChargeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CostBreakdownTest {
    @Test
    fun monthlyBreakdownOmitsMissingPricesButKeepsObservedFreeCharge() {
        val charges = listOf(
            ChargeData(chargeId = 1, startDate = "2026-01-02T10:00:00", cost = null),
            ChargeData(chargeId = 2, startDate = "2026-02-02T10:00:00", cost = 0.0),
            ChargeData(chargeId = 3, startDate = "2026-03-02T10:00:00", cost = 12.5)
        )

        val result = buildMonthlyCosts(charges, emptySet()) { it.cost }

        assertEquals(listOf("2026-02", "2026-03"), result.map { it.month })
        assertEquals(0.0, result.first().acCost, 0.0001)
        assertEquals(12.5, result.last().acCost, 0.0001)
    }

    @Test
    fun locationBreakdownOmitsLocationsWithNoPriceSource() {
        val charges = listOf(
            ChargeData(chargeId = 1, address = "Unknown station", cost = null),
            ChargeData(chargeId = 2, address = "Free station", cost = 0.0),
            ChargeData(chargeId = 3, address = "Free station", cost = 0.0),
            ChargeData(chargeId = 4, address = "Paid station", cost = 8.0)
        )

        val result = buildLocationCosts(charges) { it.cost }

        assertTrue(result.none { it.address == "Unknown station" })
        assertEquals(2, result.first { it.address == "Free station" }.count)
        assertEquals(0.0, result.first { it.address == "Free station" }.totalCost, 0.0001)
    }
}

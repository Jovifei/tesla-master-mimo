package com.matelink.ui.screens.charges

import com.matelink.data.api.models.ChargeData
import com.matelink.domain.analytics.ChargeCostSource
import com.matelink.domain.analytics.projectEffectiveChargeCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargeSummaryMetricTest {
    @Test
    fun missingNonFiniteAndNegativeEnergyAreUnavailable() {
        assertNull(observedChargeEnergy(null))
        assertNull(observedChargeEnergy(Double.NaN))
        assertNull(observedChargeEnergy(-0.1))
    }

    @Test
    fun observedZeroEnergyRemainsAvailable() {
        assertEquals(0.0, observedChargeEnergy(0.0) ?: Double.NaN, 0.0)
    }

    @Test
    fun sameCardProjectionFeedsWindowTotalAndAverageWithoutClaimingUnknownCosts() {
        fun charge(id: Int, energy: Double?, cost: Double? = null, currency: String? = null) = ChargeData(
            chargeId = id, startDate = "2026-10-20T08:00:00Z", endDate = "2026-10-20T09:00:00Z",
            source = "teslamate_archive", sourceInstanceId = "archive-A",
            chargeEnergyAdded = energy, cost = cost, currencyCode = currency
        )
        fun project(row: ChargeData, free: Boolean = false) = projectEffectiveChargeCost(
            charge = row, localHistoryCarId = 42, manual = null,
            selectedCurrencyCode = "CNY", freeSupercharging = free, isDcCharge = free,
            defaultPricePerKwh = 1.14, sessionFreeVerified = free
        )

        val estimate = project(charge(1, 2.0))
        val paid = project(charge(2, 4.0, cost = 5.0, currency = "CNY"))
        val free = project(charge(3, 3.0), free = true)
        val unknown = project(charge(4, null))
        val otherCurrency = project(charge(5, 8.0, cost = 9.0, currency = "USD"))
        val noSource = project(ChargeData(chargeId = 8, cost = 12.0, currencyCode = "CNY"))

        assertEquals(ChargeCostSource.ESTIMATE, estimate.source)
        assertEquals(2.28, estimate.cost!!, 0.0001)
        assertEquals(ChargeCostSource.TESLAMATE, paid.source)
        assertEquals(ChargeCostSource.FREE, free.source)
        assertEquals(ChargeCostSource.UNAVAILABLE, otherCurrency.source)
        assertEquals(ChargeCostSource.UNAVAILABLE, noSource.source)
        assertTrue(projectedCostMatchesFilter(estimate, CostFilter.HAS_COST))
        assertFalse(projectedCostMatchesFilter(estimate, CostFilter.NO_COST))
        assertTrue(projectedCostMatchesFilter(free, CostFilter.NO_COST))
        assertTrue(projectedCostMatchesFilter(unknown, CostFilter.NO_COST))
        assertFalse(projectedCostMatchesFilter(otherCurrency, CostFilter.HAS_COST))
        val totals = summarizeProjectedChargeCosts(listOf(estimate, paid, free, unknown, otherCurrency, noSource))
        assertEquals(7.28, totals.total!!, 0.0001)
        assertEquals(7.28 / 3, totals.average!!, 0.0001)
        assertEquals(3, totals.coveredCount)
        assertEquals(1, totals.estimatedCount)
    }

    @Test
    fun estimatedZeroIsCoveredButAbsentEnergyRemainsUnknown() {
        val zero = projectEffectiveChargeCost(
            ChargeData(chargeId = 6, source = "teslamate_archive", chargeEnergyAdded = 0.0),
            localHistoryCarId = 42, manual = null, selectedCurrencyCode = "CNY",
            defaultPricePerKwh = 1.14
        )
        val missing = projectEffectiveChargeCost(
            ChargeData(chargeId = 7, source = "teslamate_archive"),
            localHistoryCarId = 42, manual = null, selectedCurrencyCode = "CNY",
            defaultPricePerKwh = 1.14
        )
        val totals = summarizeProjectedChargeCosts(listOf(zero, missing))
        assertEquals(0.0, totals.total!!, 0.0)
        assertEquals(0.0, totals.average!!, 0.0)
        assertEquals(1, totals.coveredCount)
        assertEquals(1, totals.estimatedCount)
        assertNull(summarizeProjectedChargeCosts(listOf(missing)).total)
    }
}

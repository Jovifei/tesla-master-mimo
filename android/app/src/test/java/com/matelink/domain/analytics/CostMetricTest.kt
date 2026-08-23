package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CostMetricTest {

    @Test
    fun observedZeroCostIsRetainedWhenSourceRecordExists() {
        assertEquals(
            0.0,
            observedAggregateCostOrNull(0.0, pricedRecordCount = 1)
                ?: error("observed zero cost was discarded"),
            0.0
        )
    }

    @Test
    fun coalescedZeroWithoutPricedRecordIsUnavailable() {
        assertNull(observedAggregateCostOrNull(0.0, pricedRecordCount = 0))
    }

    @Test
    fun invalidAggregateIsUnavailable() {
        assertNull(observedAggregateCostOrNull(Double.NaN, pricedRecordCount = 1))
        assertNull(observedAggregateCostOrNull(Double.POSITIVE_INFINITY, pricedRecordCount = 1))
        assertNull(observedAggregateCostOrNull(-1.0, pricedRecordCount = 1))
    }

    @Test
    fun observedCostSumRetainsFreeChargesAndIgnoresMissingCosts() {
        assertEquals(0.0, observedCostSumOrNull(listOf(null, 0.0, null))!!, 0.0)
        assertNull(observedCostSumOrNull(listOf(null, Double.NaN, -1.0)))
    }

    @Test
    fun costPerDistanceRetainsObservedFreeCost() {
        assertEquals(0.0, observedCostPerDistanceOrNull(0.0, 100.0)!!, 0.0)
        assertNull(observedCostPerDistanceOrNull(null, 100.0))
        assertNull(observedCostPerDistanceOrNull(10.0, 0.0))
    }
}

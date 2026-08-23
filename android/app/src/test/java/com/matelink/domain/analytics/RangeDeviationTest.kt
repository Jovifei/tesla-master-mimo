package com.matelink.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeDeviationTest {
    @Test
    fun deviationIsAbsoluteDifferenceFromRatedDrop() {
        assertEquals(10.0, ratedRangeDeviationPercent(100.0, 90.0)!!, 0.0001)
        assertEquals(10.0, ratedRangeDeviationPercent(100.0, 110.0)!!, 0.0001)
    }

    @Test
    fun invalidRatedDropRemainsUnavailable() {
        assertNull(ratedRangeDeviationPercent(0.0, 10.0))
        assertNull(ratedRangeDeviationPercent(null, 10.0))
        assertNull(ratedRangeDeviationPercent(10.0, Double.NaN))
    }
}

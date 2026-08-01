package com.matelink.analytics

import com.matelink.domain.analytics.percentilePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PercentileTest {
    @Test
    fun lowestIsZeroAndHighestIsOneHundred() {
        val samples = listOf(100.0, 120.0, 150.0, 200.0)
        assertEquals(0, percentilePosition(samples, 100.0)!!.percentile)
        assertEquals(100, percentilePosition(samples, 200.0)!!.percentile)
        assertEquals(4, percentilePosition(samples, 150.0)!!.sampleCount)
    }

    @Test
    fun invalidSamplesProduceNoPosition() {
        assertNull(percentilePosition(emptyList(), 120.0))
        assertNull(percentilePosition(listOf(100.0), Double.NaN))
    }
}

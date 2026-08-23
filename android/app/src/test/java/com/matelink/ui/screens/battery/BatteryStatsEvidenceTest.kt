package com.matelink.ui.screens.battery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryStatsEvidenceTest {
    @Test
    fun missingLiveFieldsAreNotReportedAsLiveStatus() {
        val stats = sampleStats()

        assertFalse(stats.hasLiveStatus)
        assertFalse(stats.hasBatteryStatus)
        assertFalse(stats.hasRangeStatus)
    }

    @Test
    fun observedZeroBatteryLevelRemainsAvailable() {
        val stats = sampleStats(batteryLevelObserved = 0)

        assertTrue(stats.hasLiveStatus)
        assertTrue(stats.hasBatteryStatus)
    }

    @Test
    fun observedRangeFieldsRemainAvailableIndependently() {
        val stats = sampleStats(ratedRangeObserved = 320.0)

        assertTrue(stats.hasLiveStatus)
        assertFalse(stats.hasBatteryStatus)
        assertTrue(stats.hasRangeStatus)
    }

    private fun sampleStats(
        batteryLevelObserved: Int? = null,
        ratedRangeObserved: Double? = null
    ) = BatteryStats(
        currentCapacity = 0.0,
        originalCapacity = 0.0,
        healthPercent = 0.0,
        lossKwh = 0.0,
        lossPercent = 0.0,
        maxRangeNew = null,
        maxRangeNow = null,
        rangeLoss = null,
        ratedEfficiency = 0.0,
        batteryLevel = batteryLevelObserved ?: 0,
        usableBatteryLevel = batteryLevelObserved ?: 0,
        estimatedRange = 0.0,
        ratedRange = ratedRangeObserved ?: 0.0,
        idealRange = 0.0,
        rangeAt100 = null,
        batteryLevelObserved = batteryLevelObserved,
        ratedRangeObserved = ratedRangeObserved
    )
}

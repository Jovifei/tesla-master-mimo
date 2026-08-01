package com.matelink.analytics

import com.matelink.domain.analytics.StandbyCause
import com.matelink.domain.analytics.standbyAttribution
import org.junit.Assert.assertEquals
import org.junit.Test

class StandbyAttributionTest {
    @Test
    fun batteryDropWithoutEvidenceRemainsUnknown() {
        val result = standbyAttribution(false, false, false)
        assertEquals(StandbyCause.UNKNOWN, result.cause)
        assertEquals(0f, result.confidence)
    }

    @Test
    fun recordedCauseIsReportedWithFullConfidence() {
        assertEquals(StandbyCause.SENTINEL, standbyAttribution(true, false, false).cause)
        assertEquals(StandbyCause.CABIN_OVERHEAT, standbyAttribution(false, true, false).cause)
    }
}

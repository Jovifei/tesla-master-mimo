package com.matelink.ui.screens.drives

import com.matelink.data.api.models.LinkedCharge
import com.matelink.data.api.models.ParkedDetailData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ParkedChargePresentationTest {
    @Test fun legacySingleLinkedChargeStillBecomesASeparateChargeSession() {
        val parked = ParkedDetailData(
            olderDriveId = 10, newerDriveId = 11,
            startDate = "2026-10-23T00:00:00Z", endDate = "2026-10-23T10:00:00Z",
            source = "teslamate_archive",
            linkedCharge = LinkedCharge(42, "2026-10-23T01:00:00Z", "2026-10-23T03:00:00Z", 6.0)
        )
        val result = parkedChargeBreakdown(parked, emptyList())
        assertEquals(42, result.sessions.single().chargeId)
        assertEquals(3.0, result.sessions.single().energyPerHourKwh!!, 0.0)
        assertFalse(result.storedSessionsComplete)
    }
}

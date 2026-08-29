package com.matelink.data.api.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParkedDetailCompatibilityTest {
    @Test
    fun legacyParkedDetailCanOmitLinkedCharge() {
        val detail = ParkedDetailData(
            olderDriveId = 1,
            newerDriveId = 2,
            startDate = "2026-08-01T00:00:00Z",
            endDate = "2026-08-01T03:00:00Z",
            source = "teslamate"
        )

        assertNull(detail.linkedCharge)
    }

    @Test
    fun linkedChargeUsesTheServerChargeIdForDirectNavigation() {
        val linked = LinkedCharge(chargeId = 42)

        assertEquals(42, linked.chargeId)
    }
}

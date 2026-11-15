package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.entity.ChargeCostOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingChargeCostOverrideTest {
    private val charge = ChargeData(
        chargeId = 7,
        startDate = "2026-10-27T11:14:00Z",
        endDate = "2026-10-27T11:25:00Z",
        source = "teslamate",
        sourceInstanceId = "instance-a",
        sourceId = "charge-7",
        sourceRecordId = "charge-7"
    )

    private fun record(
        amount: Double,
        sourceInstanceId: String = "instance-old"
    ) = ChargeCostOverride(
        carId = -7,
        chargeId = 7,
        manualTotalAmount = amount,
        sessionKey = "session:old:CNY",
        currencyCode = "CNY",
        sourceKind = "teslamate",
        sourceInstanceId = sourceInstanceId,
        sourceRecordId = "charge-7",
        startInstant = "2026-10-27T11:14:00Z",
        endInstant = "2026-10-27T11:25:00Z"
    )

    @Test
    fun oneUnboundAmountIsAvailableAsPendingEvidence() {
        val pending = findPendingChargeCostOverride(listOf(record(2.10)), -7, charge, "CNY")
        assertEquals(2.10, pending?.manualTotalAmount ?: error("missing pending amount"), 0.0)
    }

    @Test
    fun multipleUnboundSessionsRemainUnknown() {
        val pending = findPendingChargeCostOverride(
            listOf(record(2.10, "instance-old"), record(3.10, "instance-other")),
            -7, charge, "CNY"
        )
        assertNull(pending)
    }
}

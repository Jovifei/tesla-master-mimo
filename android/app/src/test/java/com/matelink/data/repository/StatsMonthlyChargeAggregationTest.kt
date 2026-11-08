package com.matelink.data.repository

import com.matelink.data.api.models.ChargeData
import com.matelink.data.sync.toSyncSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class StatsMonthlyChargeAggregationTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test fun localYearGroupsQualifiedEnergyAndPreservesUnknownVersusTrueZero() {
        val rows = listOf(
            charge(1, "2025-12-31T16:30:00Z", 2.0, cost = 0.0),
            charge(2, "2026-01-02T10:00:00Z", null),
            charge(3, "2026-02-01T10:00:00Z", 0.0),
            charge(4, "2026-03-01T10:00:00Z", 10.0, source = "fleet_mqtt"),
            charge(5, "2026-12-31T16:30:00Z", 9.0),
            charge(1, "2025-12-31T16:30:00Z", 999.0, carId = 701)
        )

        val months = buildQualifiedMonthlyChargeAggregations(rows, 700, 2026, shanghai)

        assertEquals(listOf(1, 2, 3), months.map { it.month })
        assertEquals(2, months[0].chargeCount)
        assertEquals(1, months[0].energySampleCount)
        assertEquals(2.0, months[0].totalEnergy!!, 0.0001)
        assertEquals(1, months[0].costSampleCount)
        assertEquals(0.0, months[0].totalCost!!, 0.0001)
        assertEquals(1, months[1].energySampleCount)
        assertEquals(0.0, months[1].totalEnergy!!, 0.0001)
        assertEquals(1, months[2].chargeCount)
        assertEquals(0, months[2].energySampleCount)
        assertNull(months[2].totalEnergy)

        val nextYear = buildQualifiedMonthlyChargeAggregations(rows, 700, 2027, shanghai)
        assertEquals(listOf(1), nextYear.map { it.month })
        assertEquals(9.0, nextYear.single().totalEnergy!!, 0.0001)
    }

    @Test fun allUnknownMonthRemainsPresentWithNoEnergySample() {
        val rows = listOf(charge(1, "2026-07-01T08:00:00Z", null),
            charge(2, "2026-07-02T08:00:00Z", 8.0, source = "fleet_mqtt"))
        val month = buildQualifiedMonthlyChargeAggregations(rows, 700, 2026, shanghai).single()

        assertEquals(7, month.month)
        assertEquals(2, month.chargeCount)
        assertEquals(0, month.energySampleCount)
        assertNull(month.totalEnergy)
    }

    private fun charge(id: Int, start: String, energy: Double?, cost: Double? = null,
                       source: String = "teslamate_archive", carId: Int = 700) = ChargeData(
        chargeId = id, startDate = start,
        endDate = Instant.parse(start).plusSeconds(3600).toString(),
        chargeEnergyAdded = energy, cost = cost, source = source, qualityState = "observed"
    ).toSyncSummary(carId)!!
}

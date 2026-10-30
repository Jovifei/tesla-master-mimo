package com.matelink.data.repository

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.sync.toSyncSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class StatsMonthlyDriveAggregationTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test fun localYearUsesQualifiedValuesAndDistinguishesUnknownZeroAndNetRecovery() {
        val rows = listOf(
            drive(1, "2025-12-31T16:30:00Z", 10.0, 2.0),
            drive(2, "2026-01-02T10:00:00Z", null, null),
            drive(3, "2026-02-01T10:00:00Z", 0.0, 0.0),
            drive(4, "2026-03-01T10:00:00Z", 5.0, -0.5),
            drive(5, "2026-04-01T10:00:00Z", null, 10.0, source = "fleet_mqtt"),
            drive(6, "2026-12-31T16:30:00Z", 9.0, 3.0),
            drive(1, "2025-12-31T16:30:00Z", 999.0, 999.0, carId = 701)
        )

        val months = buildQualifiedMonthlyDriveAggregations(rows, 700, 2026, shanghai)

        assertEquals(listOf(1, 2, 3, 4), months.map { it.month })
        assertEquals(2, months[0].driveCount)
        assertEquals(1, months[0].distanceSampleCount)
        assertEquals(1, months[0].energySampleCount)
        assertEquals(10.0, months[0].totalDistance!!, 0.0001)
        assertEquals(2.0, months[0].totalEnergy!!, 0.0001)
        assertEquals(1, months[1].distanceSampleCount)
        assertEquals(1, months[1].energySampleCount)
        assertEquals(0.0, months[1].totalDistance!!, 0.0001)
        assertEquals(0.0, months[1].totalEnergy!!, 0.0001)
        assertEquals(-0.5, months[2].totalEnergy!!, 0.0001)
        assertEquals(0, months[3].distanceSampleCount)
        assertEquals(0, months[3].energySampleCount)
        assertNull(months[3].totalDistance)
        assertNull(months[3].totalEnergy)

        val nextYear = buildQualifiedMonthlyDriveAggregations(rows, 700, 2027, shanghai)
        assertEquals(listOf(1), nextYear.map { it.month })
        assertEquals(9.0, nextYear.single().totalDistance!!, 0.0001)
        assertEquals(3.0, nextYear.single().totalEnergy!!, 0.0001)
    }

    private fun drive(id: Int, start: String, distance: Double?, energy: Double?,
                      source: String = "teslamate_archive", carId: Int = 700) = DriveData(
        driveId = id, startDate = start,
        endDate = Instant.parse(start).plusSeconds(3600).toString(),
        odometerDetails = DriveOdometerDetails(distance = distance), energyConsumedNet = energy,
        source = source, qualityState = "observed", lengthUnit = "km"
    ).toSyncSummary(carId)!!
}

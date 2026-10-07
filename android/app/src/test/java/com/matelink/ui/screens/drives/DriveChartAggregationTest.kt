package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DriveChartAggregationTest {
    private val today = LocalDate.of(2026, 10, 4)
    private fun drive(id: Int, start: String?, speed: Int? = null, distance: Double? = null, minutes: Int? = null) =
        DriveData(id, startDate = start, speedMax = speed, durationMin = minutes,
            odometerDetails = DriveOdometerDetails(distance = distance))
    private fun chart(drives: List<DriveData>, granularity: DriveChartGranularity = DriveChartGranularity.MONTHLY,
        start: String? = null, end: String? = null, zone: String = "UTC") = calculateDriveChartData(
        drives, granularity, start?.let(LocalDate::parse), end?.let(LocalDate::parse), today,
        ZoneId.of(zone), Locale.UK) { "Week $it" }

    @Test fun customMonthlyEndDoesNotExtendToToday() {
        val result = chart(listOf(drive(1, "2026-07-08T00:00:00Z")), start = "2026-07-01", end = "2026-08-31")
        assertEquals(listOf("Jul", "Aug"), result.map { it.label })
        assertEquals(listOf(1, 0), result.map { it.count })
    }
    @Test fun dailyEndIsInclusiveAndExcludedRowsStayOut() {
        val result = chart(listOf(drive(1, "2026-07-01T12:00:00Z"), drive(2, "2026-07-02T23:59:59Z"),
            drive(3, "2026-07-03T00:00:00Z")), DriveChartGranularity.DAILY, "2026-07-01", "2026-07-02")
        assertEquals(2, result.size)
        assertEquals(listOf(1, 1), result.map { it.count })
    }
    @Test fun firstPartialWeekIsKeptWithoutEarlierDrive() {
        val result = chart(listOf(drive(1, "2026-07-06T12:00:00Z"), drive(2, "2026-07-08T12:00:00Z"),
            drive(3, "2026-07-13T12:00:00Z")), DriveChartGranularity.WEEKLY, "2026-07-08", "2026-07-14")
        assertEquals(listOf(LocalDate.parse("2026-07-06").toEpochDay(), LocalDate.parse("2026-07-13").toEpochDay()), result.map { it.sortKey })
        assertEquals(listOf(1, 1), result.map { it.count })
    }
    @Test fun partialWeekAcrossYearCountsExactlyOnce() {
        val result = chart(listOf(drive(1, "2025-12-31T12:00:00Z"), drive(2, "2026-01-04T12:00:00Z"),
            drive(3, "2026-01-05T12:00:00Z")), DriveChartGranularity.WEEKLY, "2025-12-31", "2026-01-05")
        assertEquals(listOf(2, 1), result.map { it.count })
    }
    @Test fun fallbackEndUsesSuppliedToday() {
        val result = chart(listOf(drive(1, "2026-10-03T12:00:00Z")), DriveChartGranularity.DAILY)
        assertEquals(listOf(1, 0), result.map { it.count })
        assertEquals(today.toEpochDay(), result.last().sortKey)
    }
    @Test fun groupingUsesTheRequestZoneAtMidnight() {
        val result = chart(listOf(drive(1, "2026-07-31T16:30:00Z")), start = "2026-08-01", end = "2026-08-31", zone = "Asia/Shanghai")
        assertEquals(listOf(1), result.map { it.count })
        assertEquals("Aug", result.single().label)
    }
    @Test fun negativeOffsetAndLocalDateTimeFollowRequestZone() {
        val result = chart(listOf(drive(1, "2026-08-01T01:00:00Z"), drive(2, "2026-07-31T23:00:00")),
            start = "2026-07-31", end = "2026-07-31", zone = "America/Los_Angeles")
        assertEquals(2, result.single().count)
    }
    @Test fun maxIsNotAverageAndTotalsKeepApiUnits() {
        val result = chart(listOf(drive(1, "2026-07-10T00:00:00Z", 40, 12.5, 10),
            drive(2, "2026-07-11T00:00:00Z", 100, 20.25, 25)), start = "2026-07-01", end = "2026-07-31").single()
        assertEquals(100, result.maxSpeed)
        assertEquals(2, result.count)
        assertEquals(32.75, result.totalDistance, 0.0)
        assertEquals(35, result.totalDurationMin)
    }
    @Test fun unknownSpeedIsNotZeroButObservedZeroIsValid() {
        val result = chart(listOf(drive(1, "2026-07-10T00:00:00Z", -1), drive(2, "2026-07-11T00:00:00Z"),
            drive(3, "2026-08-01T00:00:00Z", 0)), start = "2026-07-01", end = "2026-08-31")
        assertNull(result.first().maxSpeed)
        assertEquals(0, result.last().maxSpeed)
    }
    @Test fun crossYearLabelsRemainDistinct() {
        val result = chart(listOf(drive(1, "2025-12-31T23:30:00Z")), start = "2025-12-01", end = "2026-01-31")
        assertEquals(listOf("Dec 25", "Jan 26"), result.map { it.label })
    }
    @Test fun invalidDatesNeverCreateAChartBucket() {
        assertTrue(chart(listOf(drive(1, null), drive(2, "not-a-date"))).isEmpty())
    }
    @Test fun emptyAndReversedRangesAreEmpty() {
        assertTrue(chart(emptyList()).isEmpty())
        assertTrue(chart(listOf(drive(1, "2026-07-10T00:00:00Z")), start = "2026-08-01", end = "2026-07-01").isEmpty())
    }
    @Test fun tripCrossingMonthStaysInItsStartMonth() {
        val value = drive(1, "2026-07-31T23:50:00Z", distance = 10.0, minutes = 20).copy(endDate = "2026-08-01T00:10:00Z")
        val result = chart(listOf(value), start = "2026-07-01", end = "2026-08-31")
        assertEquals(listOf(1, 0), result.map { it.count })
        assertEquals(listOf(20, 0), result.map { it.totalDurationMin })
    }
}

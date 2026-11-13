package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DriveWeeklyCardsTest {
    @Test fun sevenDaysIncludeEmptyDatesAndKeepOriginalRecordsAndOrder() {
        val older = DriveData(1, startDate = "2026-11-10T09:00:00", endDate = "2026-11-10T09:20:00")
        val newer = DriveData(2, startDate = "2026-11-10T10:00:00", endDate = "2026-11-10T10:30:00")
        val trip = DriveHistoryItem.Drive(newer)
        val parked = DriveHistoryItem.Parked(older, newer, older.endDate!!, newer.startDate!!, 40, null)
        val groups = weeklyDriveHistoryGroups(listOf(trip, parked, DriveHistoryItem.Drive(older)), LocalDate.of(2026, 11, 11))

        assertEquals(7, groups.size)
        assertEquals(LocalDate.of(2026, 11, 11), groups.first().date)
        assertEquals(LocalDate.of(2026, 11, 5), groups.last().date)
        assertTrue(groups.first().items.isEmpty())
        assertEquals(listOf("drive-2", "parked-1-2", "drive-1"), groups[1].items.map { it.key })
        assertSame(trip, groups[1].items[0])
        assertSame(parked, groups[1].items[1])
    }

    @Test fun filterEmptyRequiresKnownHiddenActivityIncludingAMidnightOverlap() {
        val day = LocalDate.of(2026, 11, 11)
        val zone = ZoneId.of("Asia/Shanghai")
        val overnight = DriveData(1, startDate = "2026-11-10T15:50:00Z", endDate = "2026-11-10T16:10:00Z",
            qualityState = "observed")
        val endedAtMidnight = overnight.copy(endDate = "2026-11-10T16:00:00Z")
        assertTrue(weeklyDayIsFiltered(day, listOf(overnight), emptyList(), zone))
        assertFalse(weeklyDayIsFiltered(day, listOf(overnight), listOf(overnight), zone))
        assertFalse(weeklyDayIsFiltered(day, listOf(endedAtMidnight), emptyList(), zone))
        assertFalse(weeklyDayIsFiltered(day, listOf(overnight.copy(qualityState = "quarantined")), emptyList(), zone))
        assertFalse(weeklyDayIsFiltered(day, emptyList(), emptyList(), zone))
    }

    @Test fun hiddenRoutesRetainOnlyIndependentlyProvenParking() {
        val older = DriveData(1, startDate = "2026-11-10T08:00:00Z", endDate = "2026-11-10T08:20:00Z",
            source = "teslamate_archive", sourceInstanceId = "instance-A", qualityState = "observed",
            lengthUnit = "km", odometerDetails = DriveOdometerDetails(distance = 1.0))
        val newer = older.copy(driveId = 2, startDate = "2026-11-10T10:00:00Z", endDate = "2026-11-10T10:30:00Z")
        val history = listOf(newer, older)
        val parked = selectWeeklyDriveHistoryItems(history, emptyList(), verifiedParkedPairs(history))
        assertEquals(listOf("parked-1-2"), parked.map { it.key })
        assertEquals(100L, (parked.single() as DriveHistoryItem.Parked).durationMin)

        val middle = older.copy(driveId = 3, startDate = "2026-11-10T09:00:00Z", endDate = "2026-11-10T09:01:00Z",
            odometerDetails = DriveOdometerDetails(distance = 0.1))
        val interrupted = listOf(newer, middle, older)
        assertTrue(selectWeeklyDriveHistoryItems(interrupted, emptyList(), verifiedParkedPairs(interrupted)).isEmpty())
    }
}

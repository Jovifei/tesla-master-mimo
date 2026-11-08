package com.matelink.ui.screens.timeline

import com.matelink.data.api.models.DrivesResponse
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.receivedDrives
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class TimelineTimezoneConsumerTest {
    @Test fun utcDriveAcrossShanghaiMidnightUsesLocalDateWithoutChangingReceipt() {
        val originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            val drive = Moshi.Builder().build().adapter(DrivesResponse::class.java).fromJson(
                """{"data":{"units":{"unit_of_length":"km"},"drives":[{
                    "drive_id":71,"start_date":"2026-10-20T16:30:00Z",
                    "end_date":"2026-10-20T17:00:00Z","quality_state":"observed"
                }]}}"""
            )!!.data!!.receivedDrives().single()

            val event = drive.toTimelineDriveEvent()!!

            assertEquals("2026-10-21", event.date)
            assertEquals("00:30", event.time)
            assertEquals("2026-10-20T16:30:00Z", event.timestamp)
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test fun equalInstantsWithDifferentOffsetsShareLocalDay() {
        val originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            val utc = DriveData(driveId = 1, startDate = "2026-10-20T16:30:00Z")
            val offset = DriveData(driveId = 2, startDate = "2026-10-21T00:30:00+08:00")
            assertEquals(utc.toTimelineDriveEvent()!!.date, offset.toTimelineDriveEvent()!!.date)
            assertEquals(utc.toTimelineDriveEvent()!!.time, offset.toTimelineDriveEvent()!!.time)
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test fun malformedInstantDoesNotBecomeAnApparentlyValidCalendarDay() {
        assertNull(DriveData(driveId = 3, startDate = "2026-10-20Tbad").toTimelineDriveEvent())
    }

    @Test fun mixedHistorySortsByInstantAndGroupsByLocalDay() {
        val originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            val events = mergeTimelineEvents(
                drives = listOf(
                    DriveData(driveId = 1, startDate = "2026-10-20T15:00:00Z"),
                    DriveData(driveId = 2, startDate = "2026-10-20T16:30:00Z"),
                    DriveData(driveId = 3, startDate = "2026-10-20Tbad")
                ),
                charges = listOf(
                    ChargeData(chargeId = 4, startDate = "2026-10-21T00:00:00+08:00"),
                    ChargeData(chargeId = 5, startDate = "not-a-time")
                )
            )

            assertEquals(listOf("drive_2", "charge_4", "drive_1"), events.map {
                when (it) {
                    is TimelineEvent.DriveEvent -> "drive_${it.driveId}"
                    is TimelineEvent.ChargeEvent -> "charge_${it.chargeId}"
                }
            })
            assertEquals(listOf("2026-10-21", "2026-10-20"), events.groupBy { it.date }.keys.toList())
            assertEquals(listOf("00:30", "00:00", "23:00"), events.map { it.time })
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test fun equalInstantsHaveDeterministicDriveThenChargeOrder() {
        val events = mergeTimelineEvents(
            drives = listOf(DriveData(driveId = 8, startDate = "2026-10-20T14:30:00Z")),
            charges = listOf(ChargeData(chargeId = 1, startDate = "2026-10-20T22:30:00+08:00"))
        )
        assertEquals(TimelineEvent.DriveEvent::class, events[0]::class)
        assertEquals(TimelineEvent.ChargeEvent::class, events[1]::class)
    }
}

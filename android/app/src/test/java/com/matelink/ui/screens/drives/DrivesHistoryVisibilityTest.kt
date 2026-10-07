package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import org.junit.Assert.assertEquals
import org.junit.Test

class DrivesHistoryVisibilityTest {
    @Test
    fun unknownDistanceAlreadyVisibleFromViewModelRemainsVisibleInHistoryCards() {
        val drive = DriveData(
            driveId = 101,
            qualityState = "unknown",
            odometerDetails = null
        )

        val visibleIds = buildDriveHistoryItems(listOf(drive))
            .filterIsInstance<DriveHistoryItem.Drive>()
            .map { it.drive.id }

        assertEquals(listOf(101), visibleIds)
    }

    @Test
    fun observedThreeHundredMeterDriveAlreadyAllowedByViewModelRemainsVisibleInHistoryCards() {
        val drive = DriveData(
            driveId = 102,
            durationMin = 1,
            qualityState = "observed",
            odometerDetails = DriveOdometerDetails(distance = 0.3)
        )

        val visibleIds = buildDriveHistoryItems(listOf(drive))
            .filterIsInstance<DriveHistoryItem.Drive>()
            .map { it.drive.id }

        assertEquals(listOf(102), visibleIds)
    }

    @Test
    fun unknownAndShortVisibleCardsDoNotBecomeParkingInputs() {
        val newer = DriveData(
            driveId = 201,
            startDate = "2026-10-06T10:00:00+08:00",
            endDate = "2026-10-06T10:02:00+08:00",
            durationMin = 2,
            qualityState = "observed",
            odometerDetails = DriveOdometerDetails(distance = 0.3)
        )
        val older = DriveData(
            driveId = 202,
            startDate = "2026-10-06T08:00:00+08:00",
            endDate = "2026-10-06T08:10:00+08:00",
            qualityState = "unknown",
            odometerDetails = null
        )

        val items = buildDriveHistoryItems(listOf(newer, older))

        assertEquals(
            listOf(201, 202),
            items.filterIsInstance<DriveHistoryItem.Drive>().map { it.drive.id }
        )
        assertEquals(0, items.filterIsInstance<DriveHistoryItem.Parked>().size)
    }

    @Test
    fun twoParkingEligibleDrivesKeepExistingParkedSegment() {
        val newer = DriveData(
            driveId = 301,
            startDate = "2026-10-06T10:00:00+08:00",
            endDate = "2026-10-06T10:02:00+08:00",
            durationMin = 2,
            qualityState = "observed",
            odometerDetails = DriveOdometerDetails(distance = 0.6)
        )
        val older = DriveData(
            driveId = 302,
            startDate = "2026-10-06T08:00:00+08:00",
            endDate = "2026-10-06T08:10:00+08:00",
            durationMin = 10,
            qualityState = "observed",
            odometerDetails = DriveOdometerDetails(distance = 0.7)
        )

        val items = buildDriveHistoryItems(listOf(newer, older))
        val parked = items.filterIsInstance<DriveHistoryItem.Parked>().single()

        assertEquals(listOf("drive-301", "parked-302-301", "drive-302"), items.map { it.key })
        assertEquals(302, parked.olderDrive.id)
        assertEquals(301, parked.newerDrive.id)
        assertEquals(110L, parked.durationMin)
    }
}

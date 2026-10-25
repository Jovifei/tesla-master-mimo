package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveOdometerDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivesHistoryVisibilityTest {
    @Test
    fun shortDrivePreferenceChangesOnlyTheVisibleRouteSet() {
        val short = DriveData(
            driveId = 103,
            qualityState = "observed",
            durationMin = 0,
            odometerDetails = DriveOdometerDetails(distance = 0.1)
        )
        val unknown = short.copy(driveId = 104, qualityState = "unknown")

        assertFalse(DrivesViewModel.isVisibleHistoryDrive(short, showShort = false))
        assertTrue(DrivesViewModel.isVisibleHistoryDrive(short, showShort = true))
        assertTrue(DrivesViewModel.isVisibleHistoryDrive(unknown, showShort = false))
    }

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
            source = "teslamate_archive",
            sourceInstanceId = "instance-A",
            odometerDetails = DriveOdometerDetails(distance = 0.6)
        )
        val older = DriveData(
            driveId = 302,
            startDate = "2026-10-06T08:00:00+08:00",
            endDate = "2026-10-06T08:10:00+08:00",
            durationMin = 10,
            qualityState = "observed",
            source = "teslamate_archive",
            sourceInstanceId = "instance-A",
            odometerDetails = DriveOdometerDetails(distance = 0.7)
        )

        val items = buildDriveHistoryItems(listOf(newer, older))
        val parked = items.filterIsInstance<DriveHistoryItem.Parked>().single()

        assertEquals(listOf("drive-301", "parked-302-301", "drive-302"), items.map { it.key })
        assertEquals(302, parked.olderDrive.id)
        assertEquals(301, parked.newerDrive.id)
        assertEquals(110L, parked.durationMin)
    }

    @Test fun differentOrUnverifiedCollectorsCannotCreateAParkedWindow() {
        val older = DriveData(driveId = 401,
            startDate = "2026-10-06T08:00:00+08:00", endDate = "2026-10-06T08:10:00+08:00",
            source = "teslamate_archive", sourceInstanceId = "instance-A",
            qualityState = "observed", odometerDetails = DriveOdometerDetails(distance = 1.0))
        val newer = older.copy(driveId = 402,
            startDate = "2026-10-06T10:00:00+08:00", endDate = "2026-10-06T10:10:00+08:00")
        fun parkedCount(next: DriveData) = buildDriveHistoryItems(listOf(next, older))
            .filterIsInstance<DriveHistoryItem.Parked>().size
        assertEquals(1, parkedCount(newer))
        assertEquals(0, parkedCount(newer.copy(sourceInstanceId = "instance-B")))
        assertEquals(0, parkedCount(newer.copy(source = "fleet_telemetry")))
        assertEquals(0, parkedCount(newer.copy(sourceInstanceId = null)))
        assertEquals(0, parkedCount(newer.copy(source = null)))
        assertEquals(0, parkedCount(newer.copy(sourceId = "record-A", sourceRecordId = "record-B")))
    }

    @Test fun hiddenShortDriveAndDistanceFilterCannotBridgeANonAdjacentParkingGap() {
        val older = DriveData(driveId = 501, startDate = "2026-10-06T08:00:00Z",
            endDate = "2026-10-06T08:10:00Z", durationMin = 10,
            qualityState = "observed", source = "teslamate_archive", sourceInstanceId = "instance-A",
            odometerDetails = DriveOdometerDetails(distance = 2.0))
        val newer = older.copy(driveId = 503, startDate = "2026-10-06T10:00:00Z",
            endDate = "2026-10-06T10:10:00Z")
        val middle = older.copy(driveId = 502, startDate = "2026-10-06T09:00:00Z",
            endDate = "2026-10-06T09:01:00Z", durationMin = 0,
            odometerDetails = DriveOdometerDetails(distance = 0.1))
        val visibleOnly = listOf(newer, older)
        assertEquals(1, buildDriveHistoryItems(visibleOnly).filterIsInstance<DriveHistoryItem.Parked>().size)
        assertFalse(DrivesViewModel.isVisibleHistoryDrive(middle, showShort = false))
        val fullProof = verifiedParkedPairs(listOf(newer, middle, older))
        assertTrue(fullProof.isEmpty())
        assertEquals(0, buildDriveHistoryItems(visibleOnly, fullProof)
            .filterIsInstance<DriveHistoryItem.Parked>().size)
        assertEquals(0, buildDriveHistoryItems(listOf(newer, middle, older))
            .filterIsInstance<DriveHistoryItem.Parked>().size)
        assertTrue(verifiedParkedPairs(listOf(newer, middle.copy(qualityState = "quarantined"), older)).isEmpty())
        assertTrue(verifiedParkedPairs(listOf(newer, middle.copy(sourceInstanceId = "instance-B"), older)).isEmpty())
        assertEquals(setOf(501 to 503), verifiedParkedPairs(visibleOnly))
    }
}

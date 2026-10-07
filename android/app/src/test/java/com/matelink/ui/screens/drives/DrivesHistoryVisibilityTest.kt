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
}

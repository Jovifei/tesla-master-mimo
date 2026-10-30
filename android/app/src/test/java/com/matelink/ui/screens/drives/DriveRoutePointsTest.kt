package com.matelink.ui.screens.drives

import com.matelink.data.api.models.DrivePosition
import org.junit.Assert.assertEquals
import org.junit.Test

class DriveRoutePointsTest {
    @Test fun duplicateMissingAndInvalidCoordinatesCannotImplyAnAtoBRoute() {
        val points = usableDriveRoutePoints(listOf(
            DrivePosition(latitude = 30.30, longitude = 120.10),
            DrivePosition(latitude = 30.30, longitude = 120.10),
            DrivePosition(latitude = 0.0, longitude = 0.0),
            DrivePosition(latitude = null, longitude = 120.11),
            DrivePosition(latitude = 999.0, longitude = 120.11)
        ))
        assertEquals(listOf(30.30 to 120.10), points)
    }

    @Test fun twoDistinctValidPointsRemainAvailableForTheRealRoute() {
        val points = usableDriveRoutePoints(listOf(
            DrivePosition(latitude = 30.30, longitude = 120.10),
            DrivePosition(latitude = 30.31, longitude = 120.12)
        ))
        assertEquals(listOf(30.30 to 120.10, 30.31 to 120.12), points)
    }
}

package com.matelink.ui.navigation

import com.matelink.notification.completedTripNotificationDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripNotificationDeepLinkTest {
    @Test
    fun completedTripNotificationTargetsTheExistingDriveDetailScreen() {
        val deepLink = completedTripNotificationDeepLink(carId = 7, driveId = 12)
        assertEquals(
            Screen.DriveDetail(carId = 7, driveId = 12),
            notificationScreen(
                deepLink.navigateTo,
                carId = deepLink.carId,
                exteriorColor = null,
                driveId = deepLink.driveId
            )
        )
    }

    @Test
    fun driveDetailNotificationWithoutAnIdDoesNotNavigate() {
        assertNull(notificationScreen("drive_detail", carId = 7, exteriorColor = null, driveId = -1))
    }
}

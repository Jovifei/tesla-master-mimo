package com.matelink.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.matelink.ui.navigation.Screen
import com.matelink.ui.navigation.notificationScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripNotificationDeepLinkAndroidTest {
    @Test
    fun completedTripIntentCarriesTheDriveDetailRouteExtras() {
        val deepLink = completedTripNotificationDeepLink(carId = 7, driveId = 12)
        val intent = completedTripIntent(
            ApplicationProvider.getApplicationContext<Context>(),
            deepLink
        )

        assertEquals(7, intent.getIntExtra("EXTRA_CAR_ID", -1))
        assertEquals(12, intent.getIntExtra("EXTRA_DRIVE_ID", -1))
        assertEquals("drive_detail", intent.getStringExtra("EXTRA_NAVIGATE_TO"))
        assertEquals(
            Screen.DriveDetail(carId = 7, driveId = 12),
            notificationScreen(
                intent.getStringExtra("EXTRA_NAVIGATE_TO"),
                intent.getIntExtra("EXTRA_CAR_ID", -1),
                exteriorColor = null,
                driveId = intent.getIntExtra("EXTRA_DRIVE_ID", -1)
            )
        )
    }
}

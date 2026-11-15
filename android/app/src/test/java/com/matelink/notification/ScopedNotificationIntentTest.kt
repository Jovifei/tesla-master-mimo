package com.matelink.notification

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.ChargingDetails
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ScopedNotificationIntentTest {
    @Test fun sentryNotificationKeepsLocalIdentityAndNavigatesWithTheRemoteId() {
        val context = RuntimeEnvironment.getApplication()
        val local = -21
        SentryNotificationManager(context).showSentryAlert("Synthetic", local, 1, remoteApiCarId = 7)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = manager.activeNotifications.single {
            it.id == SentryNotificationManager.NOTIFICATION_ID_BASE + local
        }.notification
        val intent = shadowOf(requireNotNull(notification.contentIntent)).savedIntent
        assertEquals("sentry_history", intent.getStringExtra("EXTRA_NAVIGATE_TO"))
        assertEquals(7, intent.getIntExtra("EXTRA_CAR_ID", 0))
        assertEquals(local, intent.getIntExtra("EXTRA_LOCAL_HISTORY_CAR_ID", 0))
    }

    @Test fun chargingNotificationIncludesItsVerifiedLocalOwnerAlongsideTheRemoteTarget() {
        val context = RuntimeEnvironment.getApplication()
        val notification = ChargingNotificationManager(context).buildNotification(CarData(7, name = "Synthetic"),
            CarStatus(chargingDetails = ChargingDetails(chargingState = "Charging")),
            liveChargeAvailable = true, localHistoryCarId = -21)
        val intent = shadowOf(requireNotNull(notification.contentIntent)).savedIntent
        assertEquals("current_charge", intent.getStringExtra("EXTRA_NAVIGATE_TO"))
        assertEquals(7, intent.getIntExtra("EXTRA_CAR_ID", 0))
        assertEquals(-21, intent.getIntExtra("EXTRA_LOCAL_HISTORY_CAR_ID", 0))
    }
}

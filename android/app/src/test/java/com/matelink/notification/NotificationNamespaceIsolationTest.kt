package com.matelink.notification

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.matelink.R
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.ChargingDetails
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NotificationNamespaceIsolationTest {
    @Test fun sentryAndForegroundPlaceholderCoexistEvenWithTheSameNumericId() {
        val context = RuntimeEnvironment.getApplication()
        val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sentry = SentryNotificationManager(context)
        sentry.showSentryAlert("Synthetic", -1, 1, remoteApiCarId = 7)
        val id = SentryNotificationManager.NOTIFICATION_ID_BASE - 1
        assertEquals(3999, id) // The existing monitor service placeholder uses this ID.
        ChargingNotificationManager(context).ensureChannelExists()
        system.notify(3999, NotificationCompat.Builder(context, ChargingNotificationManager.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Isolated loading").build())
        assertEquals(2, system.activeNotifications.count { it.id == id })
        sentry.cancelNotification(-1)
        val remaining = system.activeNotifications.single { it.id == id }
        assertEquals(ChargingNotificationManager.CHANNEL_ID, remaining.notification.channelId)
    }

    @Test fun theSameRemoteIdInTwoLocalScopesCannotReuseAChargingPendingIntent() {
        val context = RuntimeEnvironment.getApplication()
        val manager = ChargingNotificationManager(context)
        val status = CarStatus(chargingDetails = ChargingDetails(chargingState = "Charging"))
        val first = requireNotNull(manager.buildNotification(CarData(7), status,
            liveChargeAvailable = true, localHistoryCarId = -21).contentIntent)
        val second = requireNotNull(manager.buildNotification(CarData(7), status,
            liveChargeAvailable = true, localHistoryCarId = -22).contentIntent)
        assertNotEquals(first, second)
        val firstIntent = shadowOf(first).savedIntent
        val secondIntent = shadowOf(second).savedIntent
        assertEquals(-21, firstIntent.getIntExtra("EXTRA_LOCAL_HISTORY_CAR_ID", 0))
        assertEquals(-22, secondIntent.getIntExtra("EXTRA_LOCAL_HISTORY_CAR_ID", 0))
        assertNotNull(firstIntent.data)
        assertNotEquals(firstIntent.data, secondIntent.data)
    }

    @Test fun chargingAndSentryTargetsUseDifferentTypeAndLocalDataUris() {
        val context = RuntimeEnvironment.getApplication()
        val charging = requireNotNull(ChargingNotificationManager(context).buildNotification(CarData(7),
            CarStatus(chargingDetails = ChargingDetails(chargingState = "Charging")),
            liveChargeAvailable = true, localHistoryCarId = -21).contentIntent)
        SentryNotificationManager(context).showSentryAlert("Synthetic", -21, 1, remoteApiCarId = 7)
        val system = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sentry = requireNotNull(system.activeNotifications.single {
            it.id == SentryNotificationManager.NOTIFICATION_ID_BASE - 21
        }.notification.contentIntent)
        val chargingIntent = shadowOf(charging).savedIntent
        val sentryIntent = shadowOf(sentry).savedIntent
        assertNotNull(chargingIntent.data)
        assertNotNull(sentryIntent.data)
        assertNotEquals(chargingIntent.data, sentryIntent.data)
        assertEquals("-21", chargingIntent.data?.lastPathSegment)
        assertEquals("-21", sentryIntent.data?.lastPathSegment)
    }
}

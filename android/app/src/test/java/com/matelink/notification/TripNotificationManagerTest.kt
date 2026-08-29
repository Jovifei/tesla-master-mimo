package com.matelink.notification

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripNotificationManagerTest {
    @Test
    fun completedTripBodyContainsRouteDistanceAndDuration() {
        val body = formatCompletedTripNotificationBody(
            template = "%1\u0024s → %2\u0024s · %3\u0024.1f km · %4\u0024d min",
            start = "Start",
            end = "End",
            distanceKm = 20.5,
            durationMin = 30
        )

        assertEquals("Start → End · 20.5 km · 30 min", body)
    }

    @Test
    fun blankAddressesUseTheLocalizedUnknownFallback() {
        assertEquals("Unknown", tripNotificationLocation("  ", "Unknown"))
        assertEquals("Home", tripNotificationLocation(" Home ", "Unknown"))
    }

    @Test
    fun notificationSpecUsesDedicatedChannelHighPriorityAndIsolatedId() {
        val spec = buildTripNotificationSpec(
            carId = 42,
            driveId = 99,
            title = "Trip completed",
            body = "Start → End · 20.5 km · 30 min"
        )

        assertEquals("trip_updates_channel", spec.channelId)
        assertEquals(NotificationCompat.PRIORITY_HIGH, spec.priority)
        assertEquals("Trip completed", spec.title)
        assertTrue(spec.notificationId >= 0x40000000)
        assertTrue(spec.notificationId !in setOf(2042, 3042, 4042))
    }
}

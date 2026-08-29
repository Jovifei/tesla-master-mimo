package com.matelink.notification

import com.matelink.data.local.TirePosition
import com.matelink.data.local.TpmsCustomPressureState
import com.matelink.domain.analytics.TpmsCustomAlert
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TpmsTrendNotificationManagerTest {
    @Test
    fun formatsLowAndHighAlertsWithWheelObservedPressureAndThreshold() {
        val low = TpmsCustomAlert(TirePosition.FL, TpmsCustomPressureState.LOW, 2.5, 2.6)
        val high = TpmsCustomAlert(TirePosition.RR, TpmsCustomPressureState.HIGH, 3.5, 3.4)

        val lowText = formatTpmsCustomAlert(
            template = "App custom reminder: %1\u0024s observed pressure %2\u0024.2f bar is below threshold %3\u0024.2f bar.",
            wheelName = "Front Left",
            alert = low
        )
        val highText = formatTpmsCustomAlert(
            template = "App custom reminder: %1\u0024s observed pressure %2\u0024.2f bar is above threshold %3\u0024.2f bar.",
            wheelName = "Rear Right",
            alert = high
        )

        assertEquals("App custom reminder: Front Left observed pressure 2.50 bar is below threshold 2.60 bar.", lowText)
        assertEquals("App custom reminder: Rear Right observed pressure 3.50 bar is above threshold 3.40 bar.", highText)
    }

    @Test
    fun notificationSpecUsesTpmsChannelDefaultPriorityAndStableContent() {
        val alert = TpmsCustomAlert(TirePosition.FL, TpmsCustomPressureState.LOW, 2.5, 2.6)
        val spec = buildTpmsCustomNotificationSpec(
            carId = 42,
            wheel = TirePosition.FL,
            title = "App custom reminder",
            body = "App custom reminder: Front Left observed 2.50 bar, threshold 2.60 bar"
        )

        assertEquals("tire_pressure_channel", spec.channelId)
        assertEquals(NotificationCompat.PRIORITY_DEFAULT, spec.priority)
        assertEquals("App custom reminder", spec.title)
        assertEquals("App custom reminder: Front Left observed 2.50 bar, threshold 2.60 bar", spec.body)
        assertEquals(customTpmsNotificationId(42, alert.wheel), spec.notificationId)
    }

    @Test
    fun customIdsAreDisjointFromEveryLegacyNotificationSpaceAtIntBoundaries() {
        val carIds = buildSet {
            addAll((0..2_048).map { offset -> Int.MIN_VALUE + offset })
            addAll(listOf(-1, 0, 1, 42, 999, Int.MAX_VALUE))
        }

        carIds.forEach { carId ->
            val legacyIds = setOf(
                (carId.toLong() + 2_000L).toInt(),
                (carId.toLong() + 3_000L).toInt(),
                (carId.toLong() + 4_000L).toInt()
            )
            TirePosition.values().forEach { wheel ->
                val customId = customTpmsNotificationId(carId, wheel)
                assertTrue(customId < 0)
                assertTrue("custom ID $customId collided for car $carId/$wheel", customId !in legacyIds)
            }
        }
    }
}

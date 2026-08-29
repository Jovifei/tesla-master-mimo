package com.matelink.notification

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.data.local.TirePosition
import com.matelink.data.local.TpmsCustomPressureState
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.analytics.TpmsCustomAlert
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalNotificationAndroidTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun customTpmsThresholdPostsANotification() {
        grantNotificationPermission()
        val notificationId = customTpmsNotificationId(carId = 7, wheel = TirePosition.FL)
        val manager = context.getSystemService(NotificationManager::class.java)

        try {
            TpmsTrendNotificationManager(context).showCustomAlert(
                carId = 7,
                alert = TpmsCustomAlert(
                    wheel = TirePosition.FL,
                    state = TpmsCustomPressureState.LOW,
                    observedPressureBar = 2.5,
                    thresholdBar = 2.6
                )
            )

            assertTrue("TPMS notification was not posted", awaitNotification(notificationId))
        } finally {
            manager.cancel(notificationId)
        }
    }

    @Test
    fun completedTripPostsANotification() {
        grantNotificationPermission()
        val summary = driveSummary(driveId = 99)
        val notificationId = tripNotificationId(carId = summary.carId, driveId = summary.driveId)
        val manager = context.getSystemService(NotificationManager::class.java)

        try {
            assertTrue(TripNotificationManager(context).showCompletedDrive(summary.carId, summary))
            assertTrue("Trip notification was not posted", awaitNotification(notificationId))
        } finally {
            manager.cancel(notificationId)
        }
    }

    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
                .close()
        }
        assertTrue(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    private fun awaitNotification(notificationId: Int): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        repeat(30) {
            if (manager.activeNotifications.any {
                    it.id == notificationId && it.packageName == context.packageName
                }
            ) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun driveSummary(driveId: Int) = DriveSummary(
        driveId = driveId,
        carId = 7,
        startDate = "2026-08-29T08:00:00Z",
        endDate = "2026-08-29T08:30:00Z",
        durationMin = 30,
        startAddress = "Start",
        endAddress = "End",
        distance = 20.5,
        speedMax = 90,
        speedAvg = 41,
        powerMax = 100,
        powerMin = -20,
        startBatteryLevel = 80,
        endBatteryLevel = 75,
        outsideTempAvg = null,
        insideTempAvg = null,
        energyConsumed = null,
        efficiency = null
    )
}

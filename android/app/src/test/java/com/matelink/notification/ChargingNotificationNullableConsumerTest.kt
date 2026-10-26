package com.matelink.notification

import android.app.Application
import android.app.Notification
import com.matelink.R
import com.matelink.data.api.models.BatteryDetails
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.ChargingDetails
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ChargingNotificationNullableConsumerTest {
    @Test fun partialChargingObservationDoesNotInventSocTargetPowerOrAc() {
        val context = RuntimeEnvironment.getApplication()
        val notification = ChargingNotificationManager(context).buildNotification(CarData(1, name = "Synthetic"),
            CarStatus(chargingDetails = ChargingDetails(chargingState = "Charging")))
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertFalse("title=$title", title.contains("0 kW"))
        assertFalse("title=$title", title.contains(" AC"))
        assertTrue(title.contains(context.getString(R.string.status_unknown)))
        assertFalse("text=$text", text.contains("0%"))
        assertFalse("text=$text", text.contains("80%"))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }

    @Test fun explicitZeroAndExplicitAcRemainObservedValues() {
        val context = RuntimeEnvironment.getApplication()
        val notification = ChargingNotificationManager(context).buildNotification(CarData(1, name = "Synthetic"),
            CarStatus(batteryDetails = BatteryDetails(batteryLevel = 0), chargingDetails = ChargingDetails(
                chargingState = "Charging", chargeLimitSoc = 0, chargerPower = 0.0, chargerPhases = 1)))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("0%"))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("0 kW AC"))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
    }
}

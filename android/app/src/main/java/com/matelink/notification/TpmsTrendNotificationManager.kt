package com.matelink.notification

import android.Manifest
import android.app.NotificationManager
import android.app.NotificationChannel
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.matelink.R
import com.matelink.data.local.TirePosition
import com.matelink.data.local.TpmsCustomPressureState
import com.matelink.data.sync.TpmsPressureWorker
import com.matelink.domain.analytics.TpmsCustomAlert
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

internal fun formatTpmsCustomAlert(
    template: String,
    wheelName: String,
    alert: TpmsCustomAlert
): String = String.format(
    Locale.ROOT,
    template,
    wheelName,
    alert.observedPressureBar,
    alert.thresholdBar
)

/**
 * Notification ID contract:
 *
 * Tesla, charging, and sentry keep their legacy `base + carId` IDs. Those
 * additions are Int-valued and can wrap into the negative range for an
 * extreme car ID, so the high-bit TPMS namespace must still probe against all
 * three legacy IDs for the same car. Only the low 30 bits are payload; the
 * sign bit and the clear bit 30 reserve this range for TPMS custom alerts.
 */
private const val CUSTOM_NOTIFICATION_NAMESPACE = Int.MIN_VALUE
private const val CUSTOM_NOTIFICATION_PAYLOAD_MASK = 0x3FFF_FFFF
private const val CUSTOM_NOTIFICATION_WHEEL_COUNT = 4
private val LEGACY_NOTIFICATION_OFFSETS = longArrayOf(2_000L, 3_000L, 4_000L)

internal fun customTpmsNotificationId(carId: Int, wheel: TirePosition): Int {
    val wheelPayload = wheel.ordinal
    var payload = (((carId.toLong() and CUSTOM_NOTIFICATION_PAYLOAD_MASK.toLong()) *
        CUSTOM_NOTIFICATION_WHEEL_COUNT) +
        wheelPayload) and CUSTOM_NOTIFICATION_PAYLOAD_MASK.toLong()
    val legacyIds = LEGACY_NOTIFICATION_OFFSETS.map { offset ->
        (carId.toLong() + offset).toInt()
    }

    // There are only three legacy spaces to avoid. Probe one extra slot so
    // every returned ID is contractually outside all of them.
    repeat(LEGACY_NOTIFICATION_OFFSETS.size + 1) {
        val candidate = CUSTOM_NOTIFICATION_NAMESPACE or payload.toInt()
        if (candidate !in legacyIds) {
            return candidate
        }
        payload = (payload + CUSTOM_NOTIFICATION_WHEEL_COUNT) and
            CUSTOM_NOTIFICATION_PAYLOAD_MASK.toLong()
    }

    error("Unable to allocate TPMS notification namespace")
}

internal data class TpmsCustomNotificationSpec(
    val notificationId: Int,
    val channelId: String,
    val title: String,
    val body: String,
    val priority: Int
)

internal fun buildTpmsCustomNotificationSpec(
    carId: Int,
    wheel: TirePosition,
    title: String,
    body: String
): TpmsCustomNotificationSpec = TpmsCustomNotificationSpec(
    notificationId = customTpmsNotificationId(carId, wheel),
    channelId = TpmsPressureWorker.CHANNEL_ID,
    title = title,
    body = body,
    priority = NotificationCompat.PRIORITY_DEFAULT
)

internal fun ensureTpmsNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        TpmsPressureWorker.CHANNEL_ID,
        context.getString(R.string.tpms_channel_name),
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = context.getString(R.string.tpms_channel_description)
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

internal class NotificationDeliveryUnavailableException(
    cause: Throwable? = null
) : RuntimeException(cause)

@Singleton
class TpmsTrendNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun showCustomAlert(carId: Int, alert: TpmsCustomAlert) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) throw NotificationDeliveryUnavailableException()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            throw NotificationDeliveryUnavailableException()
        }
        ensureTpmsNotificationChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        if (notificationManager.getNotificationChannel(TpmsPressureWorker.CHANNEL_ID)?.importance ==
            NotificationManager.IMPORTANCE_NONE
        ) throw NotificationDeliveryUnavailableException()
        val wheelName = context.getString(wheelString(alert.wheel))
        val bodyResource = when (alert.state) {
            TpmsCustomPressureState.LOW -> R.string.tpms_custom_low_body
            TpmsCustomPressureState.HIGH -> R.string.tpms_custom_high_body
        }
        val body = context.getString(
            bodyResource,
            wheelName,
            alert.observedPressureBar,
            alert.thresholdBar
        )

        val spec = buildTpmsCustomNotificationSpec(
            carId = carId,
            wheel = alert.wheel,
            title = context.getString(R.string.tpms_custom_notification_title),
            body = body
        )
        val notification = NotificationCompat.Builder(context, spec.channelId)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(spec.priority)
            .setAutoCancel(true)
            .build()

        runCatching { notificationManager.notify(spec.notificationId, notification) }
            .getOrElse { throw NotificationDeliveryUnavailableException(it) }
    }

    private fun wheelString(wheel: TirePosition): Int = when (wheel) {
        TirePosition.FL -> R.string.tire_fl_full
        TirePosition.FR -> R.string.tire_fr_full
        TirePosition.RL -> R.string.tire_rl_full
        TirePosition.RR -> R.string.tire_rr_full
    }
}

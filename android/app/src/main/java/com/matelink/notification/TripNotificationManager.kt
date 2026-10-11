package com.matelink.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.matelink.MainActivity
import com.matelink.R
import com.matelink.data.local.CompletedTripNotifier
import com.matelink.data.local.entity.DriveSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TRIP_NOTIFICATION_NAMESPACE = 0x40000000
private const val TRIP_NOTIFICATION_PAYLOAD_MASK = 0x3FFFFFFF

internal data class TripNotificationSpec(
    val notificationId: Int, val channelId: String, val title: String, val body: String, val priority: Int
)
internal data class TripNotificationDeepLink(val carId: Int, val driveId: Int, val navigateTo: String)
internal fun completedTripNotificationDeepLink(carId: Int, driveId: Int) = TripNotificationDeepLink(carId, driveId, "drive_detail")

/** Legacy helper retained for compatibility tests; new system notifications open the scoped inbox. */
internal fun completedTripIntent(context: Context, deepLink: TripNotificationDeepLink): Intent =
    Intent(context, MainActivity::class.java).apply {
        putExtra("EXTRA_CAR_ID", deepLink.carId)
        putExtra("EXTRA_DRIVE_ID", deepLink.driveId)
        putExtra("EXTRA_NAVIGATE_TO", deepLink.navigateTo)
    }

/** A notification left in Android across account/origin switches cannot choose a numeric detail ID. */
internal fun completedTripInboxIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

internal fun tripNotificationId(carId: Int, driveId: Int): Int {
    val payload = ((carId.toLong() * 1_000_003L) + driveId.toLong()) and TRIP_NOTIFICATION_PAYLOAD_MASK.toLong()
    return TRIP_NOTIFICATION_NAMESPACE or payload.toInt()
}
internal fun tripNotificationLocation(address: String, unknownLocation: String): String =
    address.trim().takeIf { it.isNotEmpty() } ?: unknownLocation
internal fun formatCompletedTripNotificationBody(
    template: String, start: String, end: String, distanceKm: Double, durationMin: Int
): String = String.format(Locale.ROOT, template, start, end, distanceKm, durationMin)
internal fun buildTripNotificationSpec(carId: Int, driveId: Int, title: String, body: String): TripNotificationSpec =
    TripNotificationSpec(tripNotificationId(carId, driveId), TripNotificationManager.CHANNEL_ID,
        title, body, NotificationCompat.PRIORITY_HIGH)

@Singleton
class TripNotificationManager @Inject constructor(@ApplicationContext private val context: Context) : CompletedTripNotifier {
    companion object { const val CHANNEL_ID = "trip_updates_channel" }

    /** Returns false when permission/channel settings prevent delivery. */
    override fun showCompletedDrive(carId: Int, summary: DriveSummary): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val spec = buildTripNotificationSpec(carId, summary.driveId,
            context.getString(R.string.trip_notification_title), context.getString(R.string.completed_drive_body))
        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            createChannel(manager)
            if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
            NotificationManagerCompat.from(context).notify(spec.notificationId,
                NotificationCompat.Builder(context, spec.channelId)
                    .setSmallIcon(R.drawable.ic_steering_wheel).setColor(0xFF087E96.toInt())
                    .setContentTitle(spec.title).setContentText(spec.body)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true)
                    .setPriority(spec.priority).setCategory(NotificationCompat.CATEGORY_STATUS).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(context, spec.notificationId,
                        completedTripInboxIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    .build())
            true
        }.getOrDefault(false)
    }
    private fun createChannel(manager: NotificationManager) {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.trip_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.trip_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }
}

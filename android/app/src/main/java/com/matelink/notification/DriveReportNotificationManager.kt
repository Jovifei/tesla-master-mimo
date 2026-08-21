package com.matelink.notification

import android.Manifest
import android.annotation.SuppressLint
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
import com.matelink.data.report.DetectedDriveReport
import com.matelink.ui.screens.drivereport.DriveReportActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveReportNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "drive_report_channel"
        private const val SUMMARY_NOTIFICATION_ID = 4100
        private const val SINGLE_NOTIFICATION_ID_BASE = 4200
    }

    fun canNotify(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        return permissionGranted &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission")
    fun showSingle(report: DetectedDriveReport) {
        ensureChannel()
        val distanceKm = report.distanceKm
        val durationMinutes = report.durationMinutes
        val content = if (
            distanceKm != null && distanceKm.isFinite() && distanceKm > 0.0 &&
            durationMinutes != null && durationMinutes > 0
        ) {
            context.getString(
                R.string.drive_report_notification_content,
                distanceKm,
                durationMinutes
            )
        } else {
            context.getString(R.string.drive_report_notification_content_generic)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.drive_report_notification_title))
            .setContentText(content)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(singlePendingIntent(report))
            .build()

        NotificationManagerCompat.from(context).notify(notificationId(report), notification)
    }

    @SuppressLint("MissingPermission")
    fun showSummary(count: Int) {
        require(count > 1)
        ensureChannel()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            SUMMARY_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.drive_report_notification_summary_title, count))
            .setContentText(context.getString(R.string.drive_report_notification_summary_content))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, notification)
    }

    private fun singlePendingIntent(report: DetectedDriveReport): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId(report),
            DriveReportActivity.createIntent(context, report.carId, report.driveId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun notificationId(report: DetectedDriveReport): Int =
        SINGLE_NOTIFICATION_ID_BASE + ("${report.carId}:${report.driveId}".hashCode() and 0x3fffffff)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.drive_report_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.drive_report_channel_description)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}

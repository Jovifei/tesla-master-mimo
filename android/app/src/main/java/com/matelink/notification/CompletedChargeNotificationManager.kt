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
import com.matelink.data.local.CompletedChargeEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompletedChargeNotificationManager @Inject constructor(@ApplicationContext private val context: Context) {
    fun show(event: CompletedChargeEvent): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "completed_charge_updates"
        manager.createNotificationChannel(NotificationChannel(channel, context.getString(R.string.completed_charge_title), NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val id = 0x50000000 or ((event.carId.toLong()*1000003 + event.chargeId) and 0x0fffffff).toInt()
        // Open the app; the scoped persistent event controls access to its detail.
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        return runCatching {
            NotificationManagerCompat.from(context).notify(id, NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.completed_charge_title))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentText(context.getString(R.string.completed_charge_body)).setOnlyAlertOnce(true).setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
            true
        }.getOrDefault(false)
    }
}

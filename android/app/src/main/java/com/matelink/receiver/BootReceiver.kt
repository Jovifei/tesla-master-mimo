package com.matelink.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.matelink.data.sync.ChargingNotificationWorker
import com.matelink.data.sync.DriveReportMonitorWorker
import com.matelink.data.sync.TpmsPressureWorker

/**
 * Reschedules monitoring work after device reboot.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device boot completed, rescheduling workers")
            TpmsPressureWorker.schedulePeriodicWork(context)
            ChargingNotificationWorker.schedulePeriodicWork(context)
            DriveReportMonitorWorker.schedulePeriodic(context)
            DriveReportMonitorWorker.runNow(context)
        }
    }
}

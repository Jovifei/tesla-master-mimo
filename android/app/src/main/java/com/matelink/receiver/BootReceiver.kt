package com.matelink.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.matelink.data.sync.ChargingNotificationWorker
import com.matelink.data.sync.TpmsPressureWorker

/**
 * BroadcastReceiver that reschedules periodic workers after device reboot.
 * Ensures TPMS pressure monitoring and charging notifications continue after the device restarts.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device boot completed, rescheduling workers")

            // Reschedule TPMS pressure monitoring
            TpmsPressureWorker.schedulePeriodicWork(context)

            // Reschedule charging notification monitoring
            ChargingNotificationWorker.schedulePeriodicWork(context)
        }
    }
}

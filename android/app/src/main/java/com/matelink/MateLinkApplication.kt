package com.matelink

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.matelink.app.AppVisibilityTracker
import com.matelink.data.sync.DriveReportMonitorWorker
import com.matelink.locale.LocaleHelper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MateLinkApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var appVisibilityTracker: AppVisibilityTracker

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // The manifest removes WorkManagerInitializer so Hilt can provide the
        // worker factory. Preserve the existing explicit initialization.
        WorkManager.initialize(this, workManagerConfiguration)

        // AMap key verification runs in a private secondary process. Only the
        // main app process owns foreground state and drive-report scheduling.
        if (isMainProcess()) {
            registerActivityLifecycleCallbacks(appVisibilityTracker)
            DriveReportMonitorWorker.schedulePeriodic(this)
            DriveReportMonitorWorker.runNow(this)
            applyStoredLocale()
        }
    }

    private fun isMainProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }
                ?.processName
        }
        return processName == packageName
    }

    private fun applyStoredLocale() {
        applicationScope.launch {
            val languageCode = try {
                val prefs = getSharedPreferences("matelink_language", Context.MODE_PRIVATE)
                prefs.getString("language_code", "") ?: ""
            } catch (_: Exception) {
                ""
            }
            with(Dispatchers.Main) {
                LocaleHelper.applyLocale(this@MateLinkApplication, languageCode)
            }
        }
    }
}

package com.matelink

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.matelink.data.sync.TpmsPressureWorker
import com.matelink.locale.LocaleHelper
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MateLinkApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Apply the persisted app locale before the first Activity composes so
        // a second launch cannot flash the system language first.
        applyStoredLocale()
        // The manifest removes WorkManagerInitializer so Hilt can provide the
        // worker factory. Initialize exactly once before any sync is enqueued.
        WorkManager.initialize(this, workManagerConfiguration)
        // Boot is not guaranteed after an install or update. KEEP makes this
        // restart-safe without launching an immediate vehicle request.
        if (shouldScheduleTpmsWorker(packageName, currentProcessName())) {
            TpmsPressureWorker.schedulePeriodicWork(this)
        }
    }

    private fun currentProcessName(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Application.getProcessName()
    } else {
        (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
    }

    private fun applyStoredLocale() {
        val languageCode = runCatching {
            getSharedPreferences("matelink_language", Context.MODE_PRIVATE)
                .getString("language_code", "")
                .orEmpty()
        }.getOrDefault("")
        LocaleHelper.applyLocale(this, languageCode)
    }
}

internal fun shouldScheduleTpmsWorker(packageName: String, processName: String?): Boolean =
    processName == packageName

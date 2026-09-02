package com.matelink

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
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

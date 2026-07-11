package com.matelink

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
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

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // The manifest removes WorkManagerInitializer so Hilt can provide the
        // worker factory. Initialize exactly once before any sync is enqueued.
        WorkManager.initialize(this, workManagerConfiguration)
        applyStoredLocale()
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

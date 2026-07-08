package com.matelink

import android.app.Application
import android.content.Context
import com.matelink.locale.LocaleHelper
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class MateLinkApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
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

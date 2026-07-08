package com.matelink.locale

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Helper for applying per-app language preferences.
 *
 * Language codes:
 *   ""  = System default
 *   "en" = English
 *   "zh" = 中文
 *   "ja" = 日本語
 *   "de" = Deutsch
 *   "fr" = Français
 */
object LocaleHelper {

    /** All supported locales (tag to display name). */
    val SUPPORTED_LOCALES: List<Pair<String, String>> = listOf(
        "" to "System Default",
        "en" to "English",
        "zh" to "中文",
        "ja" to "日本語",
        "de" to "Deutsch",
        "fr" to "Français"
    )

    /**
     * Apply the given [languageCode] to the app's resources.
     * Pass "" to revert to system default.
     * Returns true if the locale was actually changed.
     */
    fun applyLocale(context: Context, languageCode: String): Boolean {
        val locale = if (languageCode.isBlank()) {
            Locale.getDefault()
        } else {
            Locale(languageCode)
        }

        val currentLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }

        // Check if locale actually changed
        if (currentLocale.language == locale.language) {
            return false
        }

        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(android.os.LocaleList(locale))
        }

        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        return true
    }

    /**
     * Get the current app locale.
     */
    fun getCurrentLocale(context: Context): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
    }
}

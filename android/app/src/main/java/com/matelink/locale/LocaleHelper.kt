package com.matelink.locale

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Helper for applying per-app language preferences.
 *
 * Language codes:
 *   ""  = System default
 *   "en" = English
 *   "zh" = 中文
 */
object LocaleHelper {

    /** All supported locales (tag to display name). */
    val SUPPORTED_LOCALES: List<Pair<String, String>> = listOf(
        "" to "System Default",
        "en" to "English",
        "zh" to "中文"
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

        val currentLocale = context.resources.configuration.locales[0]

        // Check if locale actually changed
        if (currentLocale.language == locale.language) {
            return false
        }

        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLocales(android.os.LocaleList(locale))

        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        return true
    }

    /**
     * Get the current app locale.
     */
    fun getCurrentLocale(context: Context): Locale {
        return context.resources.configuration.locales[0]
    }
}

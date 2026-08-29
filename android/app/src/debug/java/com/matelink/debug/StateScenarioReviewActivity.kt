package com.matelink.debug

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.matelink.locale.LocaleHelper
import java.util.Locale

class StateScenarioReviewActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val languageCode = newBase
            .getSharedPreferences("matelink_language", MODE_PRIVATE)
            .getString("language_code", "")
            ?.trim()
            .orEmpty()
        if (languageCode.isBlank() || languageCode !in StateScenarioFixtures.supportedLanguageCodes()) {
            super.attachBaseContext(newBase)
            return
        }

        val config = Configuration(newBase.resources.configuration)
        config.setLocale(Locale(languageCode))
        config.setLocales(LocaleList(Locale(languageCode)))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StateScenarioReviewScreen { languageCode ->
                persistDebugLanguage(languageCode)
                if (LocaleHelper.applyLocale(this, languageCode)) {
                    recreate()
                }
            }
        }
    }

    private fun persistDebugLanguage(languageCode: String) {
        getSharedPreferences("matelink_language", MODE_PRIVATE)
            .edit()
            .putString("language_code", languageCode)
            .commit()
    }
}

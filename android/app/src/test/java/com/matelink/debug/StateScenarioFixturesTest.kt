package com.matelink.debug

import com.matelink.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class StateScenarioFixturesTest {
    @Test
    fun `exposes every approved device verification scenario`() {
        assumeTrue(BuildConfig.DEBUG)
        val scenarioIds = runCatching {
            val fixtures = Class.forName("com.matelink.debug.StateScenarioFixtures")
            @Suppress("UNCHECKED_CAST")
            fixtures.getMethod("scenarioIds").invoke(null) as Set<String>
        }.getOrDefault(emptySet())

        assertEquals(
            setOf("DRIVING_REGEN", "OPENING_TPMS", "AC_CHARGING", "DC_CHARGING", "MISSING_FIELDS"),
            scenarioIds
        )
    }

    @Test
    fun `exposes complete device matrix controls`() {
        assumeTrue(BuildConfig.DEBUG)
        val fixtures = Class.forName("com.matelink.debug.StateScenarioFixtures")

        fun invokeList(name: String): List<*> = runCatching {
            @Suppress("UNCHECKED_CAST")
            fixtures.getMethod(name).invoke(null) as List<*>
        }.getOrDefault(emptyList<Any>())

        assertEquals(listOf("zh", "en"), invokeList("supportedLanguageCodes"))
        assertEquals(listOf("light", "dark"), invokeList("supportedThemeModes"))
        assertEquals(listOf("1.0", "2.0"), invokeList("supportedFontScales"))
    }

    @Test
    fun `exposes structured selectors and stable debug tags`() {
        assumeTrue(BuildConfig.DEBUG)
        val fixtures = Class.forName("com.matelink.debug.StateScenarioFixtures")
        val observed = runCatching {
            @Suppress("UNCHECKED_CAST")
            val selectors = fixtures.getMethod("scenarioSelectors").invoke(null) as List<Any>
            val ids = selectors.map { it.javaClass.getMethod("getId").invoke(it) as String }
            val tags = Class.forName("com.matelink.debug.StateScenarioReviewTags")
            listOf(
                ids,
                tags.getMethod("language", String::class.java).invoke(null, "zh"),
                tags.getMethod("theme", String::class.java).invoke(null, "dark"),
                tags.getMethod("fontScale", Int::class.javaPrimitiveType).invoke(null, 200),
                tags.getMethod("scenario", String::class.java).invoke(null, "MISSING_FIELDS")
            )
        }.getOrDefault(emptyList<Any>())

        assertEquals(
            listOf(
                listOf("DRIVING_REGEN", "OPENING_TPMS", "AC_CHARGING", "DC_CHARGING", "MISSING_FIELDS"),
                "matrix_language_zh",
                "matrix_theme_dark",
                "matrix_font_scale_200",
                "scenario_MISSING_FIELDS"
            ),
            observed
        )
    }

    @Test
    fun `matrix transitions preserve the selected scenario`() {
        assumeTrue(BuildConfig.DEBUG)
        val observed = runCatching {
            val stateClass = Class.forName("com.matelink.debug.StateScenarioReviewState")
            val controllerClass = Class.forName("com.matelink.debug.StateScenarioReviewController")
            val state = stateClass.getConstructor(
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            ).newInstance("DRIVING_REGEN", "en", false, 1.0f)
            val selectScenario = controllerClass.getMethod(
                "selectScenario",
                stateClass,
                String::class.java
            )
            val selectLanguage = controllerClass.getMethod(
                "selectLanguage",
                stateClass,
                String::class.java
            )
            val selectTheme = controllerClass.getMethod(
                "selectTheme",
                stateClass,
                Boolean::class.javaPrimitiveType
            )
            val selectFontScale = controllerClass.getMethod(
                "selectFontScale",
                stateClass,
                Float::class.javaPrimitiveType
            )
            val selected = selectScenario.invoke(null, state, "OPENING_TPMS")
            val chinese = selectLanguage.invoke(null, selected, "zh")
            val dark = selectTheme.invoke(null, chinese, true)
            val large = selectFontScale.invoke(null, dark, 2.0f)
            listOf(
                stateClass.getMethod("getSelectedScenarioId").invoke(large),
                stateClass.getMethod("getLanguageCode").invoke(large),
                stateClass.getMethod("getDarkTheme").invoke(large),
                stateClass.getMethod("getFontScale").invoke(large)
            )
        }.getOrDefault(emptyList())

        assertEquals(listOf("OPENING_TPMS", "zh", true, 2.0f), observed)
    }

    @Test
    fun `debug activity persists language before applying locale`() {
        assumeTrue(BuildConfig.DEBUG)
        val source = File("src/debug/java/com/matelink/debug/StateScenarioReviewActivity.kt").readText()

        assertTrue(source.contains("getSharedPreferences(\"matelink_language\", MODE_PRIVATE)"))
        assertTrue(source.contains("putString(\"language_code\", languageCode)"))
        assertTrue(source.contains(".commit()"))
        assertTrue(
            source.indexOf("persistDebugLanguage(languageCode)") <
                source.indexOf("LocaleHelper.applyLocale")
        )
    }

    @Test
    fun `debug activity applies persisted locale in base context`() {
        assumeTrue(BuildConfig.DEBUG)
        val source = File("src/debug/java/com/matelink/debug/StateScenarioReviewActivity.kt").readText()

        assertTrue(source.contains("override fun attachBaseContext(newBase: Context)"))
        assertTrue(source.contains("getSharedPreferences(\"matelink_language\", MODE_PRIVATE)"))
        assertTrue(source.contains("getString(\"language_code\", \"\")"))
        assertTrue(source.contains("if (languageCode.isBlank() || languageCode !in StateScenarioFixtures.supportedLanguageCodes())"))
        assertTrue(source.contains("Configuration(newBase.resources.configuration)"))
        assertTrue(source.contains("config.setLocale(Locale(languageCode))"))
        assertTrue(source.contains("config.setLocales(LocaleList(Locale(languageCode)))"))
        assertTrue(source.contains("super.attachBaseContext(newBase.createConfigurationContext(config))"))
        assertTrue(source.indexOf("override fun attachBaseContext") < source.indexOf("override fun onCreate"))
    }

    @Test
    fun `debug activity rejects unsupported persisted locales before constructing Locale`() {
        assumeTrue(BuildConfig.DEBUG)
        val source = File("src/debug/java/com/matelink/debug/StateScenarioReviewActivity.kt").readText()

        assertTrue(source.contains("languageCode !in StateScenarioFixtures.supportedLanguageCodes()"))
        assertTrue(source.contains("if (languageCode.isBlank() || languageCode !in StateScenarioFixtures.supportedLanguageCodes())"))
        assertTrue(
            source.indexOf("languageCode !in StateScenarioFixtures.supportedLanguageCodes()") <
                source.indexOf("Locale(languageCode)")
        )
    }

    @Test
    fun `scenario selector uses structured English title and selector id`() {
        assumeTrue(BuildConfig.DEBUG)
        val source = File("src/debug/java/com/matelink/debug/StateScenarioReviewScreen.kt").readText()

        assertTrue(source.contains("val title = if (chinese) scenarioTitle(selector.id, true) else selector.title"))
        assertTrue(source.contains("selected = selector.id == selectedId"))
        assertTrue(source.contains("onClick = { onScenarioSelected(selector.id) }"))
        assertFalse(source.contains("scenarioTitle(scenario.id, chinese)"))
    }
}

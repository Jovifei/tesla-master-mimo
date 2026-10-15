package com.matelink.ui.screens.readiness

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.matelink.ui.theme.MateLinkTheme
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry

class ReadinessDiagnosticCardsAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sixIndependentCardsRemainReachableAtNarrowWidthAndLargeFont() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                MateLinkTheme {
                    Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        ReadinessDiagnosticCards(unknownDiagnosticFacts())
                    }
                }
            }
        }
        compose.waitForIdle()
        capture("diagnostic-56-top.png")
        DiagnosticStep.entries.forEach { step ->
            compose.onNodeWithTag("diagnostic_$step").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("diagnostic_$step").onChildren().filter(hasClickAction()).onFirst().performClick()
        }
        compose.waitForIdle()
        capture("diagnostic-56-details.png")
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}

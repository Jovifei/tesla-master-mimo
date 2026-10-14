package com.matelink.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.ui.theme.MateLinkTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual Android renderer in the isolated emulator package; no provider or user records. */
@RunWith(AndroidJUnit4::class)
class TelemetryMetricLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completeUnitsAtThreeWidthsFontScalesLanguagesAndThemes() {
        val width = mutableStateOf(320)
        val scale = mutableStateOf(1f)
        val english = mutableStateOf(false)
        val dark = mutableStateOf(false)
        val summary = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
                MateLinkTheme(darkTheme = dark.value) {
                    val labels = if (english.value) listOf("Duration", "Distance", "Maximum speed", "Average consumption")
                        else listOf("时长", "距离", "最高速度", "平均能耗")
                    val units = if (english.value) listOf("min", "mi", "mph", "kWh/100km")
                        else listOf("分钟", "km", "km/h", "kWh/100km")
                    Box(Modifier.width(width.value.dp).background(MaterialTheme.colorScheme.background).testTag("metric-matrix")) {
                        TelemetryMetricStrip(
                            labels.mapIndexed { index, label -> TelemetryMetricSpec(
                                Icons.Default.Speed, label, listOf("119", "12,345.6", "158", "-16.4")[index],
                                MaterialTheme.colorScheme.primary, unit = units[index],
                                valueFontSize = if (summary.value) 26.sp else 22.sp
                            ) },
                            modifier = Modifier.padding(12.dp), adaptive = true,
                            maxColumns = if (summary.value) 2 else 4
                        )
                    }
                }
            }
        }
        var scenarios = 0
        for (w in listOf(320, 360, 412)) for (s in listOf(1f, 1.3f, 1.6f))
            for (en in listOf(false, true)) for (night in listOf(false, true)) for (large in listOf(false, true)) {
                compose.runOnIdle { width.value = w; scale.value = s; english.value = en; dark.value = night; summary.value = large }
                compose.waitForIdle()
                val expected = listOf("119", "12,345.6", "158", "-16.4", "kWh/100km") +
                    if (en) listOf("min", "mi", "mph", "Maximum speed", "Average consumption")
                    else listOf("分钟", "km", "km/h", "最高速度", "平均能耗")
                for (text in expected) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    assertTrue("No real text layout for $text", layouts.isNotEmpty())
                    layouts.forEach {
                        assertFalse("Clipped $text at $w/$s/$en/$night/$large", it.hasVisualOverflow)
                        if (text in listOf("119", "12,345.6", "158", "-16.4"))
                            assertTrue("Number broken across lines: $text at $w/$s", it.lineCount == 1)
                    }
                }
                if (w == 320 && s == 1.6f && !en && !night && large) {
                    val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "metric-font160.png")
                    output.outputStream().use { compose.onNodeWithTag("metric-matrix").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                scenarios++
            }
        assertTrue("All width/font/language/theme/number cases rendered", scenarios == 72)
    }
}

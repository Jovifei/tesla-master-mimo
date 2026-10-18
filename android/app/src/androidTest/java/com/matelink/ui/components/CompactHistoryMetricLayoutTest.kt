package com.matelink.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.matelink.ui.theme.MateLinkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run only in the isolated emulator package, never against the owner's installed app. */
@RunWith(AndroidJUnit4::class)
class CompactHistoryMetricLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun historyKeepsOneShortRowAndCompleteUnitsAtLargeText() {
        val fontSize = mutableStateOf(18)
        val width = mutableStateOf(320)
        val scale = mutableStateOf(1f)
        val values = mutableStateOf(listOf("8", "2.5", "74", "-16.4"))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
                MateLinkTheme {
                    Box(Modifier.width(width.value.dp)) {
                        CompactHistoryMetricStrip(
                            listOf("时长", "距离", "最高速度", "能耗").mapIndexed { i, label ->
                                TelemetryMetricSpec(Icons.Default.Speed, label,
                                    values.value[i],
                                    MaterialTheme.colorScheme.primary,
                                    unit = listOf("分钟", "km", "km/h", "kWh/100km")[i])
                            }, numberFontSize = fontSize.value.sp
                        )
                    }
                }
            }
        }
        for (font in listOf(18, 20)) for (long in listOf(false, true)) for (w in listOf(288, 336, 388)) for (s in listOf(1f, 1.3f, 1.6f)) {
            compose.runOnIdle {
                fontSize.value = font; width.value = w; scale.value = s
                values.value = if (long) listOf("12,345", "1,234.5", "暂无数据", "-16.4")
                    else listOf("8", "2.5", "74", "-16.4")
            }
            compose.waitForIdle()
            var top: Float? = null
            repeat(4) { i ->
                val node = compose.onNodeWithTag("history-metric-value-$i", useUnmergedTree = true)
                node.performScrollTo().assertIsDisplayed()
                val bounds = node.getUnclippedBoundsInRoot()
                top?.let { assertEquals("metrics wrapped into multiple rows", it, bounds.top.value, 0.5f) }
                top = bounds.top.value
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue(layouts.isNotEmpty())
                layouts.forEach {
                    assertFalse("value=${values.value[i]} at $w/$s/long=$long size=${it.size} paragraph=${it.multiParagraph.width}x${it.multiParagraph.height} constraints=${it.layoutInput.constraints} lines=${it.lineCount} widthOverflow=${it.didOverflowWidth} heightOverflow=${it.didOverflowHeight}", it.hasVisualOverflow)
                    assertEquals(1, it.lineCount)
                }
            }
            val unit = compose.onNodeWithText("kWh/100km", useUnmergedTree = true)
            unit.assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            unit.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { assertFalse("unit clipped at $w/$s", it.hasVisualOverflow) }
            val rowBounds = compose.onNodeWithTag("history-metric-row").getUnclippedBoundsInRoot()
            assertTrue("history row is too tall", (rowBounds.bottom - rowBounds.top).value < 90f)
            if (w == 288 && s == 1.6f) assertTrue("large text must have reachable horizontal overflow",
                compose.onNodeWithTag("history-metric-row").fetchSemanticsNode()
                    .config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f)
        }
    }
}

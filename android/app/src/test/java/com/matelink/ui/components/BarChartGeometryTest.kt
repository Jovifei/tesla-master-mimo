package com.matelink.ui.components

import org.junit.Assert.*
import org.junit.Test

class BarChartGeometryTest {
    private fun geometry(count: Int = 12, labelWidth: Float = 80f, valueWidth: Float = 64f, textHeight: Float = 14f) =
        barChartGeometry(280f, count, 48f, labelWidth, valueWidth, textHeight, textHeight, 48f, 100f, 8f, true)

    @Test fun denseMonthsReserveWidthForLabelsAndValues() {
        val g = geometry()
        assertEquals(88f, g.columnWidth, 0f)
        assertTrue(g.contentWidth > 280f)
        assertTrue(g.axisWidth >= 48f)
        assertTrue(g.plotTop >= 14f)
        assertTrue(g.totalHeight - g.baseline >= 14f)
    }
    @Test fun longValuesAlsoDetermineColumnWidth() {
        assertEquals(148f, geometry(valueWidth = 140f).columnWidth, 0f)
    }
    @Test fun largeFontGrowsBothMargins() {
        val normal = geometry()
        val large = geometry(labelWidth = 160f, valueWidth = 128f, textHeight = 28f)
        assertTrue(large.columnWidth > normal.columnWidth)
        assertTrue(large.plotTop > normal.plotTop)
        assertTrue(large.totalHeight - large.baseline > normal.totalHeight - normal.baseline)
    }
    @Test fun paintingAndHitTestingUseTheSameContentCenters() {
        val g = geometry()
        repeat(12) { assertEquals(it, g.hit(g.center(it), 12)) }
        assertNull(g.hit(g.axisWidth - 1, 12))
        assertNull(g.hit(g.contentWidth, 12))
        assertNull(g.hit(Float.NaN, 12))
        val scroll = 300f
        val visibleTap = g.center(4) - scroll
        assertEquals(4, g.hit(visibleTap + scroll, 12))
    }
    @Test fun tooltipUsesViewportAndNeverHasNegativeClampBounds() {
        assertEquals(80f, barTooltipOffset(420f, 300f, 80f, 280f), 0f)
        assertEquals(0f, barTooltipOffset(20f, 0f, 400f, 280f), 0f)
        assertEquals(200f, barTooltipOffset(500f, 0f, 80f, 280f), 0f)
    }
    @Test fun defaultsFitViewportWithoutForcedMonthWidth() {
        val g = barChartGeometry(280f, 40, 24f, 80f, 80f, 12f, 12f, 48f, 100f, 8f, false)
        assertEquals(280f, g.contentWidth, 0.001f)
        assertEquals(0f, g.plotTop, 0f)
    }
}

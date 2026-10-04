package com.matelink.ui.components

/** Pixel geometry shared by painting and hit testing; text sizes come from Compose measurement. */
internal data class BarChartGeometry(
    val axisWidth: Float,
    val columnWidth: Float,
    val contentWidth: Float,
    val plotTop: Float,
    val plotHeight: Float,
    val baseline: Float,
    val totalHeight: Float
) {
    fun center(index: Int): Float = axisWidth + (index + 0.5f) * columnWidth
    fun hit(x: Float, count: Int): Int? {
        if (!x.isFinite() || x < axisWidth || x >= contentWidth || count <= 0) return null
        return ((x - axisWidth) / columnWidth).toInt().takeIf { it in 0 until count }
    }
}

internal fun barChartGeometry(
    viewportWidth: Float,
    count: Int,
    axisTextWidth: Float,
    monthTextWidth: Float,
    valueTextWidth: Float,
    monthTextHeight: Float,
    valueTextHeight: Float,
    minimumColumnWidth: Float,
    plotHeight: Float,
    gap: Float,
    showValues: Boolean
): BarChartGeometry {
    require(count > 0)
    val axis = axisTextWidth + gap
    val minimum = if (showValues) maxOf(minimumColumnWidth, monthTextWidth + gap, valueTextWidth + gap) else 0f
    val column = maxOf((viewportWidth - axis) / count, minimum, 1f)
    val top = if (showValues) valueTextHeight + gap else 0f
    val baseline = top + plotHeight
    return BarChartGeometry(axis, column, axis + column * count, top, plotHeight, baseline,
        baseline + monthTextHeight + gap)
}

/** Tooltip lives in viewport coordinates, while the selected bar lives in scroll content. */
internal fun barTooltipOffset(center: Float, scroll: Float, tooltipWidth: Float, viewportWidth: Float): Float =
    (center - scroll - tooltipWidth / 2).coerceIn(0f, (viewportWidth - tooltipWidth).coerceAtLeast(0f))

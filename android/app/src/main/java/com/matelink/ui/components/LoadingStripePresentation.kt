package com.matelink.ui.components

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI

/** Full-stripe brightness with a smooth stagger, including at the cycle boundary. */
internal fun loadingStripeAlpha(progress: Float, stripe: Int): Float {
    if (!progress.isFinite()) return 0.3f
    val phase = ((progress % 4f) + 4f) % 4f
    val distance = abs(phase - stripe).let { minOf(it, 4f - it) }
    val pulse = if (distance < 1f) (1f + cos(distance * PI).toFloat()) / 2f else 0f
    return 0.3f + 0.7f * pulse
}

/** Select the existing cyan/white lane pixels only, without changing their outline. */
internal fun loadingStripeIndex(argb: Int, x: Int, y: Int, width: Int, height: Int): Int? {
    if (width <= 0 || height <= 0 || (argb ushr 24) == 0) return null
    val red = (argb ushr 16) and 255
    val green = (argb ushr 8) and 255
    val blue = argb and 255
    val cyan = green > 60 && green > red * 3 && blue > red * 3
    val white = red > 180 && green > 180 && blue > 180
    if (!cyan && !white) return null
    val px = x * 515f / width
    val py = y * 273f / height
    if (px !in 300f..480f || py !in 60f..235f) return null
    return when {
        py < 113f -> 0
        py < 170f -> 1
        px > 373f -> 2
        else -> 3
    }
}

package com.matelink.ui.components

/** Days are newest first. Positive finger displacement goes to a newer day. No wraparound. */
internal fun dayStackTarget(index: Int, count: Int, deltaY: Float, threshold: Float): Int {
    if (count <= 0) return 0
    val current = index.coerceIn(0, count - 1)
    if (!deltaY.isFinite() || !threshold.isFinite() || threshold <= 0f) return current
    return when {
        deltaY <= -threshold -> (current + 1).coerceAtMost(count - 1)
        deltaY >= threshold -> (current - 1).coerceAtLeast(0)
        else -> current
    }
}

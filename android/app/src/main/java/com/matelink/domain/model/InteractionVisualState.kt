package com.matelink.domain.model

/** Presentation only. A timer, asset load or animation never grants account/vehicle evidence. */
enum class ConnectionVisualPhase { IDLE, CONNECTING, ACTION_REQUIRED, CONNECTED, UNAVAILABLE }
enum class VisualWheelState { UNKNOWN, RECORDED, STALE, WARNING }
data class VisualWheelReading(val pressureBar: Double?, val observedAt: Long?, val state: VisualWheelState)
const val VISUAL_TPMS_MAX_AGE_MS = 15 * 60 * 1000L

fun connectionVisualPhase(verified: Boolean, pending: Boolean, failed: Boolean,
    actionRequired: Boolean = false): ConnectionVisualPhase = when {
    failed -> ConnectionVisualPhase.UNAVAILABLE
    actionRequired -> ConnectionVisualPhase.ACTION_REQUIRED
    pending -> ConnectionVisualPhase.CONNECTING
    verified -> ConnectionVisualPhase.CONNECTED
    else -> ConnectionVisualPhase.IDLE
}

/** The caller must already have verified scope/provenance. Zero stays zero; no guessed thresholds. */
fun visualWheelReading(pressureBar: Double?, observedAt: Long?, now: Long,
    verified: Boolean, warning: Boolean = false): VisualWheelReading {
    if (!verified || pressureBar == null || !pressureBar.isFinite() || pressureBar !in 0.0..15.0 ||
        observedAt == null || observedAt <= 0 || now < observedAt)
        return VisualWheelReading(null, null, VisualWheelState.UNKNOWN)
    val state = when {
        now - observedAt > VISUAL_TPMS_MAX_AGE_MS -> VisualWheelState.STALE
        warning -> VisualWheelState.WARNING
        else -> VisualWheelState.RECORDED
    }
    return VisualWheelReading(pressureBar, observedAt, state)
}

/** Only the ordered received boundaries are usable. Absence is never filled by a fake endpoint. */
fun hasVisualRouteEndpoints(start: String?, end: String?): Boolean =
    !start.isNullOrBlank() && !end.isNullOrBlank()

fun visualMotionAllowed(foreground: Boolean, systemAnimations: Boolean, pending: Boolean): Boolean =
    foreground && systemAnimations && pending

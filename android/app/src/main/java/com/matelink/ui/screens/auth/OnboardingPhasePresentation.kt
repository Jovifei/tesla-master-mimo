package com.matelink.ui.screens.auth

import com.matelink.data.api.models.TelemetryPairingStatus

/**
 * Graded onboarding phases (design 2026-11-05 §8), mirroring the
 * backend's onboardingPhase grading in telemetry_onboarding.go. The
 * server sends `phase` directly; when an older backend does not, the
 * same grading is derived locally from status + configSynced (data
 * presence is not visible client-side, so a synced legacy status maps
 * to AWAITING_FIRST_EVENT and the polling observer keeps checking).
 *
 * Pure Kotlin so the grading is unit-testable; the Compose layer maps
 * each kind to its title/body strings.
 */
internal enum class OnboardingPhaseKind {
    PAIRING_REQUIRED,
    PERMISSION_REQUIRED,
    CONFIGURING,
    AWAITING_FIRST_EVENT,
    READY,
    BLOCKED,
    ERROR,
    UNKNOWN
}

internal fun onboardingPhaseKind(pairing: TelemetryPairingStatus): OnboardingPhaseKind {
    when (pairing.phase?.lowercase()) {
        "pairing_required" -> return OnboardingPhaseKind.PAIRING_REQUIRED
        "permission_required" -> return OnboardingPhaseKind.PERMISSION_REQUIRED
        "configuring" -> return OnboardingPhaseKind.CONFIGURING
        "awaiting_first_event" -> return OnboardingPhaseKind.AWAITING_FIRST_EVENT
        "ready" -> return OnboardingPhaseKind.READY
        "blocked" -> return OnboardingPhaseKind.BLOCKED
        "error" -> return OnboardingPhaseKind.ERROR
    }
    // Legacy fallback: derive from the raw fields like the backend does.
    return when (pairing.status.lowercase()) {
        "pairing_required" -> OnboardingPhaseKind.PAIRING_REQUIRED
        "permission_required" -> OnboardingPhaseKind.PERMISSION_REQUIRED
        "billing_blocked" -> OnboardingPhaseKind.BLOCKED
        "telemetry_error" -> OnboardingPhaseKind.ERROR
        "collecting", "available" -> OnboardingPhaseKind.READY
        else -> if (pairing.configSynced == true) {
            OnboardingPhaseKind.AWAITING_FIRST_EVENT
        } else {
            OnboardingPhaseKind.CONFIGURING
        }
    }
}

/** In-progress phases resolve on their own; the observer keeps polling. */
internal fun OnboardingPhaseKind.keepsObserving(): Boolean = when (this) {
    OnboardingPhaseKind.CONFIGURING,
    OnboardingPhaseKind.AWAITING_FIRST_EVENT -> true
    else -> false
}

/**
 * The reason token the blocked/pending panel uses to pick its copy.
 * Graded in-progress phases get their own tokens so the panel can say
 * "syncing the vehicle configuration" vs "waiting for the vehicle's
 * first data" instead of one generic pending text.
 */
internal fun OnboardingPhaseKind.panelReason(): String? = when (this) {
    OnboardingPhaseKind.CONFIGURING -> "configuring"
    OnboardingPhaseKind.AWAITING_FIRST_EVENT -> "awaiting_first_event"
    OnboardingPhaseKind.BLOCKED -> "billing_blocked"
    else -> null
}

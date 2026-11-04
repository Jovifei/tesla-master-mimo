package com.matelink.ui.screens.auth

import com.matelink.data.api.models.TelemetryPairingResponse
import com.matelink.data.api.models.TelemetryPairingStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract + grading tests for the onboarding phase presentation
 * (design 2026-11-05 §8). The grading mirrors the backend's
 * onboardingPhase in telemetry_onboarding.go.
 */
class OnboardingPhasePresentationTest {

    @Test
    fun parsesPhaseFromPairingPayload() {
        val parsed = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(TelemetryPairingResponse::class.java).fromJson(
                """{"data":{"status":"key_confirmed","phase":"awaiting_first_event","config_synced":true,"virtual_key_url":"https://tesla.com/_ak/auth.teslalink.joviluma.com"}}"""
            ) ?: error("response missing")
        assertEquals("awaiting_first_event", parsed.data?.phase)
        assertEquals(OnboardingPhaseKind.AWAITING_FIRST_EVENT, onboardingPhaseKind(parsed.data!!))
    }

    @Test
    fun serverPhaseWinsOverRawFields() {
        assertEquals(
            OnboardingPhaseKind.CONFIGURING,
            onboardingPhaseKind(TelemetryPairingStatus(status = "key_confirmed", phase = "configuring", configSynced = true))
        )
        assertEquals(
            OnboardingPhaseKind.READY,
            onboardingPhaseKind(TelemetryPairingStatus(status = "waiting_vehicle", phase = "ready", configSynced = false))
        )
        assertEquals(
            OnboardingPhaseKind.ERROR,
            onboardingPhaseKind(TelemetryPairingStatus(status = "collecting", phase = "error", configSynced = true))
        )
    }

    @Test
    fun legacyFallbackDerivesFromStatusAndSync() {
        assertEquals(OnboardingPhaseKind.PAIRING_REQUIRED,
            onboardingPhaseKind(TelemetryPairingStatus(status = "pairing_required")))
        assertEquals(OnboardingPhaseKind.PERMISSION_REQUIRED,
            onboardingPhaseKind(TelemetryPairingStatus(status = "permission_required")))
        assertEquals(OnboardingPhaseKind.BLOCKED,
            onboardingPhaseKind(TelemetryPairingStatus(status = "billing_blocked")))
        assertEquals(OnboardingPhaseKind.ERROR,
            onboardingPhaseKind(TelemetryPairingStatus(status = "telemetry_error")))
        assertEquals(OnboardingPhaseKind.READY,
            onboardingPhaseKind(TelemetryPairingStatus(status = "collecting")))
        assertEquals(OnboardingPhaseKind.AWAITING_FIRST_EVENT,
            onboardingPhaseKind(TelemetryPairingStatus(status = "key_confirmed", configSynced = true)))
        assertEquals(OnboardingPhaseKind.CONFIGURING,
            onboardingPhaseKind(TelemetryPairingStatus(status = "key_confirmed", configSynced = false)))
        assertEquals(OnboardingPhaseKind.CONFIGURING,
            onboardingPhaseKind(TelemetryPairingStatus(status = "waiting_vehicle", configSynced = null)))
    }

    @Test
    fun observingAndPanelReasonRules() {
        assertTrue(OnboardingPhaseKind.CONFIGURING.keepsObserving())
        assertTrue(OnboardingPhaseKind.AWAITING_FIRST_EVENT.keepsObserving())
        assertFalse(OnboardingPhaseKind.READY.keepsObserving())
        assertFalse(OnboardingPhaseKind.BLOCKED.keepsObserving())
        assertFalse(OnboardingPhaseKind.PAIRING_REQUIRED.keepsObserving())
        assertEquals("configuring", OnboardingPhaseKind.CONFIGURING.panelReason())
        assertEquals("awaiting_first_event", OnboardingPhaseKind.AWAITING_FIRST_EVENT.panelReason())
        assertEquals("billing_blocked", OnboardingPhaseKind.BLOCKED.panelReason())
        assertNull(OnboardingPhaseKind.READY.panelReason())
        assertNull(OnboardingPhaseKind.UNKNOWN.panelReason())
    }
}

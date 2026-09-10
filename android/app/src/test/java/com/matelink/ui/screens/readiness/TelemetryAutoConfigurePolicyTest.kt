package com.matelink.ui.screens.readiness

import com.matelink.data.api.models.TelemetryPairingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryAutoConfigurePolicyTest {
    @Test fun pairingRequiredAutoAttemptsConfiguration() {
        assertTrue(
            shouldAutoConfigureTelemetry(
                TelemetryPairingStatus(status = "pairing_required", configSynced = false, updatedAt = null),
                null
            )
        )
    }

    @Test fun persistedPairingRequiredDoesNotHammerConfiguration() {
        assertFalse(
            shouldAutoConfigureTelemetry(
                TelemetryPairingStatus(
                    status = "pairing_required",
                    configSynced = false,
                    updatedAt = "2026-09-07T00:00:00Z"
                ),
                null
            )
        )
    }

    @Test fun errorsAndSyncedStateDoNotAutoConfigure() {
        assertFalse(
            shouldAutoConfigureTelemetry(
                TelemetryPairingStatus(
                    status = "pairing_required",
                    configSynced = false,
                    updatedAt = "2026-09-07T00:00:00Z"
                ),
                "permission_required"
            )
        )
        assertFalse(
            shouldAutoConfigureTelemetry(
                TelemetryPairingStatus(status = "available", configSynced = true),
                null
            )
        )
        assertFalse(shouldAutoConfigureTelemetry(null, null))
    }
    @Test fun recoveredCaConfigurationErrorRetriesAutomatically() {
        assertTrue(
            shouldAutoConfigureTelemetry(
                TelemetryPairingStatus(status = "telemetry_error", configSynced = null, errorClass = "ca_unavailable", updatedAt = "2026-01-01T00:00:00Z"),
                null
            )
        )
        assertFalse(
            shouldAutoConfigureTelemetry(
                TelemetryPairingStatus(status = "telemetry_error", configSynced = null, errorClass = "unsupported_hardware"),
                null
            )
        )
    }
}

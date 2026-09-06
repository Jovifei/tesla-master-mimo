package com.matelink.ui.screens.readiness

import com.matelink.data.api.models.TelemetryPairingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryAutoConfigurePolicyTest {
    @Test fun pairingRequiredAutoAttemptsConfiguration() {
        assertTrue(
            shouldAutoConfigureTelemetry(
                TelemetryPairingStatus(status = "pairing_required", configSynced = false),
                null
            )
        )
    }

    @Test fun errorsAndSyncedStateDoNotAutoConfigure() {
        assertFalse(
            shouldAutoConfigureTelemetry(
                TelemetryPairingStatus(status = "pairing_required", configSynced = false),
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
}

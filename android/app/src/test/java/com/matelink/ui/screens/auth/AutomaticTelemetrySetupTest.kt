package com.matelink.ui.screens.auth

import com.matelink.data.api.models.TelemetryPairingStatus
import com.matelink.data.repository.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AutomaticTelemetrySetupTest {
    private fun status(value: String, synced: Boolean? = null) = ApiResult.Success(TelemetryPairingStatus(status = value, configSynced = synced, updatedAt = "2026-10-01T00:00:00Z"))

    @Test fun delayedConfirmationCompletesWithoutAnotherTap() = runTest {
        var reads = 0
        val published = mutableListOf<ApiResult<TelemetryPairingStatus>>()
        val result = observeAutomaticSetup(status("configuring"), { true }, {
            reads++
            if (reads == 1) status("waiting_vehicle") else status("available", true)
        }, published::add, pause = {})
        assertEquals(2, reads)
        assertEquals(3, published.size)
        assertFalse(shouldObserveAutomaticSetup(result))
    }

    @Test fun explicitOwnerActionsAreNeverRetriedAutomatically() {
        for (value in listOf("pairing_required", "permission_required", "billing_blocked")) {
            assertFalse(value, shouldObserveAutomaticSetup(status(value)))
        }
        assertFalse(shouldObserveAutomaticSetup(ApiResult.Error("permission", code = 403)))
    }

    @Test fun transportFailureRetriesButHasBoundedWindow() = runTest {
        var reads = 0
        observeAutomaticSetup(ApiResult.Error("service", code = 502), { true }, {
            reads++
            status("waiting_vehicle")
        }, {}, pause = {}, maximumReads = 2)
        assertEquals(2, reads)
    }

    @Test fun accountChangeDropsLateResponses() = runTest {
        var current = true
        var reads = 0
        var emissions = 0
        observeAutomaticSetup(status("configuring"), { current }, { reads++; status("available", true) },
            { emissions++ }, pause = { current = false })
        assertEquals(0, reads)
        assertEquals(1, emissions)
    }
}

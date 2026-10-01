package com.matelink.ui.screens.auth

import com.matelink.data.api.models.TelemetryPairingStatus
import com.matelink.data.repository.ApiResult
import kotlinx.coroutines.delay

internal fun shouldObserveAutomaticSetup(result: ApiResult<TelemetryPairingStatus>): Boolean = when (result) {
    is ApiResult.Error -> result.code in setOf(408, 429, 500, 502, 503, 504)
    is ApiResult.Success -> {
        val pairing = result.data
        pairing.configSynced != true && when (pairing.status.lowercase()) {
            "configuring", "waiting_vehicle", "collecting", "telemetry_not_configured", "key_confirmed" -> true
            "telemetry_error" -> pairing.errorClass?.lowercase() in setOf(null, "", "telemetry_error", "command_transport", "rate_limited", "ca_unavailable")
            else -> false
        }
    }
}

/** Observe backend-owned setup; never opens consent or submits commands itself. */
internal suspend fun observeAutomaticSetup(
    initial: ApiResult<TelemetryPairingStatus>,
    isCurrent: () -> Boolean,
    readNext: suspend () -> ApiResult<TelemetryPairingStatus>,
    publish: (ApiResult<TelemetryPairingStatus>) -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) },
    maximumReads: Int = 6
): ApiResult<TelemetryPairingStatus> {
    var result = initial
    repeat(maximumReads + 1) { attempt ->
        if (!isCurrent()) return result
        publish(result)
        if (!shouldObserveAutomaticSetup(result) || attempt == maximumReads) return result
        pause(5_000L)
        if (!isCurrent()) return result
        result = readNext()
    }
    return result
}

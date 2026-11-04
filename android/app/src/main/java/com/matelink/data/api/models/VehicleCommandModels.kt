package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Vehicle command request/response for
 * POST /api/v1/cars/{carId}/commands/{command} (design 2026-11-05 §6).
 *
 * Error responses carry a taxonomy class in `error` (commands_disabled,
 * scope_missing, confirmation_required, rate_limited, vehicle_moving,
 * not_plugged_in, key_not_paired, tesla_error, ...). A 200 response whose
 * result is "unverified" means Tesla accepted the command but the
 * follow-up status readback did not confirm the effect — the UI must show
 * that honestly instead of claiming success.
 */
@JsonClass(generateAdapter = true)
data class VehicleCommandRequest(
    @Json(name = "params") val params: Map<String, Any?>? = null,
    @Json(name = "confirm") val confirm: Boolean = false
)

@JsonClass(generateAdapter = true)
data class VehicleCommandResponse(
    @Json(name = "data") val data: VehicleCommandResult? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "missing_scopes") val missingScopes: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class VehicleCommandResult(
    @Json(name = "command") val command: String? = null,
    @Json(name = "result") val result: String? = null,
    @Json(name = "verified") val verified: Boolean? = null
)

package com.matelink.data.api.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Owner preview only. Helper proof and upload authorization never enter the phone. */
@JsonClass(generateAdapter = true)
data class ArchiveEnrollmentPreview(
    val handle: String,
    val status: String,
    @Json(name = "verification_code") val verificationCode: String,
    @Json(name = "expires_at") val expiresAt: String,
    @Json(name = "vehicle_id") val vehicleId: Int,
    @Json(name = "vehicle_name") val vehicleName: String,
    @Json(name = "source_label") val sourceLabel: String,
    @Json(name = "scope_fingerprint") val scopeFingerprint: String,
    @Json(name = "binding_state") val bindingState: String
)

@JsonClass(generateAdapter = true)
data class ArchiveEnrollmentDecision(
    val approve: Boolean,
    @Json(name = "verification_code") val verificationCode: String
)

@JsonClass(generateAdapter = true)
data class ArchiveEnrollmentResponse(val data: ArchiveEnrollmentPreview)

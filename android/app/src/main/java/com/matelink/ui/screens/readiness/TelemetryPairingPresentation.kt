package com.matelink.ui.screens.readiness

import java.net.URI

enum class TelemetrySetupPresentation {
    PAIRING_REQUIRED,
    WAITING_VEHICLE,
    PERMISSION_REQUIRED,
    BILLING_BLOCKED,
    TELEMETRY_ERROR,
    TELEMETRY_NOT_CONFIGURED,
    COLLECTING,
    AVAILABLE
}

enum class TelemetryConfigSyncPresentation {
    SYNCED,
    PENDING,
    UNKNOWN
}

enum class TelemetryConfigureActionPresentation {
    NONE,
    CONFIGURE
}

fun telemetrySetupPresentation(
    status: String?,
    configSynced: Boolean?
): TelemetrySetupPresentation = when (status?.trim()?.lowercase()) {
    "pairing_required" -> TelemetrySetupPresentation.PAIRING_REQUIRED
    "permission_required" -> TelemetrySetupPresentation.PERMISSION_REQUIRED
    "billing_blocked" -> TelemetrySetupPresentation.BILLING_BLOCKED
    "telemetry_not_configured" -> TelemetrySetupPresentation.TELEMETRY_NOT_CONFIGURED
    "telemetry_error" -> TelemetrySetupPresentation.TELEMETRY_ERROR
    "waiting_vehicle" -> TelemetrySetupPresentation.WAITING_VEHICLE
    else -> when {
        configSynced == false -> TelemetrySetupPresentation.WAITING_VEHICLE
        status?.trim()?.lowercase() == "collecting" -> TelemetrySetupPresentation.COLLECTING
        status?.trim()?.lowercase() == "available" && configSynced == true -> TelemetrySetupPresentation.AVAILABLE
        status?.trim()?.lowercase() == "available" -> TelemetrySetupPresentation.WAITING_VEHICLE
        else -> TelemetrySetupPresentation.TELEMETRY_ERROR
    }
}

fun telemetryConfigSyncPresentation(configSynced: Boolean?): TelemetryConfigSyncPresentation = when (configSynced) {
    true -> TelemetryConfigSyncPresentation.SYNCED
    false -> TelemetryConfigSyncPresentation.PENDING
    null -> TelemetryConfigSyncPresentation.UNKNOWN
}

/** Configuration remains an explicit user action after a pending status or polling timeout. */
fun telemetryConfigureActionPresentation(
    status: String?,
    configSynced: Boolean?
): TelemetryConfigureActionPresentation {
    if (configSynced == true) return TelemetryConfigureActionPresentation.NONE

    return when (status?.trim()?.lowercase()) {
        "pairing_required", "waiting_vehicle", "collecting", "available" ->
            TelemetryConfigureActionPresentation.CONFIGURE
        else -> TelemetryConfigureActionPresentation.NONE
    }
}

/** Accept the exact Tesla virtual-key URL shape and reject redirects or alternate hosts. */
fun officialTeslaVirtualKeyUrlOrNull(candidate: String?): String? {
    val uri = try {
        URI(candidate?.trim().orEmpty())
    } catch (_: Exception) {
        return null
    }
    if (
        uri.scheme?.equals("https", ignoreCase = true) != true ||
        uri.host?.lowercase() !in setOf("tesla.com", "www.tesla.com") ||
        uri.userInfo != null ||
        uri.port != -1 ||
        uri.rawQuery != null ||
        uri.rawFragment != null
    ) return null

    val rawPath = uri.rawPath ?: return null
    if (!rawPath.matches(Regex("^/_ak/[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$"))) return null
    return uri.toASCIIString()
}

/** Main-thread ViewModels use this gate so rapid taps cannot duplicate a configure request. */
class TelemetryConfigureGate {
    class Lease internal constructor(
        internal val generation: Long,
        private val sequence: Long
    )

    private var activeLease: Lease? = null
    private var nextSequence = 0L

    @Synchronized
    fun tryStart(generation: Long): Lease? {
        if (activeLease != null) return null
        return Lease(generation, ++nextSequence).also { activeLease = it }
    }

    @Synchronized
    fun finish(lease: Lease) {
        if (activeLease === lease) activeLease = null
    }
}

class TelemetryPollingPolicy {
    fun nextDelayMs(elapsedMs: Long): Long? = when {
        elapsedMs < 0L || elapsedMs >= MAXIMUM_WINDOW_MS -> null
        else -> POLL_INTERVAL_MS
    }

    fun shouldContinue(
        elapsedMs: Long,
        generation: Long,
        currentGeneration: Long,
        pageIsActive: Boolean
    ): Boolean = pageIsActive && generation == currentGeneration && elapsedMs in 0L..MAXIMUM_WINDOW_MS

    companion object {
        const val POLL_INTERVAL_MS = 5_000L
        const val MAXIMUM_WINDOW_MS = 30_000L
    }
}

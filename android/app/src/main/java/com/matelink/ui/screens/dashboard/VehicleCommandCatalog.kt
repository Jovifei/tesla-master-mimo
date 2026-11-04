package com.matelink.ui.screens.dashboard

import com.matelink.data.api.models.CarStatus

/**
 * The phase-1 vehicle command catalog (design 2026-11-05 §6.2), mirroring
 * the backend command table in vehicle_commands.go. The backend remains
 * the authority — this catalog only shapes what the UI offers, with the
 * same tiers and confirmation rules, so the interface never promises a
 * command the backend would reject for a client-side reason.
 *
 * Pure Kotlin (no Compose) so the offering rules are unit-testable.
 */
internal enum class CommandTier {
    LOW,
    MID,
    HIGH
}

internal data class CommandUi(
    val name: String,
    val tier: CommandTier,
    /** High-tier commands need the confirmation flag (and a confirm UI). */
    val needsConfirmation: Boolean
)

internal val vehicleCommandCatalog: List<CommandUi> = listOf(
    CommandUi("lock", CommandTier.LOW, needsConfirmation = false),
    CommandUi("unlock", CommandTier.HIGH, needsConfirmation = true),
    CommandUi("flash", CommandTier.LOW, needsConfirmation = false),
    CommandUi("honk", CommandTier.LOW, needsConfirmation = false),
    CommandUi("climate_on", CommandTier.LOW, needsConfirmation = false),
    CommandUi("climate_off", CommandTier.LOW, needsConfirmation = false),
    CommandUi("set_climate_temp", CommandTier.LOW, needsConfirmation = false),
    CommandUi("charge_port_open", CommandTier.LOW, needsConfirmation = false),
    CommandUi("charge_port_close", CommandTier.LOW, needsConfirmation = false),
    CommandUi("charge_start", CommandTier.MID, needsConfirmation = false),
    CommandUi("charge_stop", CommandTier.MID, needsConfirmation = false),
    CommandUi("set_charge_limit", CommandTier.MID, needsConfirmation = false),
    CommandUi("windows_vent", CommandTier.MID, needsConfirmation = false),
    CommandUi("windows_close", CommandTier.MID, needsConfirmation = false),
    CommandUi("sentry_on", CommandTier.MID, needsConfirmation = false),
    CommandUi("sentry_off", CommandTier.MID, needsConfirmation = false)
)

/**
 * Commands the UI may offer for this status snapshot. The whole section
 * stays hidden unless the backend evaluated the user's Tesla scopes and
 * reported them ready — an unevaluated status (null) or missing scopes
 * means the re-authorization prompt owns the surface instead.
 */
internal fun offeredCommands(status: CarStatus): List<CommandUi> {
    if (status.commandAuthorization?.ready != true) return emptyList()
    return vehicleCommandCatalog
}

/** True when the UI should surface the incremental re-authorization prompt. */
internal fun needsCommandReauthorization(status: CarStatus): Boolean {
    val authorization = status.commandAuthorization ?: return false
    return authorization.ready == false
}

package com.matelink.ui.screens.charges

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class ChargePhase {
    DC,
    SINGLE_PHASE_AC,
    THREE_PHASE_AC
}

internal fun chargePhaseFor(phases: Int?): ChargePhase? = when (phases) {
    0 -> ChargePhase.DC
    1 -> ChargePhase.SINGLE_PHASE_AC
    2, 3 -> ChargePhase.THREE_PHASE_AC
    else -> null
}

internal fun formatChargeMetric(value: Double?, unit: String): String? {
    if (value == null || !value.isFinite()) return null
    return "%.1f %s".format(Locale.ROOT, value, unit)
}

internal fun isValidScheduledChargingTime(value: String?): Boolean =
    value?.let { runCatching { OffsetDateTime.parse(it) }.isSuccess } == true

internal fun formatScheduledChargingTime(value: String?, is24Hour: Boolean): String? {
    val parsed = value?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return null
    val pattern = if (is24Hour) "MM-dd HH:mm" else "MM-dd hh:mm a"
    return parsed.atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

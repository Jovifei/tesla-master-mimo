package com.matelink.ui.components

/** Display precision preserves the distinction between a true zero and a tiny observation. */
fun formatTelemetryNumber(value: Double, decimals: Int = 1): String {
    if (!value.isFinite()) return "—"
    val magnitude = kotlin.math.abs(value)
    val precision = if (value != 0.0 && magnitude < Math.pow(10.0, -decimals.toDouble()) / 2.0) {
        (kotlin.math.ceil(-kotlin.math.log10(magnitude)).toInt() + 1).coerceAtMost(6)
    } else decimals
    return if (value != 0.0 && magnitude < 0.000001) "%.2g".format(value)
    else "%,.${precision}f".format(value)
}

fun formatTelemetryEfficiency(whKm: Double): String = "${formatTelemetryNumber(whKm / 10.0)} kWh/100km"

/** Split only a scalar and known physical unit; compound durations stay intact. */
fun telemetryMetricParts(display: String): Pair<String, String?> {
    val match = Regex("^([+\\-]?[\\p{Nd},.٫٬]+(?:[eE][+\\-]?\\p{Nd}+)?)\\s*(kWh/100km|Wh/km|Wh/mi|kWh|km/h|mph|km|mi|kW|bar|psi|°C|°F|%)$").matchEntire(display)
    return if (match == null) display to null else match.groupValues[1] to match.groupValues[2]
}

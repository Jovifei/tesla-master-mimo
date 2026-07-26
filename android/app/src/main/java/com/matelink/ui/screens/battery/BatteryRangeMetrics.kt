package com.matelink.ui.screens.battery

data class BatteryRangeMetrics(
    val maxRangeKm: Double?,
    val currentRangeKm: Double?,
    val rangeLossKm: Double?
) {
    companion object {
        fun from(maxRangeKm: Double?, currentRangeKm: Double?): BatteryRangeMetrics {
            val validMaxRangeKm = maxRangeKm?.takeIf { it.isFinite() && it >= 0.0 }
            val validCurrentRangeKm = currentRangeKm?.takeIf { it.isFinite() && it >= 0.0 }

            return BatteryRangeMetrics(
                maxRangeKm = validMaxRangeKm,
                currentRangeKm = validCurrentRangeKm,
                rangeLossKm = if (validMaxRangeKm != null && validCurrentRangeKm != null) {
                    (validMaxRangeKm - validCurrentRangeKm).coerceAtLeast(0.0)
                } else {
                    null
                }
            )
        }
    }
}

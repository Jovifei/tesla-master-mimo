package com.matelink.data.model

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Time-of-use tariff configuration for charging cost calculation.
 * Default values match typical Chinese residential electricity rates.
 */
data class TariffConfig(
    val peakPrice: Double = 1.0,      // ¥/kWh
    val flatPrice: Double = 0.7,      // ¥/kWh
    val valleyPrice: Double = 0.3,    // ¥/kWh
    val peakHours: List<IntRange> = listOf(10..14, 18..20),   // 10:00-15:00, 18:00-21:00 (对齐 iOS)
    val flatHours: List<IntRange> = listOf(7..9, 15..17, 21..22),  // 7:00-10:00, 15:00-18:00, 21:00-23:00 (对齐 iOS)
    val valleyHours: List<IntRange> = listOf(23..23, 0..6),   // 23:00-7:00 (对齐 iOS)
    val isEnabled: Boolean = true     // 启用/禁用分时电价
) {
    /**
     * Get the price for a specific hour.
     * Left-closed right-open: [08:00, 09:00) belongs to peak.
     */
    fun getPriceForHour(hour: Int): Double {
        return when {
            peakHours.any { hour in it } -> peakPrice
            flatHours.any { hour in it } -> flatPrice
            else -> valleyPrice
        }
    }

    /**
     * Calculate charging cost with cross-period billing.
     * Splits charging duration into per-minute segments and applies appropriate tariff.
     *
     * @param startTime Charging start time
     * @param endTime Charging end time
     * @param totalEnergyKwh Total energy charged in kWh
     * @return Total cost in ¥
     */
    fun calculateCost(startTime: LocalDateTime, endTime: LocalDateTime, totalEnergyKwh: Double): Double {
        val durationMinutes = ChronoUnit.MINUTES.between(startTime, endTime)
        if (durationMinutes <= 0) return 0.0

        val energyPerMinute = totalEnergyKwh / durationMinutes
        var totalCost = 0.0
        var currentTime = startTime

        while (currentTime.isBefore(endTime)) {
            val price = getPriceForHour(currentTime.hour)
            totalCost += energyPerMinute * price
            currentTime = currentTime.plusMinutes(1)
        }

        return totalCost
    }

    /**
     * Get the period name for a specific hour.
     */
    fun getPeriodName(hour: Int): String {
        return when {
            peakHours.any { hour in it } -> "峰"
            flatHours.any { hour in it } -> "平"
            else -> "谷"
        }
    }
}

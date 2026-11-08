package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData
import com.matelink.data.local.AppSettings
import java.time.ZoneId
import java.time.Duration
import java.time.temporal.ChronoUnit
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

data class ChargeTariffEstimate(val amount: Double?, val reason: String)

/** App-local tariff estimate; a known provider/manual bill always has priority. */
internal fun estimateChargeTariff(charge: ChargeData, settings: AppSettings,
    zone: ZoneId = ZoneId.systemDefault()): ChargeTariffEstimate =
    estimateChargeTariffForEnergy(charge, settings, zone, null)

/** Calculate a display-only reference using the same tariff schedule, without qualifying the raw energy. */
internal fun estimateReferenceChargeTariff(charge: ChargeData, settings: AppSettings,
    referenceEnergyKwh: Double, zone: ZoneId): ChargeTariffEstimate =
    estimateChargeTariffForEnergy(charge, settings, zone, referenceEnergyKwh)

private fun estimateChargeTariffForEnergy(charge: ChargeData, settings: AppSettings,
    zone: ZoneId, referenceEnergyKwh: Double?): ChargeTariffEstimate {
    val partial = if (referenceEnergyKwh == null) charge.partialBatteryInputKwh else null
    val energy = referenceEnergyKwh ?: charge.batteryInputKwh ?: partial
    if (!settings.tariffEnabled) return ChargeTariffEstimate(
        manualChargeAmount(settings.defaultChargePrice, energy),
        if (charge.batteryInputKwh == null && partial != null) "estimate_partial_counter" else "estimate")
    fun unavailable() = ChargeTariffEstimate(null, "tariff_coverage_unavailable")
    val validEnergy = energy?.takeIf { it.isFinite() && it >= 0.0 } ?: return unavailable()
    val rates = arrayOfNulls<Double>(24)
    val bands = listOf(settings.tariffPeakRanges to settings.tariffPeakPrice,
        settings.tariffFlatRanges to settings.tariffFlatPrice,
        settings.tariffValleyRanges to settings.tariffValleyPrice)
    for ((json, rate) in bands) {
        if (!rate.isFinite() || rate < 0.0) return unavailable()
        val ranges = runCatching { hourRangesAdapter.fromJson(json) }.getOrNull() ?: return unavailable()
        for (range in ranges) {
            if (range.size != 2 || range.any { it !in 0..23 }) return unavailable()
            var hour = range[0]
            while (true) {
                if (rates[hour] != null) return unavailable()
                rates[hour] = rate
                if (hour == range[1]) break
                hour = (hour + 1) % 24
            }
        }
    }
    if (rates.any { it == null }) return unavailable()
    val partialMetric = charge.energyContract?.batteryInput?.takeIf { charge.batteryInputKwh == null && partial != null }
    val start = chargeCostInstant(partialMetric?.observedStartAt ?: charge.startDate)
    val end = chargeCostInstant(partialMetric?.observedEndAt ?: charge.endDate)
    if (start == null || end == null || !end.isAfter(start) || Duration.between(start, end) > Duration.ofDays(31))
        return ChargeTariffEstimate(manualChargeAmount(settings.defaultChargePrice, validEnergy),
            "tariff_window_unavailable_default_rate")
    // Only session total energy is known: uniform charging power over elapsed
    // time is an explicit estimate, not a measured hourly bill or grid input.
    var cursor: java.time.Instant = start
    var weightedSeconds = 0.0
    while (cursor.isBefore(end)) {
        val local = cursor.atZone(zone)
        val nextHour = local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant()
        val next = minOf(end, nextHour)
        if (!next.isAfter(cursor)) return unavailable()
        weightedSeconds += Duration.between(cursor, next).toNanos() / 1e9 * rates[local.hour]!!
        cursor = next
    }
    val elapsedSeconds = Duration.between(start, end).toNanos() / 1e9
    val cost = (validEnergy * weightedSeconds / elapsedSeconds).takeIf { it.isFinite() && it >= 0.0 }
    return ChargeTariffEstimate(cost, if (partialMetric != null) "estimate_partial_counter" else "tariff_time_weighted_app_zone")
}

private val hourRangesAdapter = Moshi.Builder().build().adapter<List<List<Int>>>(
    Types.newParameterizedType(List::class.java, Types.newParameterizedType(List::class.java, Int::class.javaObjectType)))

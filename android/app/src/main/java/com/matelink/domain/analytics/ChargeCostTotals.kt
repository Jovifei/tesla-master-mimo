package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData

internal data class CostTotals(
    val knownTotal: Double?, val qualifiedBatteryTotal: Double?, val estimatedTotal: Double?,
    val pricedCount: Int, val energyCount: Int, val estimatedCount: Int,
    val pairedCount: Int, val pairedUnitCost: Double?, val averageKnownCost: Double?
)

/** Price and qualified battery energy must belong to the same session before division. */
internal fun calculateCostTotals(
    charges: List<ChargeData>, costFor: (ChargeData) -> Double?, defaultPricePerKwh: Double?,
    estimateFor: ((ChargeData) -> Double?)? = null
): CostTotals {
    val rate = defaultPricePerKwh?.takeIf { it.isFinite() && it >= 0.0 }
    val records = charges.map { charge ->
        Pair(costFor(charge)?.takeIf { it.isFinite() && it >= 0.0 },
            charge.batteryInputKwh?.takeIf { it.isFinite() && it >= 0.0 })
    }
    val prices = records.mapNotNull { it.first }
    val energies = records.mapNotNull { it.second }
    val paired = records.filter { it.first != null && (it.second ?: 0.0) > 0.0 }
    val pairedEnergy = paired.sumOf { it.second!! }
    val estimates = charges.zip(records).mapNotNull { (charge, values) ->
        val (price, energy) = values
        if (price != null || energy == null) null else
            (if (estimateFor != null) estimateFor(charge) else rate?.times(energy))
                ?.takeIf { it.isFinite() && it >= 0.0 }
    }
    val known = prices.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite)
    return CostTotals(
        knownTotal = known,
        qualifiedBatteryTotal = energies.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite),
        estimatedTotal = estimates.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite),
        pricedCount = prices.size, energyCount = energies.size, estimatedCount = estimates.size,
        pairedCount = paired.size,
        pairedUnitCost = if (pairedEnergy > 0.0 && pairedEnergy.isFinite())
            (paired.sumOf { it.first!! } / pairedEnergy).takeIf(Double::isFinite) else null,
        averageKnownCost = if (known != null && prices.isNotEmpty()) known / prices.size else null
    )
}

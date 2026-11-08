package com.matelink.domain.analytics

import com.matelink.data.api.models.ChargeData

internal data class CostTotals(
    val knownTotal: Double?, val qualifiedBatteryTotal: Double?, val estimatedTotal: Double?,
    val pricedCount: Int, val energyCount: Int, val estimatedCount: Int,
    val pairedCount: Int, val pairedUnitCost: Double?, val averageKnownCost: Double?,
    /** Only the observed part of a session; never part of a bill or full-energy denominator. */
    val partialEstimatedTotal: Double? = null, val partialEstimatedCount: Int = 0
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
    val estimates = mutableListOf<Double>()
    val partialEstimates = mutableListOf<Double>()
    charges.zip(records).forEach { (charge, values) ->
        val (price, wholeEnergy) = values
        if (price != null) return@forEach
        // Admission uses the existing same-source/window partial-counter proof,
        // never the raw scalar. The callback cannot qualify missing energy.
        val energy = wholeEnergy ?: charge.partialBatteryInputKwh
            ?.takeIf { it.isFinite() && it >= 0.0 } ?: return@forEach
        val amount = (if (estimateFor != null) estimateFor(charge) else rate?.times(energy))
            ?.takeIf { it.isFinite() && it >= 0.0 } ?: return@forEach
        if (wholeEnergy != null) estimates += amount else partialEstimates += amount
    }
    fun total(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }
        ?.sum()?.takeIf(Double::isFinite)
    val known = total(prices)
    return CostTotals(
        knownTotal = known,
        qualifiedBatteryTotal = total(energies),
        estimatedTotal = total(estimates),
        pricedCount = prices.size, energyCount = energies.size, estimatedCount = estimates.size,
        pairedCount = paired.size,
        pairedUnitCost = if (pairedEnergy > 0.0 && pairedEnergy.isFinite())
            (paired.sumOf { it.first!! } / pairedEnergy).takeIf(Double::isFinite) else null,
        averageKnownCost = if (known != null && prices.isNotEmpty()) known / prices.size else null,
        partialEstimatedTotal = total(partialEstimates), partialEstimatedCount = partialEstimates.size
    )
}

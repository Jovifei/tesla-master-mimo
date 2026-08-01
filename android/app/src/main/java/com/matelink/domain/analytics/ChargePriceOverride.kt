package com.matelink.domain.analytics

fun chargePriceOverrideKey(carId: Int, chargeId: Int): String = "$carId:$chargeId"

fun manualChargeAmount(pricePerKwh: Double?, energyKwh: Double?): Double? {
    val price = pricePerKwh?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    val energy = energyKwh?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    return price * energy
}

fun resolveChargeCost(
    pricePerKwh: Double?,
    freeSupercharging: Boolean,
    isDcCharge: Boolean,
    teslaMateCost: Double?,
    energyKwh: Double?
): EffectiveChargeCost {
    val validEnergy = energyKwh?.takeIf { it.isFinite() && it >= 0.0 }
    return EffectiveChargeCostResolver.resolve(
        EffectiveChargeCostInput(
            manualAmount = manualChargeAmount(pricePerKwh, validEnergy),
            manuallyFree = freeSupercharging && isDcCharge,
            teslaMateCost = teslaMateCost,
            energyKwh = validEnergy
        )
    )
}


fun resolveChargeCostFromTotal(
    manualTotalAmount: Double?,
    freeSupercharging: Boolean,
    isDcCharge: Boolean,
    teslaMateCost: Double?,
    energyKwh: Double?
): EffectiveChargeCost = EffectiveChargeCostResolver.resolve(
    EffectiveChargeCostInput(
        manualAmount = validManualChargeTotal(manualTotalAmount),
        manuallyFree = freeSupercharging && isDcCharge,
        teslaMateCost = teslaMateCost,
        energyKwh = energyKwh?.takeIf { it.isFinite() && it >= 0.0 }
    )
)

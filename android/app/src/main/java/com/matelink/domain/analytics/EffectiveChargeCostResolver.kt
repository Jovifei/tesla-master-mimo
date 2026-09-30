package com.matelink.domain.analytics

data class EffectiveChargeCostInput(
    val manualAmount: Double? = null,
    val manuallyFree: Boolean = false,
    val teslaMateCost: Double? = null,
    val energyKwh: Double? = null,
    val defaultPricePerKwh: Double? = null
)

data class EffectiveChargeCost(
    val cost: Double?,
    val source: ChargeCostSource
)

enum class ChargeCostSource {
    MANUAL,
    FREE,
    TESLAMATE,
    ESTIMATE,
    UNAVAILABLE
}

object EffectiveChargeCostResolver {

    fun resolve(input: EffectiveChargeCostInput): EffectiveChargeCost {
        val manualAmount = input.manualAmount?.takeIf { it.isFinite() && it >= 0.0 }
        val teslaMateCost = input.teslaMateCost?.takeIf { it.isFinite() && it > 0.0 }
        val estimate = manualChargeAmount(input.defaultPricePerKwh, input.energyKwh)

        return when {
            manualAmount != null -> EffectiveChargeCost(manualAmount, ChargeCostSource.MANUAL)
            input.manuallyFree -> EffectiveChargeCost(0.0, ChargeCostSource.FREE)
            teslaMateCost != null -> EffectiveChargeCost(teslaMateCost, ChargeCostSource.TESLAMATE)
            estimate != null && estimate.isFinite() -> EffectiveChargeCost(estimate, ChargeCostSource.ESTIMATE)
            else -> EffectiveChargeCost(null, ChargeCostSource.UNAVAILABLE)
        }
    }
}

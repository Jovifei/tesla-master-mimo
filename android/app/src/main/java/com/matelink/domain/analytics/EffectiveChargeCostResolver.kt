package com.matelink.domain.analytics

data class EffectiveChargeCostInput(
    val manualAmount: Double? = null,
    val manuallyFree: Boolean = false,
    val teslaMateCost: Double? = null,
    val energyKwh: Double? = null
)

data class EffectiveChargeCost(
    val cost: Double?,
    val source: ChargeCostSource
)

enum class ChargeCostSource {
    MANUAL,
    FREE,
    TESLAMATE,
    ESTIMATE
}

object EffectiveChargeCostResolver {

    private const val ESTIMATED_COST_PER_KWH = 1.10

    fun resolve(input: EffectiveChargeCostInput): EffectiveChargeCost {
        val manualAmount = input.manualAmount?.takeIf { it.isFinite() && it >= 0.0 }
        val teslaMateCost = input.teslaMateCost?.takeIf { it.isFinite() && it > 0.0 }
        val estimatedCost = input.energyKwh?.takeIf { it.isFinite() }?.times(ESTIMATED_COST_PER_KWH)

        return when {
            manualAmount != null -> EffectiveChargeCost(manualAmount, ChargeCostSource.MANUAL)
            input.manuallyFree -> EffectiveChargeCost(0.0, ChargeCostSource.FREE)
            teslaMateCost != null -> EffectiveChargeCost(teslaMateCost, ChargeCostSource.TESLAMATE)
            else -> EffectiveChargeCost(estimatedCost, ChargeCostSource.ESTIMATE)
        }
    }
}

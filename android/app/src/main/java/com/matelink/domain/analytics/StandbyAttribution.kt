package com.matelink.domain.analytics

enum class StandbyCause {
    SENTINEL,
    CABIN_OVERHEAT,
    CLIMATE,
    UNKNOWN
}

data class StandbyAttribution(
    val cause: StandbyCause,
    val confidence: Float
)

/** Cause labels are evidence-backed; a battery drop alone can only be unknown. */
fun standbyAttribution(
    sentinelRecorded: Boolean,
    cabinOverheatRecorded: Boolean,
    climateRecorded: Boolean
): StandbyAttribution = when {
    sentinelRecorded -> StandbyAttribution(StandbyCause.SENTINEL, 1f)
    cabinOverheatRecorded -> StandbyAttribution(StandbyCause.CABIN_OVERHEAT, 1f)
    climateRecorded -> StandbyAttribution(StandbyCause.CLIMATE, 1f)
    else -> StandbyAttribution(StandbyCause.UNKNOWN, 0f)
}

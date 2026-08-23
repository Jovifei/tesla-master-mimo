package com.matelink.domain.analytics

data class StandbyEnergyEstimate(
    val energyKwh: Double,
    val averagePowerW: Double
)

/** A short gap between charges is not strong enough evidence of standby drain. */
fun isQualifiedStandbyWindow(
    hoursIdle: Double,
    minimumHours: Double = MINIMUM_STANDBY_WINDOW_HOURS
): Boolean = hoursIdle.isFinite() && minimumHours.isFinite() &&
    minimumHours > 0.0 && hoursIdle >= minimumHours

/** Returns null when battery capacity is not observed or otherwise unusable. */
fun estimateStandbyEnergy(
    drainPercent: Int,
    usableBatteryKwh: Double?,
    hoursIdle: Double
): StandbyEnergyEstimate? {
    val capacity = usableBatteryKwh?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    if (drainPercent <= 0 || !hoursIdle.isFinite() || hoursIdle <= 0.0) return null

    val energyKwh = drainPercent / 100.0 * capacity
    return StandbyEnergyEstimate(
        energyKwh = energyKwh,
        averagePowerW = energyKwh * 1000.0 / hoursIdle
    )
}

private const val MINIMUM_STANDBY_WINDOW_HOURS = 2.0

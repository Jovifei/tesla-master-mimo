package com.matelink.domain.analytics

enum class DriveEnergySource {
    API,
    POWER_SAMPLES,
    UNAVAILABLE
}

data class DriveEnergyEstimate(
    val energyKwh: Double?,
    val efficiencyWhKm: Double?,
    val source: DriveEnergySource,
    val coverageSeconds: Long = 0L
)

/**
 * Uses server-provided energy when available, otherwise estimates consumption from
 * TeslaMate's time-series power samples. Missing source data remains unknown.
 */
object DriveEnergyResolver {

    fun resolve(
        apiEnergyKwh: Double?,
        distanceKm: Double?,
        samples: List<DrivePowerSample>
    ): DriveEnergyEstimate {
        val apiEnergy = apiEnergyKwh?.takeIf { it.isFinite() && it > 0.0 }
        if (apiEnergy != null) {
            return estimate(apiEnergy, distanceKm, DriveEnergySource.API)
        }

        val calculated = DriveEnergyCalculator.calculate(samples)
        val calculatedEnergy = calculated.energyKwh
        if (calculatedEnergy != null) {
            return estimate(
                energyKwh = calculatedEnergy,
                distanceKm = distanceKm,
                source = DriveEnergySource.POWER_SAMPLES,
                coverageSeconds = calculated.coverageSeconds
            )
        }

        return DriveEnergyEstimate(
            energyKwh = null,
            efficiencyWhKm = null,
            source = DriveEnergySource.UNAVAILABLE
        )
    }

    private fun estimate(
        energyKwh: Double,
        distanceKm: Double?,
        source: DriveEnergySource,
        coverageSeconds: Long = 0L
    ): DriveEnergyEstimate = DriveEnergyEstimate(
        energyKwh = energyKwh,
        efficiencyWhKm = distanceKm
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { energyKwh * 1000.0 / it },
        source = source,
        coverageSeconds = coverageSeconds
    )
}

package com.matelink.domain.analytics

enum class DriveEnergySource { API, POWER_SAMPLES, UNAVAILABLE }

data class DriveEnergyEstimate(
    val energyKwh: Double?,
    val efficiencyWhKm: Double?,
    val source: DriveEnergySource,
    val coverageSeconds: Long = 0L,
    val coverageSecondsExact: Double = coverageSeconds.toDouble(),
    val coverageRatio: Double? = null,
    /** Covered-subset integral is diagnostic only when whole-window energy is unknown. */
    val observedEnergyKwh: Double? = null,
    val qualityReason: String? = null
)

/** API-reported net energy is not automatically a measured battery counter. */
object DriveEnergyResolver {
    fun resolve(
        apiEnergyKwh: Double?,
        distanceKm: Double?,
        samples: List<DrivePowerSample>,
        durationSeconds: Long? = null,
        startDate: String? = null,
        endDate: String? = null
    ): DriveEnergyEstimate {
        // Zero and negative net recovery are legitimate reported values.
        apiEnergyKwh?.takeIf(Double::isFinite)?.let { energy ->
            return DriveEnergyEstimate(energy, efficiency(energy, distanceKm), DriveEnergySource.API,
                qualityReason = "api_reported_net_energy")
        }
        val calculated = DriveEnergyCalculator.calculate(samples, startDate, endDate)
        val denominator = calculated.windowSeconds ?: durationSeconds?.takeIf { it > 0L }?.toDouble()
        val coverage = denominator?.let { (calculated.coverageSecondsExact / it).takeIf(Double::isFinite) }
        // A rounded duration, or the first/last available samples, cannot invent
        // the missing start/end of a drive. Partial integrals remain diagnostic.
        val energy = calculated.energyKwh?.takeIf { calculated.complete }
        return DriveEnergyEstimate(
            energyKwh = energy,
            efficiencyWhKm = energy?.let { efficiency(it, distanceKm) },
            source = if (energy == null) DriveEnergySource.UNAVAILABLE else DriveEnergySource.POWER_SAMPLES,
            coverageSeconds = calculated.coverageSeconds,
            coverageSecondsExact = calculated.coverageSecondsExact,
            coverageRatio = coverage,
            observedEnergyKwh = calculated.energyKwh,
            qualityReason = calculated.qualityReason
        )
    }

    private fun efficiency(energyKwh: Double, distanceKm: Double?): Double? =
        distanceKm?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (energyKwh / it * 1000.0).takeIf(Double::isFinite) }
}

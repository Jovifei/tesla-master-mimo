package com.matelink.domain.analytics

enum class DriveEnergySource {
    API,
    POWER_SAMPLES,
    ENERGY_REMAINING_DELTA,
    SOC_CAPACITY_ESTIMATE,
    UNAVAILABLE
}

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
    const val DEFAULT_BATTERY_CAPACITY_KWH = 60.0

    /**
     * Capacity input, model lookup and the default are estimates. A positive
     * API number does not prove calibration; the binding persists the actual basis.
     */
    @JvmStatic
    @JvmOverloads
    fun resolveBatteryCapacity(
        model: String? = null,
        trimBadging: String? = null,
        calibratedCapacityKwh: Double? = null
    ): Double {
        calibratedCapacityKwh?.takeIf { it > 0.0 && it.isFinite() }?.let { return it }
        val cleanModel = model?.trim()?.uppercase()
        val cleanTrim = trimBadging?.trim()?.uppercase()
        return when {
            cleanModel == "S" || cleanModel == "X" -> when {
                cleanTrim?.contains("100") == true || cleanTrim?.contains("PLAID") == true -> 100.0
                cleanTrim?.contains("90") == true -> 90.0
                cleanTrim?.contains("85") == true -> 85.0
                cleanTrim?.contains("75") == true -> 75.0
                else -> 100.0
            }
            cleanModel == "3" || cleanModel == "Y" -> when {
                cleanTrim?.contains("PERFORMANCE") == true || cleanTrim?.startsWith("P") == true ||
                cleanTrim?.contains("LONG") == true || cleanTrim?.contains("LR") == true ||
                cleanTrim?.contains("AWD") == true || cleanTrim?.contains("DUAL") == true -> 78.4
                else -> 60.0
            }
            else -> DEFAULT_BATTERY_CAPACITY_KWH
        }
    }

    @JvmStatic
    @JvmOverloads
    fun resolve(
        apiEnergyKwh: Double?,
        distanceKm: Double?,
        samples: List<DrivePowerSample>,
        durationSeconds: Long? = null,
        startDate: String? = null,
        endDate: String? = null,
        startEnergyRemaining: Double? = null,
        endEnergyRemaining: Double? = null,
        startSoc: Int? = null,
        endSoc: Int? = null,
        batteryCapacityKwh: Double? = null,
        model: String? = null,
        trimBadging: String? = null
    ): DriveEnergyEstimate {
        // Zero and negative net recovery are legitimate reported values.
        apiEnergyKwh?.takeIf(Double::isFinite)?.let { energy ->
            return DriveEnergyEstimate(
                energy, efficiency(energy, distanceKm), DriveEnergySource.API,
                qualityReason = "api_reported_net_energy"
            )
        }
        val calculated = DriveEnergyCalculator.calculate(samples, startDate, endDate)
        val denominator = calculated.windowSeconds ?: durationSeconds?.takeIf { it > 0L }?.toDouble()
        val coverage = denominator?.let { (calculated.coverageSecondsExact / it).takeIf(Double::isFinite) }
        // 1. Complete power window integral (power samples)
        if (calculated.complete && calculated.energyKwh != null) {
            val energy = calculated.energyKwh
            return DriveEnergyEstimate(
                energyKwh = energy,
                efficiencyWhKm = efficiency(energy, distanceKm),
                source = DriveEnergySource.POWER_SAMPLES,
                coverageSeconds = calculated.coverageSeconds,
                coverageSecondsExact = calculated.coverageSecondsExact,
                coverageRatio = coverage,
                observedEnergyKwh = calculated.energyKwh,
                qualityReason = calculated.qualityReason
            )
        }
        // 2. Fallback to EnergyRemaining delta when endpoints are observed
        if (startEnergyRemaining != null && endEnergyRemaining != null &&
            startEnergyRemaining.isFinite() && endEnergyRemaining.isFinite() &&
            startEnergyRemaining in 0.0..150.0 && endEnergyRemaining in 0.0..150.0
        ) {
            val delta = startEnergyRemaining - endEnergyRemaining
            if (delta.isFinite() && delta in -40.0..120.0) {
                return DriveEnergyEstimate(
                    energyKwh = delta,
                    efficiencyWhKm = efficiency(delta, distanceKm),
                    source = DriveEnergySource.ENERGY_REMAINING_DELTA,
                    coverageSeconds = calculated.coverageSeconds,
                    coverageSecondsExact = calculated.coverageSecondsExact,
                    coverageRatio = coverage,
                    observedEnergyKwh = calculated.energyKwh,
                    qualityReason = "energy_remaining_delta"
                )
            }
        }
        // 3. Fallback to SOC drop percentage * usable battery capacity estimate
        if (startSoc != null && endSoc != null && startSoc in 0..100 && endSoc in 0..100) {
            val deltaSoc = startSoc - endSoc
            if (deltaSoc == 0) {
                if (calculated.energyKwh != null && (coverage ?: 0.0) >= 0.85) {
                    val energy = calculated.energyKwh
                    return DriveEnergyEstimate(
                        energyKwh = energy,
                        efficiencyWhKm = efficiency(energy, distanceKm),
                        source = DriveEnergySource.POWER_SAMPLES,
                        coverageSeconds = calculated.coverageSeconds,
                        coverageSecondsExact = calculated.coverageSecondsExact,
                        coverageRatio = coverage,
                        observedEnergyKwh = calculated.energyKwh,
                        qualityReason = "power_integral_short_trip"
                    )
                }
                // SOC did not change across this window; integer 1% quantization cannot resolve short trips.
                // Do not falsely report as 0.0 kWh (zero consumption).
                return DriveEnergyEstimate(
                    energyKwh = null,
                    efficiencyWhKm = null,
                    source = DriveEnergySource.UNAVAILABLE,
                    coverageSeconds = calculated.coverageSeconds,
                    coverageSecondsExact = calculated.coverageSecondsExact,
                    coverageRatio = coverage,
                    observedEnergyKwh = calculated.energyKwh,
                    qualityReason = "soc_unchanged_resolution_limit"
                )
            }
            if (deltaSoc in -30..100) {
                val capacity = batteryCapacityKwh?.takeIf { it > 0.0 && it.isFinite() }
                    ?: resolveBatteryCapacity(model, trimBadging)
                val estimateKwh = (deltaSoc.toDouble() / 100.0) * capacity
                if (estimateKwh.isFinite()) {
                    return DriveEnergyEstimate(
                        energyKwh = estimateKwh,
                        efficiencyWhKm = efficiency(estimateKwh, distanceKm),
                        source = DriveEnergySource.SOC_CAPACITY_ESTIMATE,
                        coverageSeconds = calculated.coverageSeconds,
                        coverageSecondsExact = calculated.coverageSecondsExact,
                        coverageRatio = coverage,
                        observedEnergyKwh = calculated.energyKwh,
                        qualityReason = "soc_capacity_estimate"
                    )
                }
            }
        }
        return DriveEnergyEstimate(
            energyKwh = null,
            efficiencyWhKm = null,
            source = DriveEnergySource.UNAVAILABLE,
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

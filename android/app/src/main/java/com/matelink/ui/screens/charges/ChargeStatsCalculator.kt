package com.matelink.ui.screens.charges

import com.matelink.data.api.models.ChargeDetail

enum class ChargeType { AC, DC, UNKNOWN }

object ChargeStatsCalculator {

    fun calculateStats(detail: ChargeDetail): ChargeDetailStats {
        val points = detail.chargePoints ?: emptyList()

        // Power stats
        val powers = points.mapNotNull { it.chargerPower }
        val powerMax = powers.maxOrNull()
        val powerMin = powers.minOrNull()
        val powerAvg = powers.takeIf { it.isNotEmpty() }?.average()

        // Voltage stats
        val voltages = points.mapNotNull { it.chargerVoltage }
        val voltageMax = voltages.maxOrNull()
        val voltageMin = voltages.minOrNull()
        val voltageAvg = voltages.takeIf { it.isNotEmpty() }?.average()

        // Current stats
        val currents = points.mapNotNull { it.chargerCurrent }
        val currentMax = currents.maxOrNull()
        val currentMin = currents.minOrNull()
        val currentAvg = currents.takeIf { it.isNotEmpty() }?.average()

        // Temperature stats
        val temps = points.mapNotNull { it.outsideTemp }
        val tempMax = temps.maxOrNull() ?: detail.outsideTempAvg
        val tempMin = temps.minOrNull() ?: detail.outsideTempAvg
        val tempAvg = temps.takeIf { it.isNotEmpty() }?.average() ?: detail.outsideTempAvg

        // Battery stats
        val batteryLevels = points.mapNotNull { it.batteryLevel }
        val batteryStart = batteryLevels.firstOrNull() ?: detail.startBatteryLevel
        val batteryEnd = batteryLevels.lastOrNull() ?: detail.currentOrEndBatteryLevel
        val batteryAdded = if (batteryStart != null && batteryEnd != null) {
            batteryEnd - batteryStart
        } else {
            null
        }

        // Energy stats
        val energyAdded = detail.chargeEnergyAdded?.takeIf { it.isFinite() && it >= 0.0 }
        val energyUsed = detail.chargeEnergyUsed?.takeIf { it.isFinite() && it >= 0.0 }
        val efficiency = if (energyAdded != null && energyUsed != null && energyUsed > 0.0) {
            (energyAdded / energyUsed * 100.0).takeIf { it.isFinite() && energyAdded <= energyUsed }
        } else {
            null
        }

        return ChargeDetailStats(
            powerMax = powerMax,
            powerMin = powerMin,
            powerAvg = powerAvg,
            voltageMax = voltageMax,
            voltageMin = voltageMin,
            voltageAvg = voltageAvg,
            currentMax = currentMax,
            currentMin = currentMin,
            currentAvg = currentAvg,
            tempMax = tempMax,
            tempMin = tempMin,
            tempAvg = tempAvg,
            batteryStart = batteryStart,
            batteryEnd = batteryEnd,
            batteryAdded = batteryAdded,
            energyAdded = energyAdded,
            energyUsed = energyUsed,
            efficiency = efficiency,
            durationMin = detail.durationMin?.takeIf { it >= 0 },
            cost = detail.cost
        )
    }

    /** Resolve charging type only from explicit, non-conflicting evidence. */
    fun detectChargeType(detail: ChargeDetail): ChargeType {
        val points = detail.chargePoints.orEmpty()
        val details = points.mapNotNull { it.chargerDetails }
        val explicitFast = details.mapNotNull { it.fastChargerPresent }.distinct()
        if (explicitFast.size > 1) return ChargeType.UNKNOWN
        val phases = details.mapNotNull { it.chargerPhases }.distinct()
        if (phases.any { it < 0 || it > 3 }) return ChargeType.UNKNOWN
        if (phases.any { it == 0 } && phases.any { it > 0 }) return ChargeType.UNKNOWN
        explicitFast.singleOrNull()?.let { explicit ->
            if ((explicit && phases.any { it > 0 }) || (!explicit && phases.any { it == 0 })) {
                return ChargeType.UNKNOWN
            }
            return if (explicit) ChargeType.DC else ChargeType.AC
        }
        return when {
            phases.any { it == 0 } -> ChargeType.DC
            phases.any { it in 1..3 } -> ChargeType.AC
            else -> ChargeType.UNKNOWN
        }
    }

    /** Compatibility projection for legacy UI callers; UNKNOWN is never AC evidence. */
    fun detectDcCharge(detail: ChargeDetail): Boolean = detectChargeType(detail) == ChargeType.DC
}

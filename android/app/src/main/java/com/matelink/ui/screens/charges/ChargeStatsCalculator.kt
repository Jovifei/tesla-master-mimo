package com.matelink.ui.screens.charges

import com.matelink.data.api.models.ChargeDetail

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

    /**
     * Detect if this is a DC charge using Teslamate's logic:
     * DC charging has charger_phases = 0 or null (bypasses onboard charger)
     * AC charging has charger_phases = 1 or 2 (for triphasic line)
     */
    fun detectDcCharge(detail: ChargeDetail): Boolean {
        val points = detail.chargePoints ?: return false
        val phases = points.mapNotNull { it.chargerDetails?.chargerPhases }
        val modePhases = phases.filter { it > 0 }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return modePhases == null
    }
}

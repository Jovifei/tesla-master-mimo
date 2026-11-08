package com.matelink.ui.screens.charges

import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargePoint
import com.matelink.util.parseIsoInstant
import java.time.Instant
import kotlin.math.roundToInt

enum class ChargeType { AC, DC, UNKNOWN }

internal data class QualifiedChargeSeries(
    val power: List<Pair<Instant, Double>>,
    val voltage: List<Pair<Instant, Double>>,
    val current: List<Pair<Instant, Double>>,
    val temperature: List<Pair<Instant, Double>>,
    val soc: List<Pair<Instant, Int>>
)

object ChargeStatsCalculator {

    private fun <T> uniqueSamples(
        points: List<Pair<Instant, ChargePoint>>,
        value: (ChargePoint) -> T?, valid: (T) -> Boolean
    ): List<Pair<Instant, T>> = points.mapNotNull { (at, point) ->
        value(point)?.takeIf(valid)?.let { at to it }
    }.groupBy { it.first }.mapNotNull { (at, samples) ->
        samples.map { it.second }.distinct().singleOrNull()?.let { at to it }
    }.sortedBy { it.first }

    /** One bounded, sorted admission rule for statistics and rendered curves. */
    internal fun project(detail: ChargeDetail): QualifiedChargeSeries {
        val points = detail.chargePoints ?: emptyList()
        val start = parseIsoInstant(detail.startDate)
        val end = parseIsoInstant(detail.endDate)
        val timed = if (start != null && end != null && !end.isBefore(start)) {
            points.mapNotNull { point ->
                parseIsoInstant(point.date)?.takeIf { !it.isBefore(start) && !it.isAfter(end) }
                    ?.let { it to point }
            }
        } else emptyList()

        return QualifiedChargeSeries(
            power = uniqueSamples(timed, { it.chargerPowerValue }) { it.isFinite() && it >= 0.0 },
            voltage = uniqueSamples(timed, { it.chargerVoltageValue }) { it.isFinite() && it >= 0.0 },
            current = uniqueSamples(timed, { it.chargerCurrentValue }) { it.isFinite() && it >= 0.0 },
            temperature = uniqueSamples(timed, { it.outsideTemp }) { it.isFinite() },
            soc = uniqueSamples(timed, { it.batteryLevel }) { it in 0..100 }
        )
    }

    fun calculateStats(detail: ChargeDetail): ChargeDetailStats {
        val points = detail.chargePoints ?: emptyList()
        val start = parseIsoInstant(detail.startDate)
        val end = parseIsoInstant(detail.endDate)
        val series = project(detail)

        // Observed-sample extrema and arithmetic sample mean, never a time-weighted mean.
        val powers = series.power
        val powerMax = powers.maxOfOrNull { it.second }?.roundToInt()
        val powerMin = powers.minOfOrNull { it.second }?.roundToInt()
        val powerAvg = powers.takeIf { it.isNotEmpty() }?.map { it.second }?.average()

        val voltages = series.voltage
        val voltageMax = voltages.maxOfOrNull { it.second }?.roundToInt()
        val voltageMin = voltages.minOfOrNull { it.second }?.roundToInt()
        val voltageAvg = voltages.takeIf { it.isNotEmpty() }?.map { it.second }?.average()

        val currents = series.current
        val currentMax = currents.maxOfOrNull { it.second }?.roundToInt()
        val currentMin = currents.minOfOrNull { it.second }?.roundToInt()
        val currentAvg = currents.takeIf { it.isNotEmpty() }?.map { it.second }?.average()

        val temps = series.temperature
        val tempMax = temps.maxOfOrNull { it.second }
        val tempMin = temps.minOfOrNull { it.second }
        val tempAvg = temps.takeIf { it.isNotEmpty() }?.map { it.second }?.average()
            ?: detail.outsideTempAvg?.takeIf { value ->
                value.isFinite() && points.none { it.outsideTemp != null }
            }

        val batteryLevels = series.soc
        val summaryOnly = batteryLevels.isEmpty() && points.none { it.batteryLevel != null }
        val first = batteryLevels.firstOrNull()
        val last = batteryLevels.lastOrNull()
        // These values are the first and last unambiguous observations. The
        // summary fields have no timestamps and never prove exact boundaries.
        val batteryStart = when {
            batteryLevels.size >= 2 -> first?.second
            first != null && (first.first != end || start == end) -> first.second
            summaryOnly -> detail.startBatteryLevel?.takeIf { it in 0..100 }
            else -> null
        }
        val batteryEnd = when {
            batteryLevels.size >= 2 -> last?.second
            last != null && last.first == end -> last.second
            summaryOnly -> detail.endBatteryLevel?.takeIf { it in 0..100 }
            else -> null
        }
        val batteryAdded = if (batteryStart != null && batteryEnd != null) {
            batteryEnd - batteryStart
        } else {
            null
        }

        // Energy stats
        val energyAdded = detail.batteryInputKwh
        val energyUsed = detail.inputEnergyKwh
        // Two legacy scalars do not prove a shared AC measurement window.
        val efficiency = com.matelink.domain.analytics.qualifiedAcEnergyBalance(
            detail.energyContract, detail.startDate, detail.endDate, detail.chargeType
        )?.efficiencyPercent

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
        val contract = detail.energyContract
        val verifiedMode = if (contract?.version == 1 &&
            contract.chargeModeEvidence == "observed_boundary_modes_no_conflict") when (contract.chargeMode) {
            "ac" -> ChargeType.AC
            "dc" -> ChargeType.DC
            else -> ChargeType.UNKNOWN
        } else ChargeType.UNKNOWN
        if (contract != null && verifiedMode == ChargeType.UNKNOWN) return ChargeType.UNKNOWN
        if (contract != null && detail.chargeType != null &&
            detail.chargeType != contract.chargeMode) return ChargeType.UNKNOWN
        val points = detail.chargePoints.orEmpty()
        val details = points.mapNotNull { it.chargerDetails }
        val explicitFast = details.mapNotNull { it.fastChargerPresent }.distinct()
        if (explicitFast.size > 1) return ChargeType.UNKNOWN
        val phases = details.mapNotNull { it.chargerPhases }.distinct()
        if (phases.any { it < 0 || it > 3 }) return ChargeType.UNKNOWN
        if (phases.any { it == 0 } && phases.any { it > 0 }) return ChargeType.UNKNOWN
        val inferred = if (explicitFast.isNotEmpty()) {
            val explicit = explicitFast.single()
            if ((explicit && phases.any { it > 0 }) || (!explicit && phases.any { it == 0 })) {
                return ChargeType.UNKNOWN
            }
            if (explicit) ChargeType.DC else ChargeType.AC
        } else when {
            phases.any { it == 0 } -> ChargeType.DC
            phases.any { it in 1..3 } -> ChargeType.AC
            else -> ChargeType.UNKNOWN
        }
        if (contract != null) {
            if (inferred != ChargeType.UNKNOWN && inferred != verifiedMode) return ChargeType.UNKNOWN
            return verifiedMode
        }
        return inferred
    }

    /** Compatibility projection for legacy UI callers; UNKNOWN is never AC evidence. */
    fun detectDcCharge(detail: ChargeDetail): Boolean = detectChargeType(detail) == ChargeType.DC
}

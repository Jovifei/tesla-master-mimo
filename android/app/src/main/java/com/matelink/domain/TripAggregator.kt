package com.matelink.domain

import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary
import com.matelink.domain.analytics.EfficiencySample
import com.matelink.domain.analytics.calculateWeightedEfficiency
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.domain.model.Trip
import com.matelink.util.parseIsoDateTime
import com.matelink.util.parseIsoInstant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Assembles a Trip domain object from its constituent drive and charge legs.
 *
 * Shared by TripDetector (for newly detected trips) and TripRepository (for
 * rebuilding persisted SavedTrips from their leg references). The aggregator
 * itself enforces no detection rules — callers decide whether a given leg set
 * is eligible (the detector applies the 300 km / 2-drive / 1-charge thresholds).
 */
object TripAggregator {

    /** Production boundary for nullable receipt-qualified trip metrics. */
    fun buildQualifiedTrip(drives: List<DriveSummary>, charges: List<ChargeSummary>, name: String? = null): Trip? {
        val trip = buildTrip(drives, charges, name) ?: return null
        val driveData = trip.drives.map { it.toAnalysisDriveData() }
        val chargeData = trip.charges.map { it.toAnalysisChargeData() }
        val distances = driveData.mapNotNull { it.distanceKm?.takeIf { v -> v.isFinite() && v >= 0.0 } }
        val durations = driveData.mapNotNull { it.durationMin?.takeIf { v -> v >= 0 } }
        val energy = driveData.mapNotNull { it.netEnergyKwh?.takeIf(Double::isFinite) }
        val charged = chargeData.mapNotNull { it.batteryInputKwh?.takeIf { v -> v.isFinite() && v >= 0.0 } }
        fun sum(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite)
        val duration = durations.takeIf { it.isNotEmpty() }?.sumOf { it.toLong() }
            ?.takeIf { it <= Int.MAX_VALUE }?.toInt()
        val weighted = calculateWeightedEfficiency(driveData.map {
            EfficiencySample(it.distanceKm, it.netEnergyKwh)
        })
        val firstCharge = chargeData.firstOrNull()?.takeIf { it.startDate == trip.startDate }
        val lastCharge = chargeData.lastOrNull()?.takeIf { it.endDate == trip.endDate }
        return trip.copy(
            totalDistance = sum(distances) ?: 0.0, totalDrivingDurationMin = duration ?: 0,
            totalEnergyConsumed = sum(energy), totalEnergyCharged = sum(charged) ?: 0.0,
            avgEfficiency = weighted.efficiencyWhKm, energySampleCount = energy.size,
            efficiencySampleCount = weighted.sampleCount,
            efficiencyRecordCoveragePercent = weighted.coveragePercent,
            efficiencyDistanceCoveragePercent = weighted.distanceCoveragePercent,
            knownDistanceKm = sum(distances), distanceSampleCount = distances.size,
            knownDrivingDurationMin = duration, durationSampleCount = durations.size,
            knownChargeEnergyKwh = sum(charged), chargeEnergySampleCount = charged.size,
            observedStartBatteryLevel = (if (firstCharge != null) firstCharge.batteryDetails?.startBatteryLevel
                else driveData.firstOrNull()?.batteryDetails?.startBatteryLevel)?.takeIf { it in 0..100 },
            observedEndBatteryLevel = (if (lastCharge != null) lastCharge.batteryDetails?.endBatteryLevel
                else driveData.lastOrNull()?.batteryDetails?.endBatteryLevel)?.takeIf { it in 0..100 },
            observedMaxSpeedKmh = driveData.mapNotNull { it.speedMaxKmh?.takeIf { v -> v.isFinite() && v >= 0.0 }
                ?.toInt() }.maxOrNull()
        )
    }

    /**
     * Build a Trip from chronologically-ordered legs.
     * Returns null if [drives] is empty (a Trip's start/end metadata comes from the first/last drive).
     * [name] is carried through to [Trip.name] so saved trips' custom names reach the UI.
     */
    fun buildTrip(
        drives: List<DriveSummary>,
        charges: List<ChargeSummary>,
        name: String? = null
    ): Trip? {
        if (drives.isEmpty()) return null

        val orderedDrives = drives.sortedWith { a, b -> compareDates(a.startDate, b.startDate) }
        val orderedCharges = charges.sortedWith { a, b -> compareDates(a.startDate, b.startDate) }
        val startDate = (orderedDrives.map { it.startDate } + orderedCharges.map { it.startDate })
            .minWithOrNull(::compareDates)!!
        val endDate = (orderedDrives.map { it.endDate } + orderedCharges.map { it.endDate })
            .maxWithOrNull(::compareDates)!!

        val totalDistance = drives.sumOf { it.distance }
        val totalDrivingMin = drives.sumOf { it.durationMin }
        val firstStart = parseIsoInstant(startDate)
        val lastEnd = parseIsoInstant(endDate)
        val totalMin = if (firstStart != null && lastEnd != null) {
            ChronoUnit.MINUTES.between(firstStart, lastEnd).toInt()
        } else totalDrivingMin
        val weightedEnergy = calculateWeightedEfficiency(
            drives.map { EfficiencySample(distanceKm = it.distance, energyKwh = it.energyConsumed) }
        )
        // A known energy receipt remains meaningful even when its distance is unknown or zero.
        // Only efficiency requires a qualified energy/distance pair.
        val knownEnergy = drives.mapNotNull { it.energyConsumed?.takeIf(Double::isFinite) }
        val totalEnergyConsumed = knownEnergy.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite)
        val totalEnergyCharged = charges.sumOf { it.energyAdded }
        val costs = charges.mapNotNull { it.cost }
        val totalCost = if (costs.isNotEmpty()) costs.sum() else null
        val maxSpeed = drives.maxOf { it.speedMax }
        val avgEfficiency = weightedEnergy.efficiencyWhKm

        return Trip(
            drives = orderedDrives,
            charges = orderedCharges,
            totalDistance = totalDistance,
            totalDrivingDurationMin = totalDrivingMin,
            totalDurationMin = totalMin,
            totalEnergyConsumed = totalEnergyConsumed,
            totalEnergyCharged = totalEnergyCharged,
            totalChargeCost = totalCost,
            avgEfficiency = avgEfficiency,
            maxSpeed = maxSpeed,
            startAddress = orderedCharges.firstOrNull()?.takeIf { it.startDate == startDate }?.address
                ?: orderedDrives.first().startAddress,
            endAddress = orderedCharges.lastOrNull()?.takeIf { it.endDate == endDate }?.address
                ?: orderedDrives.last().endAddress,
            startDate = startDate,
            endDate = endDate,
            startBatteryLevel = orderedCharges.firstOrNull()?.takeIf { it.startDate == startDate }?.startBatteryLevel
                ?: orderedDrives.first().startBatteryLevel,
            endBatteryLevel = orderedCharges.lastOrNull()?.takeIf { it.endDate == endDate }?.endBatteryLevel
                ?: orderedDrives.last().endBatteryLevel,
            name = name,
            efficiencySampleCount = weightedEnergy.sampleCount,
            efficiencyRecordCoveragePercent = weightedEnergy.coveragePercent,
            efficiencyDistanceCoveragePercent = weightedEnergy.distanceCoveragePercent,
            energySampleCount = knownEnergy.size
        )
    }

    private fun parseDateTime(dateStr: String): LocalDateTime? =
        parseIsoDateTime(dateStr)

    private fun compareDates(a: String, b: String): Int {
        val first = parseIsoInstant(a)
        val second = parseIsoInstant(b)
        return if (first != null && second != null) first.compareTo(second) else a.compareTo(b)
    }
}

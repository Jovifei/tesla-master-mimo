package com.matelink.domain.model

import androidx.compose.runtime.Immutable
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveSummary

/**
 * A highway/road trip: a sequence of drive -> DC charge -> drive [-> DC charge -> drive ...]
 * Computed from existing drive and charge data, not persisted in the database.
 */
@Immutable
data class Trip(
    val drives: List<DriveSummary>,
    val charges: List<ChargeSummary>,
    val totalDistance: Double,
    val totalDrivingDurationMin: Int,
    val totalDurationMin: Int,
    /** Sum of known finite net kWh, independent of distance; null for no known energy or sum overflow. */
    val totalEnergyConsumed: Double?,
    val totalEnergyCharged: Double,
    val totalChargeCost: Double?,
    /** Distance-weighted net Wh/km, using only finite energy and finite positive distance pairs. */
    val avgEfficiency: Double?,
    val maxSpeed: Int,
    val startAddress: String,
    val endAddress: String,
    val startDate: String,
    val endDate: String,
    val startBatteryLevel: Int,
    val endBatteryLevel: Int,
    /** User-chosen name. Null means fall back to the default "startCity → endCity" label. */
    val name: String? = null,
    /** Optional derived coverage for older callers; these fields are never persisted. */
    val efficiencySampleCount: Int? = null,
    val efficiencyRecordCoveragePercent: Double? = null,
    /** Qualified distance divided by all known finite positive distance, not unknown distance. */
    val efficiencyDistanceCoveragePercent: Double? = null,
    /** Known finite energy receipts, independent of efficiency's valid-distance requirement. */
    val energySampleCount: Int? = null,
    /** Stable saved-row identity is independent of a drive-set fingerprint. */
    val savedTripId: Long? = null,
    val knownDistanceKm: Double? = totalDistance,
    val distanceSampleCount: Int = drives.size,
    val knownDrivingDurationMin: Int? = totalDrivingDurationMin,
    val durationSampleCount: Int = drives.size,
    val knownChargeEnergyKwh: Double? = totalEnergyCharged,
    val chargeEnergySampleCount: Int = charges.size,
    val observedStartBatteryLevel: Int? = startBatteryLevel,
    val observedEndBatteryLevel: Int? = endBatteryLevel,
    val observedMaxSpeedKmh: Int? = maxSpeed,
    val expectedDriveCount: Int = drives.size,
    val expectedChargeCount: Int = charges.size
)

package com.matelink.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Drive summary data from /drives list endpoint.
 * Contains all fields needed for Quick Stats.
 */
@Immutable
@Entity(
    tableName = "drives_summary",
    primaryKeys = ["carId", "driveId"],
    indices = [
        Index(value = ["carId"]),
        Index(value = ["carId", "startDate"])
    ]
)
data class DriveSummary(
    val driveId: Int,
    val carId: Int,

    // Timing
    val startDate: String,
    val endDate: String,
    val durationMin: Int,

    // Location
    val startAddress: String,
    val endAddress: String,

    // Distance & Speed
    val distance: Double,           // km
    val speedMax: Int,              // km/h
    val speedAvg: Int,              // km/h

    // Power
    val powerMax: Int,              // kW (acceleration)
    val powerMin: Int,              // kW (regen, negative)

    // Battery
    val startBatteryLevel: Int,
    val endBatteryLevel: Int,

    // Temperature (averages from list endpoint)
    val outsideTempAvg: Double?,
    val insideTempAvg: Double?,

    // Energy
    val energyConsumed: Double?,    // kWh

    // Computed efficiency (Wh/km)
    val efficiency: Double?,

    // Provenance for calculated/API energy shown in history.
    val energySource: String? = null,
    @ColumnInfo(defaultValue = "0")
    val energyCoverageSeconds: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val energyCoverageRatio: Double = 0.0,

    /** Exact nullable API fields; legacy scalar columns cannot represent their provenance. */
    val apiEvidence: String? = null
)

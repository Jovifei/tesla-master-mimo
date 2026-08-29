package com.matelink.data.local.entity

import androidx.room.Entity

/** A point-in-time TPMS observation. Unavailable wheels remain null. */
@Entity(
    tableName = "tpms_pressure_samples",
    primaryKeys = ["carId", "observedAt"]
)
data class TpmsPressureSample(
    val carId: Int,
    val observedAt: Long,
    val pressureFl: Double? = null,
    val pressureFr: Double? = null,
    val pressureRl: Double? = null,
    val pressureRr: Double? = null,
    val outsideTempC: Double? = null
)

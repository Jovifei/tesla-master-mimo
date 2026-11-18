package com.matelink.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One locally observed SOC point per car and hour. The hour bucket is UTC so
 * adjacent samples from the existing 15-minute status poll collapse into a
 * single point without inventing values. The exact observation time is kept
 * for display and for preferring the newest reading inside the hour.
 */
@Entity(
    tableName = "soc_samples",
    primaryKeys = ["carId", "hourEpochMs"],
    indices = [Index(value = ["carId", "hourEpochMs"])]
)
data class SocSample(
    val carId: Int,
    val hourEpochMs: Long,
    val observedAtEpochMs: Long,
    val socPercent: Int,
    val source: String = "status"
)
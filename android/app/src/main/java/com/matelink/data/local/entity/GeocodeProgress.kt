package com.matelink.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks geocoding progress per car for UI display.
 *
 * Separate from queue because queue items are removed when processed,
 * but we need to track "total ever enqueued" for progress percentage.
 */
@Entity(tableName = "geocode_progress")
data class GeocodeProgress(
    @PrimaryKey
    val carId: Int,
    val totalLocations: Int = 0,      // Total unique locations enqueued
    val processedLocations: Int = 0,  // Successfully geocoded
    val lastUpdatedAt: Long = 0
)

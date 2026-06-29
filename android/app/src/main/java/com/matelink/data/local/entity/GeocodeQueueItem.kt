package com.matelink.data.local.entity

import androidx.room.Entity

/**
 * Queue item for pending geocoding requests.
 *
 * Locations are enqueued during sync and processed in the background
 * by GeocodeWorker at Nominatim's rate limit (1 req/sec).
 */
@Entity(
    tableName = "geocode_queue",
    primaryKeys = ["gridLat", "gridLon"]
)
data class GeocodeQueueItem(
    val gridLat: Int,           // Grid coordinate
    val gridLon: Int,           // Grid coordinate
    val carId: Int,             // For progress tracking per car
    val latitude: Double,       // Original precise coordinate for API call
    val longitude: Double,
    val addedAt: Long,
    val attempts: Int = 0,      // For retry logic
    val lastAttemptAt: Long? = null
)

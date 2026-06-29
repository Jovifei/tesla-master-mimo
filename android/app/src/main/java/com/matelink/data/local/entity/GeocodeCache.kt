package com.matelink.data.local.entity

import androidx.room.Entity

/**
 * Grid-based geocoding cache for location lookups.
 *
 * Uses a 0.01° grid (~1.1km at equator, ~0.7km at 45° latitude)
 * to cache geocoding results and avoid repeated API calls.
 */
@Entity(
    tableName = "geocode_cache",
    primaryKeys = ["gridLat", "gridLon"]
)
data class GeocodeCache(
    val gridLat: Int,           // latitude * 100 (0.01° grid)
    val gridLon: Int,           // longitude * 100
    val countryCode: String?,   // ISO 3166-1 alpha-2 (e.g., "IT")
    val countryName: String?,   // "Italy"
    val regionName: String?,    // "Lazio" (state/region)
    val city: String?,          // "Rome"
    val cachedAt: Long          // timestamp for potential cache invalidation
)

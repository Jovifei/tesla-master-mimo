package com.matelink.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.matelink.data.local.entity.GeocodeProgress
import kotlinx.coroutines.flow.Flow

@Dao
interface GeocodeProgressDao {

    @Query("SELECT * FROM geocode_progress WHERE carId = :carId")
    suspend fun get(carId: Int): GeocodeProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: GeocodeProgress)

    // Increment total when new locations are enqueued
    @Query("""
        UPDATE geocode_progress
        SET totalLocations = totalLocations + :count,
            lastUpdatedAt = :timestamp
        WHERE carId = :carId
    """)
    suspend fun incrementTotal(carId: Int, count: Int, timestamp: Long)

    // Increment processed when geocoding succeeds
    @Query("""
        UPDATE geocode_progress
        SET processedLocations = processedLocations + 1,
            lastUpdatedAt = :timestamp
        WHERE carId = :carId
    """)
    suspend fun incrementProcessed(carId: Int, timestamp: Long)

    // Flow-based query for real-time progress bar
    @Query("SELECT * FROM geocode_progress WHERE carId = :carId")
    fun observe(carId: Int): Flow<GeocodeProgress?>

    // Reset progress (for full resync)
    @Query("UPDATE geocode_progress SET totalLocations = 0, processedLocations = 0 WHERE carId = :carId")
    suspend fun reset(carId: Int)

    // Delete progress record
    @Query("DELETE FROM geocode_progress WHERE carId = :carId")
    suspend fun delete(carId: Int)

    // Sync progress with actual cache count (fixes stale data)
    // Sets both total and processed to cachedCount, marking geocoding as complete
    @Query("""
        UPDATE geocode_progress
        SET totalLocations = :cachedCount,
            processedLocations = :cachedCount,
            lastUpdatedAt = :timestamp
    """)
    suspend fun syncWithCache(cachedCount: Int, timestamp: Long = System.currentTimeMillis())
}

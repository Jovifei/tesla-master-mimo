package com.matelink.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.matelink.data.local.entity.SocSample
import kotlinx.coroutines.flow.Flow

@Dao
interface SocSampleDao {
    @Query("SELECT * FROM soc_samples WHERE carId = :carId AND hourEpochMs >= :since ORDER BY hourEpochMs ASC")
    fun observeSince(carId: Int, since: Long): Flow<List<SocSample>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(sample: SocSample): Long

    @Query(
        "UPDATE soc_samples SET observedAtEpochMs = :observedAtEpochMs, socPercent = :socPercent, source = :source " +
            "WHERE carId = :carId AND hourEpochMs = :hourEpochMs AND observedAtEpochMs < :observedAtEpochMs"
    )
    suspend fun updateIfNewer(
        carId: Int,
        hourEpochMs: Long,
        observedAtEpochMs: Long,
        socPercent: Int,
        source: String
    ): Int

    @Query("DELETE FROM soc_samples WHERE carId = :carId AND hourEpochMs < :cutoffEpochMs")
    suspend fun pruneBefore(carId: Int, cutoffEpochMs: Long)
}
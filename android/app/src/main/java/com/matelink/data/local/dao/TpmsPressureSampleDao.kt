package com.matelink.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.matelink.data.local.entity.TpmsPressureSample

@Dao
interface TpmsPressureSampleDao {
    @Upsert
    suspend fun upsert(sample: TpmsPressureSample)

    @Upsert
    suspend fun upsertAll(samples: List<TpmsPressureSample>)

    @Query(
        "SELECT * FROM tpms_pressure_samples " +
            "WHERE carId = :carId AND observedAt >= :from AND observedAt < :to " +
            "ORDER BY observedAt ASC"
    )
    suspend fun getInRange(carId: Int, from: Long, to: Long): List<TpmsPressureSample>

    @Query("DELETE FROM tpms_pressure_samples WHERE carId = :carId AND observedAt < :cutoff")
    suspend fun deleteOlderThan(carId: Int, cutoff: Long): Int
}

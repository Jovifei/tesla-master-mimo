package com.matelink.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import com.matelink.data.repository.mergeTpmsSampleAtSameInstant
import com.matelink.data.local.entity.TpmsPressureSample

@Dao
interface TpmsPressureSampleDao {
    @Upsert
    suspend fun upsert(sample: TpmsPressureSample)

    @Upsert
    suspend fun upsertAll(samples: List<TpmsPressureSample>)

    @Query("SELECT * FROM tpms_pressure_samples WHERE carId = :carId AND observedAt = :at LIMIT 1")
    suspend fun getAt(carId: Int, at: Long): TpmsPressureSample?

    @Transaction
    suspend fun upsertMergingObservation(sample: TpmsPressureSample) {
        val previous = getAt(sample.carId, sample.observedAt)
        upsert(mergeTpmsSampleAtSameInstant(previous, sample))
    }

    // At most four prior wheel-bearing rows (bounded regardless of history size).
    @Query("""
        SELECT * FROM tpms_pressure_samples
        WHERE carId = :carId AND provenance = 'provider_observation'
        AND observedAt >= :from AND observedAt < :before
        AND observedAt IN (
          SELECT MAX(observedAt) FROM tpms_pressure_samples
            WHERE carId = :carId AND provenance = 'provider_observation'
            AND observedAt >= :from AND observedAt < :before AND pressureFl IS NOT NULL
          UNION
          SELECT MAX(observedAt) FROM tpms_pressure_samples
            WHERE carId = :carId AND provenance = 'provider_observation'
            AND observedAt >= :from AND observedAt < :before AND pressureFr IS NOT NULL
          UNION
          SELECT MAX(observedAt) FROM tpms_pressure_samples
            WHERE carId = :carId AND provenance = 'provider_observation'
            AND observedAt >= :from AND observedAt < :before AND pressureRl IS NOT NULL
          UNION
          SELECT MAX(observedAt) FROM tpms_pressure_samples
            WHERE carId = :carId AND provenance = 'provider_observation'
            AND observedAt >= :from AND observedAt < :before AND pressureRr IS NOT NULL
        ) ORDER BY observedAt ASC
    """)
    suspend fun latestPerWheel(carId: Int, from: Long, before: Long): List<TpmsPressureSample>

    @Query(
        "SELECT * FROM tpms_pressure_samples " +
            "WHERE carId = :carId AND observedAt >= :from AND observedAt < :to " +
            "ORDER BY observedAt ASC"
    )
    suspend fun getInRange(carId: Int, from: Long, to: Long): List<TpmsPressureSample>

    @Query("DELETE FROM tpms_pressure_samples WHERE provenance = 'provider_observation' AND carId = :carId AND observedAt < :cutoff")
    suspend fun deleteOlderThan(carId: Int, cutoff: Long): Int
}

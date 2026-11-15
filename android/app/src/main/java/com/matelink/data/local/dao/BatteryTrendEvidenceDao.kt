package com.matelink.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.matelink.data.local.entity.BatteryTrendEvidence

@Dao
interface BatteryTrendEvidenceDao {
    @Query("SELECT * FROM battery_trend_evidence WHERE scopeFingerprint = :scope AND localHistoryCarId = :carId")
    suspend fun allFor(scope: String, carId: Int): List<BatteryTrendEvidence>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<BatteryTrendEvidence>)
}

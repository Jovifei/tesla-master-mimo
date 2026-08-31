package com.matelink.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.matelink.data.local.entity.ChargeCostOverride
import kotlinx.coroutines.flow.Flow

@Dao
interface ChargeCostOverrideDao {
    @Query("SELECT * FROM charge_cost_overrides ORDER BY carId, chargeId")
    fun observeAll(): Flow<List<ChargeCostOverride>>

    @Query("SELECT * FROM charge_cost_overrides")
    suspend fun getAll(): List<ChargeCostOverride>

    @Query("SELECT manualTotalAmount FROM charge_cost_overrides WHERE carId = :carId AND chargeId = :chargeId")
    suspend fun getAmount(carId: Int, chargeId: Int): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: ChargeCostOverride)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(overrides: List<ChargeCostOverride>)

    @Query("""
        INSERT OR IGNORE INTO charge_cost_overrides (carId, chargeId, manualTotalAmount)
        SELECT :targetCarId, chargeId, manualTotalAmount
        FROM charge_cost_overrides WHERE carId = :legacyCarId
    """)
    suspend fun copyFromLegacy(legacyCarId: Int, targetCarId: Int)

    @Query("DELETE FROM charge_cost_overrides WHERE carId = :carId AND chargeId = :chargeId")
    suspend fun delete(carId: Int, chargeId: Int)
}

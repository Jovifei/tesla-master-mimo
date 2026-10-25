package com.matelink.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.matelink.data.local.entity.ChargeCostOverride
import kotlinx.coroutines.flow.Flow

@Dao
interface ChargeCostOverrideDao {
    @Query("SELECT * FROM charge_cost_overrides ORDER BY carId, chargeId, sessionKey")
    fun observeAll(): Flow<List<ChargeCostOverride>>

    @Query("SELECT * FROM charge_cost_overrides")
    suspend fun getAll(): List<ChargeCostOverride>

    @Query("SELECT * FROM charge_cost_overrides WHERE carId = :carId AND chargeId = :chargeId AND sessionKey = :sessionKey")
    suspend fun getRecord(carId: Int, chargeId: Int, sessionKey: String): ChargeCostOverride?

    @Query("SELECT * FROM charge_cost_overrides WHERE carId = :carId AND chargeId = :chargeId")
    suspend fun getRecordsForCharge(carId: Int, chargeId: Int): List<ChargeCostOverride>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: ChargeCostOverride)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLegacyIfAbsent(overrides: List<ChargeCostOverride>)

    /** An edit or clear carries its base version. A stale write must not win by arriving last. */
    @androidx.room.Transaction
    suspend fun saveIfVersion(override: ChargeCostOverride, expectedVersion: Long): Boolean {
        val current = getRecord(override.carId, override.chargeId, override.sessionKey)
        if ((current?.version ?: 0L) != expectedVersion || expectedVersion == Long.MAX_VALUE) return false
        upsert(override.copy(version = expectedVersion + 1L))
        return true
    }

    @Query("""
        INSERT OR IGNORE INTO charge_cost_overrides (carId, chargeId, sessionKey, manualTotalAmount,
            currencyCode, sourceKind, sourceInstanceId, sourceRecordId, startInstant, endInstant, version, deleted)
        SELECT :targetCarId, chargeId, sessionKey, manualTotalAmount,
            currencyCode, sourceKind, sourceInstanceId, sourceRecordId, startInstant, endInstant, version, deleted
        FROM charge_cost_overrides WHERE carId = :legacyCarId
    """)
    suspend fun copyFromLegacy(legacyCarId: Int, targetCarId: Int)

}

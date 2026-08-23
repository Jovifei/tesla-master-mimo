package com.matelink.data.local

import com.matelink.data.local.dao.ChargeCostOverrideDao
import com.matelink.data.local.entity.ChargeCostOverride
import com.matelink.domain.analytics.chargeTotalOverrideKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed user charge-cost overrides.
 *
 * Existing DataStore JSON is migrated lazily on first use so upgrades preserve
 * values without requiring a destructive database migration.
 */
@Singleton
class ChargeCostOverrideStore @Inject constructor(
    private val dao: ChargeCostOverrideDao,
    private val settingsDataStore: SettingsDataStore
) {
    private val migrationMutex = Mutex()
    private var legacyMigrationChecked = false

    suspend fun ensureLegacyMigrated() {
        migrationMutex.withLock {
            if (legacyMigrationChecked) return
            if (!settingsDataStore.chargeTotalOverridesMigrated.first()) {
                val legacy = settingsDataStore.chargeTotalOverrides.first()
                val parsed = legacy.mapNotNull { (key, amount) ->
                    val ids = key.split(':', limit = 2)
                    val carId = ids.getOrNull(0)?.toIntOrNull()
                    val chargeId = ids.getOrNull(1)?.toIntOrNull()
                    if (carId == null || chargeId == null || !amount.isFinite() || amount < 0.0) {
                        null
                    } else {
                        ChargeCostOverride(carId, chargeId, amount)
                    }
                }
                if (parsed.isNotEmpty()) dao.upsertAll(parsed)
                settingsDataStore.markChargeTotalOverridesMigrated()
            }
            legacyMigrationChecked = true
        }
    }

    fun observeAll(): Flow<Map<String, Double>> = dao.observeAll().map { overrides ->
        overrides.associate { override ->
            chargeTotalOverrideKey(override.carId, override.chargeId) to override.manualTotalAmount
        }
    }

    suspend fun getAmount(carId: Int, chargeId: Int): Double? {
        ensureLegacyMigrated()
        return dao.getAmount(carId, chargeId)
    }

    suspend fun getAll(): Map<String, Double> {
        ensureLegacyMigrated()
        return dao.getAll().associate { override ->
            chargeTotalOverrideKey(override.carId, override.chargeId) to override.manualTotalAmount
        }
    }

    suspend fun save(carId: Int, chargeId: Int, amount: Double?) {
        ensureLegacyMigrated()
        if (amount == null) {
            dao.delete(carId, chargeId)
        } else {
            dao.upsert(ChargeCostOverride(carId, chargeId, amount))
        }
    }
}

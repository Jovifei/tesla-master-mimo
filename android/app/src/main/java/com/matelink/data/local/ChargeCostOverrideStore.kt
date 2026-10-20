package com.matelink.data.local

import com.matelink.data.local.dao.ChargeCostOverrideDao
import com.matelink.data.local.entity.ChargeCostOverride
import com.matelink.data.api.models.ChargeData
import com.matelink.data.model.Currency
import com.matelink.domain.analytics.chargeCostInstant
import com.matelink.domain.analytics.chargeCostSessionKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
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
                if (parsed.isNotEmpty()) dao.insertLegacyIfAbsent(parsed)
                settingsDataStore.markChargeTotalOverridesMigrated()
            }
            legacyMigrationChecked = true
        }
    }

    fun observeAllRecords(): Flow<List<ChargeCostOverride>> = flow {
        ensureLegacyMigrated()
        emitAll(dao.observeAll())
    }

    suspend fun getAllRecords(): List<ChargeCostOverride> {
        ensureLegacyMigrated()
        return readAllRecordsAfterMigration()
    }

    /** Call only after ensureLegacyMigrated(); safe to capture within a Room read transaction. */
    suspend fun readAllRecordsAfterMigration(): List<ChargeCostOverride> = dao.getAll()

    suspend fun getRecordsForCharge(carId: Int, chargeId: Int): List<ChargeCostOverride> {
        ensureLegacyMigrated()
        return dao.getRecordsForCharge(carId, chargeId)
    }

    /** Returns false if another edit or clear advanced this exact session's version. */
    suspend fun saveBound(
        carId: Int, charge: ChargeData, amount: Double?, currencyCode: String,
        expectedVersion: Long
    ): Boolean {
        ensureLegacyMigrated()
        if (charge.sourceRecordIdentity.conflicted) return false
        if (amount != null && (!amount.isFinite() || amount < 0.0)) return false
        val code = currencyCode.trim().uppercase(Locale.ROOT)
            .takeIf { value -> Currency.entries.any { it.code == value } }
        val key = chargeCostSessionKey(charge) + ":" + (code ?: "unverified")
        val current = dao.getRecord(carId, charge.chargeId, key)
        val record = ChargeCostOverride(
            carId = carId, chargeId = charge.chargeId,
            manualTotalAmount = amount ?: current?.manualTotalAmount ?: 0.0,
            sessionKey = key, currencyCode = code,
            sourceKind = charge.source?.takeIf(String::isNotBlank),
            sourceInstanceId = charge.sourceInstanceId?.takeIf(String::isNotBlank),
            sourceRecordId = charge.sourceRecordIdentity.value,
            startInstant = chargeCostInstant(charge.startDate)?.toString(),
            endInstant = chargeCostInstant(charge.endDate)?.toString(),
            deleted = amount == null
        )
        return dao.saveIfVersion(record, expectedVersion)
    }

}

package com.matelink.data.repository

import androidx.room.withTransaction
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.dao.ChargeCostOverrideDao
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.dao.LegacyHistoryArchiveDao
import com.matelink.data.local.entity.LegacyHistoryArchive
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

data class LegacyHistoryMigrationEligibility(
    val eligible: Boolean,
    val legacyDriveCount: Int,
    val legacyChargeCount: Int,
    val legacyMaxOdometer: Double?,
    val reason: LegacyHistoryMigrationBlockReason? = null
) {
    val legacyRecordCount: Int get() = legacyDriveCount + legacyChargeCount
}

enum class LegacyHistoryMigrationBlockReason {
    NO_LEGACY_ROWS,
    ARCHIVE_MARKER_UNAVAILABLE,
    FINGERPRINT_MISMATCH,
    MODEL_MISMATCH,
    ODOMETER_UNAVAILABLE,
    ODOMETER_INVALID,
    ODOMETER_ROLLBACK
}

fun evaluateLegacyHistoryMigration(
    legacyDriveCount: Int,
    legacyChargeCount: Int,
    legacyModel: String?,
    currentModel: String?,
    legacyMaxOdometer: Double?,
    currentObservedOdometer: Double?,
    legacyVehicleFingerprint: String? = null,
    currentVehicleFingerprint: String? = null,
    explicitUpgradeOrigin: Boolean = false
): LegacyHistoryMigrationEligibility {
    val count = legacyDriveCount + legacyChargeCount
    if (count <= 0) return LegacyHistoryMigrationEligibility(
        eligible = false,
        legacyDriveCount = legacyDriveCount,
        legacyChargeCount = legacyChargeCount,
        legacyMaxOdometer = legacyMaxOdometer,
        reason = LegacyHistoryMigrationBlockReason.NO_LEGACY_ROWS
    )
    if (!explicitUpgradeOrigin) return LegacyHistoryMigrationEligibility(
        false, legacyDriveCount, legacyChargeCount, legacyMaxOdometer,
        LegacyHistoryMigrationBlockReason.ARCHIVE_MARKER_UNAVAILABLE
    )
    if (legacyVehicleFingerprint.isNullOrBlank() || currentVehicleFingerprint.isNullOrBlank() ||
        legacyVehicleFingerprint != currentVehicleFingerprint
    ) return LegacyHistoryMigrationEligibility(
        false, legacyDriveCount, legacyChargeCount, legacyMaxOdometer,
        LegacyHistoryMigrationBlockReason.FINGERPRINT_MISMATCH
    )
    if (legacyModel.isNullOrBlank() || currentModel.isNullOrBlank() ||
        !legacyModel.trim().equals(currentModel.trim(), ignoreCase = true)
    ) return LegacyHistoryMigrationEligibility(
        false, legacyDriveCount, legacyChargeCount, legacyMaxOdometer,
        LegacyHistoryMigrationBlockReason.MODEL_MISMATCH
    )
    if (legacyMaxOdometer == null || currentObservedOdometer == null) return LegacyHistoryMigrationEligibility(
        false, legacyDriveCount, legacyChargeCount, legacyMaxOdometer,
        LegacyHistoryMigrationBlockReason.ODOMETER_UNAVAILABLE
    )
    if (!legacyMaxOdometer.isFinite() || !currentObservedOdometer.isFinite() ||
        legacyMaxOdometer < 0.0 || currentObservedOdometer < 0.0
    ) return LegacyHistoryMigrationEligibility(
        false, legacyDriveCount, legacyChargeCount, legacyMaxOdometer,
        LegacyHistoryMigrationBlockReason.ODOMETER_INVALID
    )
    if (legacyMaxOdometer > currentObservedOdometer + abs(currentObservedOdometer) * 0.000001) {
        return LegacyHistoryMigrationEligibility(
            false, legacyDriveCount, legacyChargeCount, legacyMaxOdometer,
            LegacyHistoryMigrationBlockReason.ODOMETER_ROLLBACK
        )
    }
    return LegacyHistoryMigrationEligibility(true, legacyDriveCount, legacyChargeCount, legacyMaxOdometer)
}

data class LegacyHistoryMigrationResult(
    val eligibility: LegacyHistoryMigrationEligibility,
    val copiedDriveCount: Int = 0,
    val copiedChargeCount: Int = 0,
    val copiedDriveAggregateCount: Int = 0,
    val copiedChargeAggregateCount: Int = 0,
    val copiedOverrideCount: Int = 0
) {
    val copiedRecordCount: Int get() = copiedDriveCount + copiedChargeCount
}

@Singleton
class LegacyHistoryMigrationRepository @Inject constructor(
    private val database: StatsDatabase,
    private val driveSummaryDao: DriveSummaryDao,
    private val chargeSummaryDao: ChargeSummaryDao,
    private val aggregateDao: AggregateDao,
    private val chargeCostOverrideDao: ChargeCostOverrideDao,
    private val legacyHistoryArchiveDao: LegacyHistoryArchiveDao
) : LegacyHistoryMigrationService {
    /**
     * Converts a V17 model-unknown marker only with real current car metadata.
     * This is an explicit binding, never an inference from a positive car id.
     */
    suspend fun recordExplicitUpgradeOrigin(
        legacyCarId: Int,
        actualModel: String?,
        actualVehicleFingerprint: String?
    ): Boolean {
        val model = actualModel?.trim().orEmpty()
        val fingerprint = actualVehicleFingerprint?.trim().orEmpty()
        if (model.isEmpty() || fingerprint.isEmpty()) return false
        return legacyHistoryArchiveDao.bindUnknownUpgradeArchive(
            legacyCarId = legacyCarId,
            vehicleModel = model,
            vehicleFingerprint = fingerprint
        ) == 1
    }

    override suspend fun inspect(
        legacyCarId: Int,
        currentModel: String?,
        currentVehicleFingerprint: String?,
        currentObservedOdometer: Double?
    ): LegacyHistoryMigrationEligibility {
        val driveCount = driveSummaryDao.count(legacyCarId)
        val chargeCount = chargeSummaryDao.count(legacyCarId)
        val archive = legacyHistoryArchiveDao.get(legacyCarId)
        return evaluateLegacyHistoryMigration(
            legacyDriveCount = driveCount,
            legacyChargeCount = chargeCount,
            legacyModel = archive?.vehicleModel,
            currentModel = currentModel,
            legacyMaxOdometer = chargeSummaryDao.getMaxNonNegativeOdometer(legacyCarId),
            currentObservedOdometer = currentObservedOdometer,
            legacyVehicleFingerprint = archive?.vehicleFingerprint,
            currentVehicleFingerprint = currentVehicleFingerprint,
            explicitUpgradeOrigin = archive?.upgradeOrigin == LegacyHistoryArchive.EXPLICIT_UPGRADE_ARCHIVE
        )
    }

    override suspend fun migrate(
        legacyCarId: Int,
        targetHistoryCarId: Int,
        eligibility: LegacyHistoryMigrationEligibility
    ): LegacyHistoryMigrationResult {
        if (!eligibility.eligible) return LegacyHistoryMigrationResult(eligibility)
        return database.withTransaction {
            val driveAggregates = aggregateDao.countDriveAggregates(legacyCarId)
            val chargeAggregates = aggregateDao.countChargeAggregates(legacyCarId)
            LegacyHistoryMigrationResult(
                eligibility = eligibility,
                copiedDriveCount = eligibility.legacyDriveCount,
                copiedChargeCount = eligibility.legacyChargeCount,
                copiedDriveAggregateCount = driveAggregates,
                copiedChargeAggregateCount = chargeAggregates
            ).also {
                driveSummaryDao.copyFromLegacy(legacyCarId, targetHistoryCarId)
                chargeSummaryDao.copyFromLegacy(legacyCarId, targetHistoryCarId)
                aggregateDao.copyDriveAggregatesFromLegacy(legacyCarId, targetHistoryCarId)
                aggregateDao.copyChargeAggregatesFromLegacy(legacyCarId, targetHistoryCarId)
                chargeCostOverrideDao.copyFromLegacy(legacyCarId, targetHistoryCarId)
            }
        }
    }
}

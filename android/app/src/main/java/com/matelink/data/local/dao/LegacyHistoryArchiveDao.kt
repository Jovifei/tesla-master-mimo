package com.matelink.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.matelink.data.local.entity.LegacyHistoryArchive

@Dao
interface LegacyHistoryArchiveDao {
    @Query("SELECT * FROM legacy_history_archives WHERE legacyCarId = :legacyCarId")
    suspend fun get(legacyCarId: Int): LegacyHistoryArchive?

    /** Called only by an explicit, verified upgrade flow; never inferred from rows. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordExplicitUpgradeOrigin(archive: LegacyHistoryArchive)

    /** Binds a V17 marker only when actual current car metadata is available. */
    @Query(
        """
        UPDATE legacy_history_archives
        SET vehicleFingerprint = :vehicleFingerprint,
            vehicleModel = :vehicleModel,
            upgradeOrigin = 'EXPLICIT_UPGRADE_ARCHIVE'
        WHERE legacyCarId = :legacyCarId
          AND upgradeOrigin = 'UPGRADE_ARCHIVE_MODEL_UNKNOWN'
        """
    )
    suspend fun bindUnknownUpgradeArchive(
        legacyCarId: Int,
        vehicleModel: String,
        vehicleFingerprint: String
    ): Int
}

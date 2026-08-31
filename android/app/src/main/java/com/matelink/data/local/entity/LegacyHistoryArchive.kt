package com.matelink.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Explicit evidence that a legacy archive came from an upgrade path. */
@Entity(tableName = "legacy_history_archives")
data class LegacyHistoryArchive(
    @PrimaryKey val legacyCarId: Int,
    val vehicleFingerprint: String,
    val vehicleModel: String,
    val upgradeOrigin: String,
    val recordedAt: Long
) {
    companion object {
        const val EXPLICIT_UPGRADE_ARCHIVE = "EXPLICIT_UPGRADE_ARCHIVE"
        const val UPGRADE_ARCHIVE_MODEL_UNKNOWN = "UPGRADE_ARCHIVE_MODEL_UNKNOWN"
        const val MODEL_UNKNOWN = "MODEL_UNKNOWN"
    }
}

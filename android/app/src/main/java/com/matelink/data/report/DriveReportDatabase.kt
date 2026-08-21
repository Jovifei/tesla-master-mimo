package com.matelink.data.report

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DriveReportDeliveryEntity::class,
        DriveReportCursorEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class DriveReportDatabase : RoomDatabase() {
    abstract fun driveReportDao(): DriveReportDao

    companion object {
        const val DATABASE_NAME = "matelink_drive_reports.db"
    }
}

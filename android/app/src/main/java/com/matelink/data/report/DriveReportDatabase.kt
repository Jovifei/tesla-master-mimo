package com.matelink.data.report

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DriveReportDeliveryEntity::class,
        DriveReportCursorEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class DriveReportDatabase : RoomDatabase() {
    abstract fun driveReportDao(): DriveReportDao

    companion object {
        const val DATABASE_NAME = "matelink_drive_reports.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // V1 intentionally did not persist notification facts. Keep
                // them nullable rather than fabricating zero distance/time.
                db.execSQL(
                    "ALTER TABLE drive_report_delivery " +
                        "ADD COLUMN durationMinutes INTEGER"
                )
                db.execSQL(
                    "ALTER TABLE drive_report_delivery " +
                        "ADD COLUMN distanceKm REAL"
                )
            }
        }
    }
}

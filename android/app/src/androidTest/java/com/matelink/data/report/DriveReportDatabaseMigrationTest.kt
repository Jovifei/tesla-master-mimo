package com.matelink.data.report

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriveReportDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DriveReportDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migratesV1RowsWithoutFabricatingNotificationFacts() {
        val databaseName = "drive-report-v1-to-v2"
        helper.createDatabase(databaseName, 1).apply {
            execSQL(
                """
                INSERT INTO drive_report_delivery (
                    carId, driveId, detectedAt, notificationPostedAt,
                    openedAt, dismissedAt, deliveryState, reportVersion
                ) VALUES (1, 7, 1000, NULL, NULL, NULL, 'pending', 1)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            2,
            true,
            DriveReportDatabase.MIGRATION_1_2
        )
        try {
            migrated.query(
                "SELECT durationMinutes, distanceKm FROM drive_report_delivery " +
                    "WHERE carId = 1 AND driveId = 7"
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
        } finally {
            migrated.close()
        }
    }
}

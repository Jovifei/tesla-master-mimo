package com.matelink.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatsDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StatsDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migratesLegacyV13RowsAndDefaultMetadataToV14() {
        migrateLegacyV13(databaseName = "legacy-v13-defaults", includesEnergyColumns = true)
    }

    @Test
    fun migratesV13WithoutEnergyColumnsToV14() {
        migrateLegacyV13(databaseName = "legacy-v13-missing-columns", includesEnergyColumns = false)
    }

    @Test
    fun migratesLegacyV14IdentityHashToLatestWithoutChangingRows() {
        val databaseName = "legacy-v14-identity-hash"
        helper.createDatabase(databaseName, 14).apply {
            execSQL("UPDATE room_master_table SET identity_hash = '442084c21fe8ce4522f3edacfbcc3884' WHERE id = 42")
            close()
        }

        val database = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            StatsDatabase::class.java,
            databaseName
        ).addMigrations(*StatsDatabase.ALL_MIGRATIONS).build()
        try {
            database.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
                cursor.moveToFirst()
                assertEquals(17, cursor.getInt(0))
            }
            database.openHelper.writableDatabase.query(
                "SELECT identity_hash FROM room_master_table WHERE id = 42"
            ).use { cursor ->
                cursor.moveToFirst()
                assertNotEquals("442084c21fe8ce4522f3edacfbcc3884", cursor.getString(0))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun migratesV15ToV16WithChargeCostOverrideTable() {
        val databaseName = "legacy-v15-charge-cost-overrides"
        helper.createDatabase(databaseName, 15).close()

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            16,
            true,
            StatsDatabase.MIGRATION_15_16
        )
        try {
            migrated.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'charge_cost_overrides'"
            ).use { cursor ->
                assertEquals(1, cursor.count)
            }
            migrated.query(
                "PRAGMA table_info(`charge_cost_overrides`)"
            ).use { cursor ->
                assertEquals(3, cursor.count)
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun migratesV16ToV17RetainsRowsAndCreatesTpmsPrimaryKeySchema() {
        val databaseName = "legacy-v16-tpms-pressure-samples"
        helper.createDatabase(databaseName, 16).apply {
            execSQL(
                """
                INSERT INTO drives_summary (
                    driveId, carId, startDate, endDate, durationMin, startAddress, endAddress,
                    distance, speedMax, speedAvg, powerMax, powerMin, startBatteryLevel,
                    endBatteryLevel, outsideTempAvg, insideTempAvg, energyConsumed, efficiency
                ) VALUES (
                    101, 7, '2026-01-01T00:00:00Z', '2026-01-01T01:00:00Z', 60, 'A', 'B',
                    123.45, 97, 54, 120, -50, 80, 70, 12.5, 21.0, 20.25, 164.0
                )
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO charge_cost_overrides (carId, chargeId, manualTotalAmount)
                VALUES (7, 501, 88.75)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            17,
            true,
            StatsDatabase.MIGRATION_16_17
        )
        try {
            migrated.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'tpms_pressure_samples'"
            ).use { cursor -> assertEquals(1, cursor.count) }
            migrated.query("PRAGMA index_list(`tpms_pressure_samples`)").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("pk", cursor.getString(cursor.getColumnIndexOrThrow("origin")))
            }
            migrated.query("PRAGMA table_info(`tpms_pressure_samples`)").use { cursor ->
                assertEquals(7, cursor.count)
            }
            migrated.query(
                "SELECT carId, startDate, distance, speedMax, energyConsumed, efficiency " +
                    "FROM drives_summary WHERE driveId = 101"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(7, cursor.getInt(0))
                assertEquals("2026-01-01T00:00:00Z", cursor.getString(1))
                assertEquals(123.45, cursor.getDouble(2), 0.0)
                assertEquals(97, cursor.getInt(3))
                assertEquals(20.25, cursor.getDouble(4), 0.0)
                assertEquals(164.0, cursor.getDouble(5), 0.0)
            }
            migrated.query(
                "SELECT manualTotalAmount FROM charge_cost_overrides WHERE carId = 7 AND chargeId = 501"
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(88.75, cursor.getDouble(0), 0.0)
            }
            migrated.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { cursor ->
                cursor.moveToFirst()
                assertNotEquals("99551db285888bcd78917317b4c68076", cursor.getString(0))
            }
        } finally {
            migrated.close()
        }
    }

    private fun migrateLegacyV13(databaseName: String, includesEnergyColumns: Boolean) {
        helper.createDatabase(databaseName, 13).apply {
            createLegacyV13Tables(this, includesEnergyColumns)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            databaseName,
            14,
            true,
            StatsDatabase.MIGRATION_13_14
        )
        try {
            migrated.query(
                "SELECT `energySource`, `energyCoverageSeconds`, `energyCoverageRatio` FROM `drives_summary` WHERE `driveId` = 7"
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                if (includesEnergyColumns) {
                    assertEquals("CALCULATED", cursor.getString(0))
                    assertEquals(360, cursor.getLong(1))
                    assertEquals(0.75, cursor.getDouble(2), 0.0)
                } else {
                    assertEquals(null, cursor.getString(0))
                    assertEquals(0, cursor.getLong(1))
                    assertEquals(0.0, cursor.getDouble(2), 0.0)
                }
            }
            migrated.query("SELECT COUNT(*) FROM `drive_detail_aggregates` WHERE `driveId` = 7").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            migrated.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
            assertEnergyDefaults(migrated)
        } finally {
            migrated.close()
        }
    }

    private fun createLegacyV13Tables(db: SupportSQLiteDatabase, includesEnergyColumns: Boolean) {
        db.execSQL("DROP INDEX IF EXISTS `index_drive_detail_aggregates_carId`")
        db.execSQL("DROP INDEX IF EXISTS `index_drive_detail_aggregates_driveId`")
        db.execSQL("DROP TABLE `drive_detail_aggregates`")
        db.execSQL("DROP INDEX IF EXISTS `index_drives_summary_carId`")
        db.execSQL("DROP INDEX IF EXISTS `index_drives_summary_carId_startDate`")
        db.execSQL("DROP TABLE `drives_summary`")

        val energyColumns = if (includesEnergyColumns) {
            ", `energySource` TEXT, `energyCoverageSeconds` INTEGER NOT NULL, `energyCoverageRatio` REAL NOT NULL"
        } else {
            ""
        }
        db.execSQL(
            """
            CREATE TABLE `drives_summary` (
                `driveId` INTEGER NOT NULL,
                `carId` INTEGER NOT NULL,
                `startDate` TEXT NOT NULL,
                `endDate` TEXT NOT NULL,
                `durationMin` INTEGER NOT NULL,
                `startAddress` TEXT NOT NULL,
                `endAddress` TEXT NOT NULL,
                `distance` REAL NOT NULL,
                `speedMax` INTEGER NOT NULL,
                `speedAvg` INTEGER NOT NULL,
                `powerMax` INTEGER NOT NULL,
                `powerMin` INTEGER NOT NULL,
                `startBatteryLevel` INTEGER NOT NULL,
                `endBatteryLevel` INTEGER NOT NULL,
                `outsideTempAvg` REAL,
                `insideTempAvg` REAL,
                `energyConsumed` REAL,
                `efficiency` REAL$energyColumns,
                PRIMARY KEY(`driveId`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX `index_drives_summary_carId` ON `drives_summary` (`carId`)")
        db.execSQL("CREATE INDEX `index_drives_summary_carId_startDate` ON `drives_summary` (`carId`, `startDate`)")

        val energyColumnNames = if (includesEnergyColumns) {
            ", `energySource`, `energyCoverageSeconds`, `energyCoverageRatio`"
        } else {
            ""
        }
        val energyValues = if (includesEnergyColumns) ", 'CALCULATED', 360, 0.75" else ""
        db.execSQL(
            """
            INSERT INTO `drives_summary` (
                `driveId`, `carId`, `startDate`, `endDate`, `durationMin`, `startAddress`, `endAddress`,
                `distance`, `speedMax`, `speedAvg`, `powerMax`, `powerMin`, `startBatteryLevel`,
                `endBatteryLevel`, `outsideTempAvg`, `insideTempAvg`, `energyConsumed`, `efficiency`$energyColumnNames
            ) VALUES (7, 3, 'start', 'end', 12, 'A', 'B', 8.5, 90, 45, 30, -12, 80, 70,
                NULL, NULL, 4.2, 150.0$energyValues)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE `drive_detail_aggregates` (
                `driveId` INTEGER NOT NULL,
                `carId` INTEGER NOT NULL,
                `schemaVersion` INTEGER NOT NULL,
                `computedAt` INTEGER NOT NULL,
                `maxElevation` INTEGER,
                `minElevation` INTEGER,
                `startElevation` INTEGER,
                `endElevation` INTEGER,
                `elevationGain` INTEGER,
                `elevationLoss` INTEGER,
                `hasElevationData` INTEGER NOT NULL,
                `maxInsideTemp` REAL,
                `minInsideTemp` REAL,
                `maxOutsideTemp` REAL,
                `minOutsideTemp` REAL,
                `maxPower` INTEGER,
                `minPower` INTEGER,
                `climateOnPositions` INTEGER NOT NULL,
                `positionCount` INTEGER NOT NULL,
                `startLatitude` REAL,
                `startLongitude` REAL,
                `startCountryCode` TEXT,
                `startCountryName` TEXT,
                `startRegionName` TEXT,
                `startCity` TEXT,
                `endLatitude` REAL,
                `endLongitude` REAL,
                `extraJson` TEXT,
                PRIMARY KEY(`driveId`),
                FOREIGN KEY(`driveId`) REFERENCES `drives_summary`(`driveId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX `index_drive_detail_aggregates_carId` ON `drive_detail_aggregates` (`carId`)")
        db.execSQL("CREATE INDEX `index_drive_detail_aggregates_driveId` ON `drive_detail_aggregates` (`driveId`)")
        db.execSQL(
            """
            INSERT INTO `drive_detail_aggregates` (
                `driveId`, `carId`, `schemaVersion`, `computedAt`, `hasElevationData`,
                `climateOnPositions`, `positionCount`
            ) VALUES (7, 3, 1, 1000, 0, 0, 10)
            """.trimIndent()
        )
        db.execSQL("UPDATE room_master_table SET identity_hash = '442084c21fe8ce4522f3edacfbcc3884' WHERE id = 42")
    }

    private fun assertEnergyDefaults(db: SupportSQLiteDatabase) {
        val defaults = mutableMapOf<String, String?>()
        db.query("PRAGMA table_info(`drives_summary`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) defaults[cursor.getString(nameIndex)] = cursor.getString(defaultIndex)
        }
        assertEquals("0", defaults["energyCoverageSeconds"])
        assertEquals("0", defaults["energyCoverageRatio"])
    }
}

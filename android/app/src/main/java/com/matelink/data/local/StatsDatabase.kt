package com.matelink.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.dao.ChargeCostOverrideDao
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.dao.GeocodeCacheDao
import com.matelink.data.local.dao.LegacyHistoryArchiveDao
import com.matelink.data.local.dao.GeocodeProgressDao
import com.matelink.data.local.dao.GeocodeQueueDao
import com.matelink.data.local.dao.SavedTripDao
import com.matelink.data.local.dao.SentryAlertLogDao
import com.matelink.data.local.dao.SyncStateDao
import com.matelink.data.local.dao.TripCountryCacheDao
import com.matelink.data.local.dao.TripRouteCacheDao
import com.matelink.data.local.dao.TpmsPressureSampleDao
import com.matelink.data.local.entity.ChargeDetailAggregate
import com.matelink.data.local.entity.ChargeCostOverride
import com.matelink.data.local.entity.ChargeSummary
import com.matelink.data.local.entity.DriveDetailAggregate
import com.matelink.data.local.entity.DriveSummary
import com.matelink.data.local.entity.GeocodeCache
import com.matelink.data.local.entity.LegacyHistoryArchive
import com.matelink.data.local.entity.GeocodeProgress
import com.matelink.data.local.entity.GeocodeQueueItem
import com.matelink.data.local.entity.SavedTrip
import com.matelink.data.local.entity.SavedTripConsumedFingerprint
import com.matelink.data.local.entity.SavedTripLeg
import com.matelink.data.local.entity.SentryAlertLog
import com.matelink.data.local.entity.SyncState
import com.matelink.data.local.entity.TripCountryCache
import com.matelink.data.local.entity.TripRouteCache
import com.matelink.data.local.entity.TpmsPressureSample

/**
 * Room database for storing stats data locally.
 *
 * Tables:
 * - sync_state: Tracks sync progress per car
 * - drives_summary: Drive list data for Quick Stats
 * - charges_summary: Charge list data for Quick Stats
 * - drive_detail_aggregates: Computed aggregates for Deep Stats
 * - charge_detail_aggregates: Computed aggregates for Deep Stats
 *
 * Storage estimate: ~10 MB for heavy user (15k drives, 8k charges)
 */
@Database(
    entities = [
        SyncState::class,
        DriveSummary::class,
        ChargeSummary::class,
        ChargeCostOverride::class,
        DriveDetailAggregate::class,
        ChargeDetailAggregate::class,
        GeocodeCache::class,
        GeocodeQueueItem::class,
        GeocodeProgress::class,
        SentryAlertLog::class,
        TripRouteCache::class,
        TripCountryCache::class,
        SavedTrip::class,
        SavedTripLeg::class,
        SavedTripConsumedFingerprint::class,
        TpmsPressureSample::class,
        LegacyHistoryArchive::class
    ],
    version = 19,
    exportSchema = true
)
abstract class StatsDatabase : RoomDatabase() {

    abstract fun syncStateDao(): SyncStateDao
    abstract fun driveSummaryDao(): DriveSummaryDao
    abstract fun chargeSummaryDao(): ChargeSummaryDao
    abstract fun chargeCostOverrideDao(): ChargeCostOverrideDao
    abstract fun aggregateDao(): AggregateDao
    abstract fun geocodeCacheDao(): GeocodeCacheDao
    abstract fun geocodeQueueDao(): GeocodeQueueDao
    abstract fun geocodeProgressDao(): GeocodeProgressDao
    abstract fun sentryAlertLogDao(): SentryAlertLogDao
    abstract fun tripRouteCacheDao(): TripRouteCacheDao
    abstract fun tripCountryCacheDao(): TripCountryCacheDao
    abstract fun savedTripDao(): SavedTripDao
    abstract fun tpmsPressureSampleDao(): TpmsPressureSampleDao
    abstract fun legacyHistoryArchiveDao(): LegacyHistoryArchiveDao

    companion object {
        const val DATABASE_NAME = "matelink_stats.db"

        /** Migration from V1 to V2: Add start/end elevation for net climb calculation */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add startElevation and endElevation columns to drive_detail_aggregates
                db.execSQL("ALTER TABLE drive_detail_aggregates ADD COLUMN startElevation INTEGER")
                db.execSQL("ALTER TABLE drive_detail_aggregates ADD COLUMN endElevation INTEGER")
            }
        }

        /** Migration from V2 to V3: Fix isFastCharger using Teslamate's charger_phases logic */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Teslamate logic: DC charging has charger_phases = 0 or null
                // AC charging has charger_phases = 1, 2, or 3
                db.execSQL("""
                    UPDATE charge_detail_aggregates
                    SET isFastCharger = CASE
                        WHEN chargerPhases IS NULL OR chargerPhases = 0 THEN 1
                        ELSE 0
                    END
                """)
            }
        }

        /** Migration from V3 to V4: Add country fields to drive aggregates */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drive_detail_aggregates ADD COLUMN startCountryCode TEXT")
                db.execSQL("ALTER TABLE drive_detail_aggregates ADD COLUMN startCountryName TEXT")
            }
        }

        /** Migration from V4 to V5: Add geocoding cache tables and location fields */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create geocode_cache table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS geocode_cache (
                        gridLat INTEGER NOT NULL,
                        gridLon INTEGER NOT NULL,
                        countryCode TEXT,
                        countryName TEXT,
                        regionName TEXT,
                        city TEXT,
                        cachedAt INTEGER NOT NULL,
                        PRIMARY KEY (gridLat, gridLon)
                    )
                """)

                // Create geocode_queue table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS geocode_queue (
                        gridLat INTEGER NOT NULL,
                        gridLon INTEGER NOT NULL,
                        carId INTEGER NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        addedAt INTEGER NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        lastAttemptAt INTEGER,
                        PRIMARY KEY (gridLat, gridLon)
                    )
                """)

                // Create geocode_progress table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS geocode_progress (
                        carId INTEGER NOT NULL,
                        totalLocations INTEGER NOT NULL DEFAULT 0,
                        processedLocations INTEGER NOT NULL DEFAULT 0,
                        lastUpdatedAt INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (carId)
                    )
                """)

                // Add coordinate and region/city columns to drive_detail_aggregates
                db.execSQL("ALTER TABLE drive_detail_aggregates ADD COLUMN startLatitude REAL")
                db.execSQL("ALTER TABLE drive_detail_aggregates ADD COLUMN startLongitude REAL")
                db.execSQL("ALTER TABLE drive_detail_aggregates ADD COLUMN startRegionName TEXT")
                db.execSQL("ALTER TABLE drive_detail_aggregates ADD COLUMN startCity TEXT")

                // Add location columns to charge_detail_aggregates
                db.execSQL("ALTER TABLE charge_detail_aggregates ADD COLUMN countryCode TEXT")
                db.execSQL("ALTER TABLE charge_detail_aggregates ADD COLUMN countryName TEXT")
                db.execSQL("ALTER TABLE charge_detail_aggregates ADD COLUMN regionName TEXT")
                db.execSQL("ALTER TABLE charge_detail_aggregates ADD COLUMN city TEXT")
            }
        }

        /** Migration from V5 to V6: Add sentry alert history log table */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sentry_alert_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        carId INTEGER NOT NULL,
                        detectedAt INTEGER NOT NULL,
                        sessionStartedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_sentry_alert_log_carId_detectedAt
                    ON sentry_alert_log (carId, detectedAt)
                """)
            }
        }

        /** Migration from V6 to V7: Add trip route cache table (one row per segment) */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS trip_route_cache (
                        tripKey TEXT NOT NULL,
                        segmentIndex INTEGER NOT NULL,
                        segmentJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY (tripKey, segmentIndex)
                    )
                """)
            }
        }

        /** Migration from V7 to V8: Recreate trip_route_cache with composite PK */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS trip_route_cache")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS trip_route_cache (
                        tripKey TEXT NOT NULL,
                        segmentIndex INTEGER NOT NULL,
                        segmentJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY (tripKey, segmentIndex)
                    )
                """)
            }
        }

        /**
         * Migration from V8 to V9:
         * - Recreate trip_route_cache with binary BLOB instead of JSON text
         * - Add end coordinates to drive_detail_aggregates for trip country resolution
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS trip_route_cache")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS trip_route_cache (
                        tripKey TEXT NOT NULL,
                        segmentIndex INTEGER NOT NULL,
                        segmentData BLOB NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY (tripKey, segmentIndex)
                    )
                """)
                db.execSQL("ALTER TABLE drive_detail_aggregates ADD COLUMN endLatitude REAL")
                db.execSQL("ALTER TABLE drive_detail_aggregates ADD COLUMN endLongitude REAL")
            }
        }

        /** Migration from V9 to V10: Add trip country cache table */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS trip_country_cache (
                        tripKey TEXT NOT NULL,
                        countries TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY (tripKey)
                    )
                """)
            }
        }

        /** Migration from V10 to V11: Add location fields to sentry alert log */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sentry_alert_log ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE sentry_alert_log ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE sentry_alert_log ADD COLUMN address TEXT")
            }
        }

        /**
         * Migration from V11 to V12: Add saved trip persistence tables.
         *
         * - saved_trips: first-class trip entity (metrics derived from legs at read time)
         * - saved_trip_legs: ordered DRIVE/CHARGE references per trip (ON DELETE CASCADE)
         * - saved_trip_consumed_fingerprints: suppresses auto-detector output already
         *   represented by a saved trip (ON DELETE CASCADE → releases when trip is deleted)
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS saved_trips (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        carId INTEGER NOT NULL,
                        name TEXT,
                        source TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_saved_trips_carId
                    ON saved_trips (carId)
                """)

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS saved_trip_legs (
                        tripId INTEGER NOT NULL,
                        position INTEGER NOT NULL,
                        legType TEXT NOT NULL,
                        legId INTEGER NOT NULL,
                        PRIMARY KEY (tripId, position),
                        FOREIGN KEY (tripId) REFERENCES saved_trips(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_saved_trip_legs_legType_legId
                    ON saved_trip_legs (legType, legId)
                """)

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS saved_trip_consumed_fingerprints (
                        savedTripId INTEGER NOT NULL,
                        fingerprint TEXT NOT NULL,
                        PRIMARY KEY (savedTripId, fingerprint),
                        FOREIGN KEY (savedTripId) REFERENCES saved_trips(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_saved_trip_consumed_fingerprints_fingerprint
                    ON saved_trip_consumed_fingerprints (fingerprint)
                """)
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drives_summary ADD COLUMN energySource TEXT")
                db.execSQL("ALTER TABLE drives_summary ADD COLUMN energyCoverageSeconds INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE drives_summary ADD COLUMN energyCoverageRatio REAL NOT NULL DEFAULT 0")
            }
        }

        /**
         * Migration from V13 to V14: align persisted default-value metadata for drive energy
         * provenance. Earlier V13 builds have the same user_version but a different Room
         * identity hash, which otherwise prevents the database from opening.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                rebuildDrivesSummaryWithEnergyDefaults(db)
            }
        }

        /**
         * Migration from V14 to V15: repair the Room master-table identity hash left by
         * earlier V13/V14 builds. The schema is unchanged; advancing the user version makes
         * Room validate the existing tables and write its current identity metadata.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        /** Migration from V15 to V16: persist per-vehicle manual charge costs in Room. */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `charge_cost_overrides` (
                        `carId` INTEGER NOT NULL,
                        `chargeId` INTEGER NOT NULL,
                        `manualTotalAmount` REAL NOT NULL,
                        PRIMARY KEY(`carId`, `chargeId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_charge_cost_overrides_carId` " +
                        "ON `charge_cost_overrides` (`carId`)"
                )
            }
        }

        /** Migration from V16 to V17: persist local TPMS pressure observations. */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tpms_pressure_samples` (
                        `carId` INTEGER NOT NULL,
                        `observedAt` INTEGER NOT NULL,
                        `pressureFl` REAL,
                        `pressureFr` REAL,
                        `pressureRl` REAL,
                        `pressureRr` REAL,
                        `outsideTempC` REAL,
                        PRIMARY KEY(`carId`, `observedAt`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration from V17 to V18: scope history records by local history car id.
         *
         * V17 used driveId/chargeId as global primary keys. Rebuilding only the
         * four affected tables keeps every archive row while allowing two vehicle
         * namespaces to contain the same provider id.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                rebuildDriveHistoryTables(db)
                rebuildChargeHistoryTables(db)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `legacy_history_archives` (
                        `legacyCarId` INTEGER NOT NULL,
                        `vehicleFingerprint` TEXT NOT NULL,
                        `vehicleModel` TEXT NOT NULL,
                        `upgradeOrigin` TEXT NOT NULL,
                        `recordedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`legacyCarId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `legacy_history_archives` (
                        `legacyCarId`, `vehicleFingerprint`, `vehicleModel`, `upgradeOrigin`, `recordedAt`
                    )
                    SELECT `carId`, 'legacy-v17:car:' || `carId`, 'MODEL_UNKNOWN',
                        'UPGRADE_ARCHIVE_MODEL_UNKNOWN', 0
                    FROM (
                        SELECT `carId` FROM `drives_summary`
                        UNION
                        SELECT `carId` FROM `charges_summary`
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * V19 stores the exact nullable API payload alongside legacy summary
         * columns. V18 values remain legacy/unproven instead of being inferred.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `drives_summary` ADD COLUMN `apiEvidence` TEXT")
                db.execSQL("ALTER TABLE `charges_summary` ADD COLUMN `apiEvidence` TEXT")
            }
        }

        private fun rebuildDriveHistoryTables(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `drive_detail_aggregates` RENAME TO `drive_detail_aggregates_v17`")
            db.execSQL("ALTER TABLE `drives_summary` RENAME TO `drives_summary_v17`")
            db.execSQL(
                """
                CREATE TABLE `drives_summary` (
                    `driveId` INTEGER NOT NULL, `carId` INTEGER NOT NULL,
                    `startDate` TEXT NOT NULL, `endDate` TEXT NOT NULL,
                    `durationMin` INTEGER NOT NULL, `startAddress` TEXT NOT NULL,
                    `endAddress` TEXT NOT NULL, `distance` REAL NOT NULL,
                    `speedMax` INTEGER NOT NULL, `speedAvg` INTEGER NOT NULL,
                    `powerMax` INTEGER NOT NULL, `powerMin` INTEGER NOT NULL,
                    `startBatteryLevel` INTEGER NOT NULL, `endBatteryLevel` INTEGER NOT NULL,
                    `outsideTempAvg` REAL, `insideTempAvg` REAL,
                    `energyConsumed` REAL, `efficiency` REAL, `energySource` TEXT,
                    `energyCoverageSeconds` INTEGER NOT NULL DEFAULT 0,
                    `energyCoverageRatio` REAL NOT NULL DEFAULT 0,
                    PRIMARY KEY(`carId`, `driveId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `drives_summary`
                SELECT `driveId`, `carId`, `startDate`, `endDate`, `durationMin`,
                    `startAddress`, `endAddress`, `distance`, `speedMax`, `speedAvg`,
                    `powerMax`, `powerMin`, `startBatteryLevel`, `endBatteryLevel`,
                    `outsideTempAvg`, `insideTempAvg`, `energyConsumed`, `efficiency`,
                    `energySource`, `energyCoverageSeconds`, `energyCoverageRatio`
                FROM `drives_summary_v17`
                """.trimIndent()
            )
            createDriveDetailAggregatesTable(db, "drive_detail_aggregates", includeForeignKey = true, compositeKey = true)
            db.execSQL(
                """
                INSERT INTO `drive_detail_aggregates`
                SELECT `driveId`, `carId`, `schemaVersion`, `computedAt`, `maxElevation`, `minElevation`,
                    `startElevation`, `endElevation`, `elevationGain`, `elevationLoss`, `hasElevationData`,
                    `maxInsideTemp`, `minInsideTemp`, `maxOutsideTemp`, `minOutsideTemp`, `maxPower`,
                    `minPower`, `climateOnPositions`, `positionCount`, `startLatitude`, `startLongitude`,
                    `startCountryCode`, `startCountryName`, `startRegionName`, `startCity`,
                    `endLatitude`, `endLongitude`, `extraJson`
                FROM `drive_detail_aggregates_v17`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `drive_detail_aggregates_v17`")
            db.execSQL("DROP TABLE `drives_summary_v17`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_drives_summary_carId` ON `drives_summary` (`carId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_drives_summary_carId_startDate` ON `drives_summary` (`carId`, `startDate`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_drive_detail_aggregates_carId` ON `drive_detail_aggregates` (`carId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_drive_detail_aggregates_driveId` ON `drive_detail_aggregates` (`driveId`)")
        }

        private fun rebuildChargeHistoryTables(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `charge_detail_aggregates` RENAME TO `charge_detail_aggregates_v17`")
            db.execSQL("ALTER TABLE `charges_summary` RENAME TO `charges_summary_v17`")
            db.execSQL(
                """
                CREATE TABLE `charges_summary` (
                    `chargeId` INTEGER NOT NULL, `carId` INTEGER NOT NULL,
                    `startDate` TEXT NOT NULL, `endDate` TEXT NOT NULL,
                    `durationMin` INTEGER NOT NULL, `address` TEXT NOT NULL,
                    `latitude` REAL NOT NULL, `longitude` REAL NOT NULL,
                    `energyAdded` REAL NOT NULL, `energyUsed` REAL, `cost` REAL,
                    `startBatteryLevel` INTEGER NOT NULL, `endBatteryLevel` INTEGER NOT NULL,
                    `outsideTempAvg` REAL, `odometer` REAL NOT NULL,
                    PRIMARY KEY(`carId`, `chargeId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `charges_summary`
                SELECT `chargeId`, `carId`, `startDate`, `endDate`, `durationMin`, `address`,
                    `latitude`, `longitude`, `energyAdded`, `energyUsed`, `cost`,
                    `startBatteryLevel`, `endBatteryLevel`, `outsideTempAvg`, `odometer`
                FROM `charges_summary_v17`
                """.trimIndent()
            )
            createChargeDetailAggregatesTable(db, "charge_detail_aggregates", compositeKey = true)
            db.execSQL(
                """
                INSERT INTO `charge_detail_aggregates`
                SELECT `chargeId`, `carId`, `schemaVersion`, `computedAt`, `isFastCharger`,
                    `fastChargerBrand`, `connectorType`, `maxChargerPower`, `maxChargerVoltage`,
                    `maxChargerCurrent`, `chargerPhases`, `maxOutsideTemp`, `minOutsideTemp`,
                    `chargePointCount`, `countryCode`, `countryName`, `regionName`, `city`, `extraJson`
                FROM `charge_detail_aggregates_v17`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `charge_detail_aggregates_v17`")
            db.execSQL("DROP TABLE `charges_summary_v17`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_charges_summary_carId` ON `charges_summary` (`carId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_charges_summary_carId_startDate` ON `charges_summary` (`carId`, `startDate`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_detail_aggregates_carId` ON `charge_detail_aggregates` (`carId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_charge_detail_aggregates_chargeId` ON `charge_detail_aggregates` (`chargeId`)")
        }

        private fun rebuildDrivesSummaryWithEnergyDefaults(db: SupportSQLiteDatabase) {
            val energySource = if (hasColumn(db, "drives_summary", "energySource")) "`energySource`" else "NULL"
            val energyCoverageSeconds = if (hasColumn(db, "drives_summary", "energyCoverageSeconds")) {
                "COALESCE(`energyCoverageSeconds`, 0)"
            } else {
                "0"
            }
            val energyCoverageRatio = if (hasColumn(db, "drives_summary", "energyCoverageRatio")) {
                "COALESCE(`energyCoverageRatio`, 0)"
            } else {
                "0"
            }

            createDriveDetailAggregatesTable(db, "drive_detail_aggregates_v14", includeForeignKey = false)
            copyDriveDetailAggregates(db, "drive_detail_aggregates", "drive_detail_aggregates_v14")
            db.execSQL("DROP TABLE `drive_detail_aggregates`")

            db.execSQL(
                """
                CREATE TABLE `drives_summary_v14` (
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
                    `efficiency` REAL,
                    `energySource` TEXT,
                    `energyCoverageSeconds` INTEGER NOT NULL DEFAULT 0,
                    `energyCoverageRatio` REAL NOT NULL DEFAULT 0,
                    PRIMARY KEY(`driveId`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `drives_summary_v14` (
                    `driveId`, `carId`, `startDate`, `endDate`, `durationMin`, `startAddress`,
                    `endAddress`, `distance`, `speedMax`, `speedAvg`, `powerMax`, `powerMin`,
                    `startBatteryLevel`, `endBatteryLevel`, `outsideTempAvg`, `insideTempAvg`,
                    `energyConsumed`, `efficiency`, `energySource`, `energyCoverageSeconds`,
                    `energyCoverageRatio`
                )
                SELECT
                    `driveId`, `carId`, `startDate`, `endDate`, `durationMin`, `startAddress`,
                    `endAddress`, `distance`, `speedMax`, `speedAvg`, `powerMax`, `powerMin`,
                    `startBatteryLevel`, `endBatteryLevel`, `outsideTempAvg`, `insideTempAvg`,
                    `energyConsumed`, `efficiency`, $energySource, $energyCoverageSeconds,
                    $energyCoverageRatio
                FROM `drives_summary`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `drives_summary`")
            db.execSQL("ALTER TABLE `drives_summary_v14` RENAME TO `drives_summary`")
            createDriveDetailAggregatesTable(db, "drive_detail_aggregates", includeForeignKey = true)
            copyDriveDetailAggregates(db, "drive_detail_aggregates_v14", "drive_detail_aggregates")
            db.execSQL("DROP TABLE `drive_detail_aggregates_v14`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_drives_summary_carId` ON `drives_summary` (`carId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_drives_summary_carId_startDate` ON `drives_summary` (`carId`, `startDate`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_drive_detail_aggregates_carId` ON `drive_detail_aggregates` (`carId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_drive_detail_aggregates_driveId` ON `drive_detail_aggregates` (`driveId`)")
        }

        private fun createDriveDetailAggregatesTable(
            db: SupportSQLiteDatabase,
            tableName: String,
            includeForeignKey: Boolean,
            compositeKey: Boolean = false
        ) {
            val foreignKey = if (includeForeignKey) {
                if (compositeKey) {
                    ", FOREIGN KEY(`carId`, `driveId`) REFERENCES `drives_summary`(`carId`, `driveId`) ON UPDATE NO ACTION ON DELETE CASCADE"
                } else {
                    ", FOREIGN KEY(`driveId`) REFERENCES `drives_summary`(`driveId`) ON UPDATE NO ACTION ON DELETE CASCADE"
                }
            } else {
                ""
            }
            val primaryKey = if (compositeKey) "PRIMARY KEY(`carId`, `driveId`)" else "PRIMARY KEY(`driveId`)"
            db.execSQL(
                """
                CREATE TABLE `$tableName` (
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
                    $primaryKey$foreignKey
                )
                """.trimIndent()
            )
        }

        private fun createChargeDetailAggregatesTable(
            db: SupportSQLiteDatabase,
            tableName: String,
            compositeKey: Boolean
        ) {
            val primaryKey = if (compositeKey) "PRIMARY KEY(`carId`, `chargeId`)" else "PRIMARY KEY(`chargeId`)"
            db.execSQL(
                """
                CREATE TABLE `$tableName` (
                    `chargeId` INTEGER NOT NULL, `carId` INTEGER NOT NULL,
                    `schemaVersion` INTEGER NOT NULL, `computedAt` INTEGER NOT NULL,
                    `isFastCharger` INTEGER NOT NULL, `fastChargerBrand` TEXT,
                    `connectorType` TEXT, `maxChargerPower` INTEGER,
                    `maxChargerVoltage` INTEGER, `maxChargerCurrent` INTEGER,
                    `chargerPhases` INTEGER, `maxOutsideTemp` REAL,
                    `minOutsideTemp` REAL, `chargePointCount` INTEGER NOT NULL,
                    `countryCode` TEXT, `countryName` TEXT, `regionName` TEXT,
                    `city` TEXT, `extraJson` TEXT,
                    $primaryKey,
                    FOREIGN KEY(`carId`, `chargeId`) REFERENCES `charges_summary`(`carId`, `chargeId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }

        private fun copyDriveDetailAggregates(
            db: SupportSQLiteDatabase,
            sourceTable: String,
            destinationTable: String
        ) {
            db.execSQL(
                """
                INSERT INTO `$destinationTable` (
                    `driveId`, `carId`, `schemaVersion`, `computedAt`, `maxElevation`, `minElevation`,
                    `startElevation`, `endElevation`, `elevationGain`, `elevationLoss`, `hasElevationData`,
                    `maxInsideTemp`, `minInsideTemp`, `maxOutsideTemp`, `minOutsideTemp`, `maxPower`,
                    `minPower`, `climateOnPositions`, `positionCount`, `startLatitude`, `startLongitude`,
                    `startCountryCode`, `startCountryName`, `startRegionName`, `startCity`, `endLatitude`,
                    `endLongitude`, `extraJson`
                )
                SELECT
                    `driveId`, `carId`, `schemaVersion`, `computedAt`, `maxElevation`, `minElevation`,
                    `startElevation`, `endElevation`, `elevationGain`, `elevationLoss`, `hasElevationData`,
                    `maxInsideTemp`, `minInsideTemp`, `maxOutsideTemp`, `minOutsideTemp`, `maxPower`,
                    `minPower`, `climateOnPositions`, `positionCount`, `startLatitude`, `startLongitude`,
                    `startCountryCode`, `startCountryName`, `startRegionName`, `startCity`, `endLatitude`,
                    `endLongitude`, `extraJson`
                FROM `$sourceTable`
                """.trimIndent()
            )
        }

        private fun hasColumn(db: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean =
            db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }
                    .any { it == columnName }
            }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
            MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19
        )
    }
}

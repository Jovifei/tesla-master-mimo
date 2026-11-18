package com.matelink.di

import android.content.Context
import androidx.room.Room
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.dao.ChargeCostOverrideDao
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.dao.GeocodeCacheDao
import com.matelink.data.local.dao.GeocodeProgressDao
import com.matelink.data.local.dao.LegacyHistoryArchiveDao
import com.matelink.data.local.dao.GeocodeQueueDao
import com.matelink.data.local.dao.HistoryDetailDao
import com.matelink.data.local.dao.SavedTripDao
import com.matelink.data.local.dao.SentryAlertLogDao
import com.matelink.data.local.dao.SocSampleDao
import com.matelink.data.local.dao.SyncStateDao
import com.matelink.data.local.dao.TripCountryCacheDao
import com.matelink.data.local.dao.TripRouteCacheDao
import com.matelink.data.local.dao.TpmsPressureSampleDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideStatsDatabase(
        @ApplicationContext context: Context
    ): StatsDatabase {
        snapshotBeforeMigration(context)
        return Room.databaseBuilder(
            context,
            StatsDatabase::class.java,
            StatsDatabase.DATABASE_NAME
        )
            .addMigrations(*StatsDatabase.ALL_MIGRATIONS)
            .build()
    }

    /**
     * Copy the existing database before Room runs a schema migration. The
     * snapshot is app-private, replaces the previous snapshot (one copy), and
     * is only made when the stored user_version is older than the current
     * schema. New installations never create an empty backup.
     */
    private fun snapshotBeforeMigration(context: Context) {
        val source = context.getDatabasePath(StatsDatabase.DATABASE_NAME)
        if (!source.isFile) return
        val version = readUserVersion(source)
        if (version !in 1 until StatsDatabase.SCHEMA_VERSION) return
        runCatching {
            val dir = File(context.filesDir, "database_backups").apply { mkdirs() }
            val target = File(dir, "${StatsDatabase.DATABASE_NAME}.pre-migration")
            target.delete()
            source.copyTo(target, overwrite = true)
            listOf("-wal", "-shm").forEach { suffix ->
                val part = File(source.path + suffix)
                if (part.isFile) {
                    File(target.path + suffix).delete()
                    part.copyTo(File(target.path + suffix), overwrite = true)
                }
            }
        }.onFailure {
            // A failed best-effort snapshot must not block the normal migration.
        }
    }

    private fun readUserVersion(file: File): Int {
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(100)
                if (input.read(header) != header.size) return 0
                ByteBuffer.wrap(header, 60, 4).int
            }
        } catch (_: Exception) {
            0
        }
    }

    @Provides
    @Singleton
    fun provideSocSampleDao(database: StatsDatabase): SocSampleDao {
        return database.socSampleDao()
    }

    @Provides
    @Singleton
    fun provideSyncStateDao(database: StatsDatabase): SyncStateDao {
        return database.syncStateDao()
    }

    @Provides
    @Singleton
    fun provideDriveSummaryDao(database: StatsDatabase): DriveSummaryDao {
        return database.driveSummaryDao()
    }

    @Provides
    @Singleton
    fun provideChargeSummaryDao(database: StatsDatabase): ChargeSummaryDao {
        return database.chargeSummaryDao()
    }

    @Provides
    @Singleton
    fun provideHistoryDetailDao(database: StatsDatabase): HistoryDetailDao {
        return database.historyDetailDao()
    }

    @Provides
    @Singleton
    fun provideChargeCostOverrideDao(database: StatsDatabase): ChargeCostOverrideDao {
        return database.chargeCostOverrideDao()
    }

    @Provides
    @Singleton
    fun provideAggregateDao(database: StatsDatabase): AggregateDao {
        return database.aggregateDao()
    }

    @Provides
    @Singleton
    fun provideGeocodeCacheDao(database: StatsDatabase): GeocodeCacheDao {
        return database.geocodeCacheDao()
    }

    @Provides
    @Singleton
    fun provideGeocodeQueueDao(database: StatsDatabase): GeocodeQueueDao {
        return database.geocodeQueueDao()
    }

    @Provides
    @Singleton
    fun provideGeocodeProgressDao(database: StatsDatabase): GeocodeProgressDao {
        return database.geocodeProgressDao()
    }

    @Provides
    @Singleton
    fun provideSentryAlertLogDao(database: StatsDatabase): SentryAlertLogDao {
        return database.sentryAlertLogDao()
    }

    @Provides
    @Singleton
    fun provideTripRouteCacheDao(database: StatsDatabase): TripRouteCacheDao {
        return database.tripRouteCacheDao()
    }

    @Provides
    @Singleton
    fun provideTripCountryCacheDao(database: StatsDatabase): TripCountryCacheDao {
        return database.tripCountryCacheDao()
    }

    @Provides
    @Singleton
    fun provideSavedTripDao(database: StatsDatabase): SavedTripDao {
        return database.savedTripDao()
    }

    @Provides
    @Singleton
    fun provideTpmsPressureSampleDao(database: StatsDatabase): TpmsPressureSampleDao {
        return database.tpmsPressureSampleDao()
    }

    @Provides
    @Singleton
    fun provideLegacyHistoryArchiveDao(database: StatsDatabase): LegacyHistoryArchiveDao {
        return database.legacyHistoryArchiveDao()
    }
}

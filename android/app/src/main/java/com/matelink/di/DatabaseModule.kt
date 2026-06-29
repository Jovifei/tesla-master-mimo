package com.matelink.di

import android.content.Context
import androidx.room.Room
import com.matelink.data.local.StatsDatabase
import com.matelink.data.local.dao.AggregateDao
import com.matelink.data.local.dao.ChargeSummaryDao
import com.matelink.data.local.dao.DriveSummaryDao
import com.matelink.data.local.dao.GeocodeCacheDao
import com.matelink.data.local.dao.GeocodeProgressDao
import com.matelink.data.local.dao.GeocodeQueueDao
import com.matelink.data.local.dao.SavedTripDao
import com.matelink.data.local.dao.SentryAlertLogDao
import com.matelink.data.local.dao.SyncStateDao
import com.matelink.data.local.dao.TripCountryCacheDao
import com.matelink.data.local.dao.TripRouteCacheDao
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
        return Room.databaseBuilder(
            context,
            StatsDatabase::class.java,
            StatsDatabase.DATABASE_NAME
        )
            .addMigrations(*StatsDatabase.ALL_MIGRATIONS)
            .fallbackToDestructiveMigration(dropAllTables = false)  // Fallback for development
            .build()
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
}

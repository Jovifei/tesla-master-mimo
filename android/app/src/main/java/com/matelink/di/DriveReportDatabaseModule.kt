package com.matelink.di

import android.content.Context
import androidx.room.Room
import com.matelink.data.report.DriveReportDao
import com.matelink.data.report.DriveReportDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DriveReportDatabaseModule {
    @Provides
    @Singleton
    fun provideDriveReportDatabase(
        @ApplicationContext context: Context
    ): DriveReportDatabase = Room.databaseBuilder(
        context,
        DriveReportDatabase::class.java,
        DriveReportDatabase.DATABASE_NAME
    ).build()

    @Provides
    @Singleton
    fun provideDriveReportDao(database: DriveReportDatabase): DriveReportDao =
        database.driveReportDao()
}

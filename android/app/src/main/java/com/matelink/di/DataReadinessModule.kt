package com.matelink.di

import com.matelink.data.local.VehicleContextRepository
import com.matelink.data.local.VehicleContextResolver
import com.matelink.data.repository.DataReadinessDataSource
import com.matelink.data.repository.LegacyHistoryMigrationRepository
import com.matelink.data.repository.LegacyHistoryMigrationService
import com.matelink.data.repository.TeslamateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataReadinessModule {
    @Binds
    @Singleton
    abstract fun bindDataReadinessDataSource(repository: TeslamateRepository): DataReadinessDataSource

    @Binds
    @Singleton
    abstract fun bindVehicleContextResolver(repository: VehicleContextRepository): VehicleContextResolver

    @Binds
    @Singleton
    abstract fun bindLegacyHistoryMigrationService(repository: LegacyHistoryMigrationRepository): LegacyHistoryMigrationService
}

package com.matelink.di

import com.matelink.data.local.HistoryCarIdResolver
import com.matelink.data.local.VehicleContextRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VehicleContextModule {
    @Binds
    @Singleton
    abstract fun bindHistoryCarIdResolver(repository: VehicleContextRepository): HistoryCarIdResolver
}

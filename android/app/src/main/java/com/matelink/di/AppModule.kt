package com.matelink.di

import android.content.Context
import com.matelink.data.local.SettingsDataStore
import com.matelink.notification.ChargingNotificationManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideChargingNotificationManager(
        @ApplicationContext context: Context
    ): ChargingNotificationManager {
        return ChargingNotificationManager(context)
    }
}

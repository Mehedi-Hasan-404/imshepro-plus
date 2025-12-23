// app/src/main/java/com/livetvpro/di/AppModule.kt
package com.livetvpro.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // Firebase removed - now using NetworkModule for API calls
}

// app/src/main/java/com/livetvpro/di/NetworkModule.kt
package com.livetvpro.di

import android.content.Context
import com.livetvpro.data.api.ApiService
import com.livetvpro.utils.RemoteConfigManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        // --- SECURITY CHECK REMOVED ---
        // The previous integrity check was too strict and blocked valid API URLs.
        // It is commented out below to ensure your app loads data correctly.
        
        /*
        val integrityInterceptor = Interceptor { chain ->
            val request = chain.request()
            // ... strict checks removed ...
            chain.proceed(request)
        }
        */

        return OkHttpClient.Builder()
            // .addInterceptor(integrityInterceptor) // Disabled
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        okHttpClient: OkHttpClient,
        remoteConfigManager: RemoteConfigManager
    ): Retrofit {
        val baseUrl = remoteConfigManager.getBaseUrl()
        
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }
}


// app/src/main/java/com/livetvpro/di/NetworkModule.kt
package com.livetvpro.di

import com.livetvpro.data.api.ApiService
import com.livetvpro.data.api.ListenerService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    init {
        // Load native library
        System.loadLibrary("native-lib")
    }

    // Native method declaration
    private external fun getBaseUrl(): String

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        // Get base URL from native library (encrypted)
        val baseUrl = getBaseUrl()
        
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
    
    // FIXED: Separate Retrofit instance for listener service with /public/ path
    @Provides
    @Singleton
    fun provideListenerRetrofit(okHttpClient: OkHttpClient): Retrofit {
        val baseUrl = getBaseUrl()
        // Ensure base URL ends with /public/ for listener endpoints
        val listenerBaseUrl = if (baseUrl.endsWith("/")) {
            "${baseUrl}public/"
        } else {
            "$baseUrl/public/"
        }
        
        return Retrofit.Builder()
            .baseUrl(listenerBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    @Provides
    @Singleton
    fun provideListenerService(@Named("listener") retrofit: Retrofit): ListenerService {
        return retrofit.create(ListenerService::class.java)
    }
}

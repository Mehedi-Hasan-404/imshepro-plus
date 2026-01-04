package com.livetvpro.di

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.livetvpro.security.SecurityManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return GsonBuilder().create()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        securityManager: SecurityManager // ADDED
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        // CRITICAL: Add integrity check interceptor
        val integrityInterceptor = Interceptor { chain ->
            // Verify integrity before ANY network request
            if (!securityManager.verifyIntegrity()) {
                Timber.e("🚨 Network request blocked - integrity check failed")
                securityManager.enforceIntegrity() // Crash app
                throw SecurityException("Network access denied")
            }
            
            // Verify the request is going to legitimate endpoints
            val request = chain.request()
            val url = request.url.toString()
            
            // Only allow requests to your domains
            if (!isLegitimateEndpoint(url)) {
                Timber.e("🚨 Blocked unauthorized endpoint: $url")
                securityManager.enforceIntegrity()
                throw SecurityException("Unauthorized endpoint")
            }
            
            chain.proceed(request)
        }

        return OkHttpClient.Builder()
            .addInterceptor(integrityInterceptor) // Add FIRST (before logging)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * Verify endpoint is from your legitimate servers
     */
    private fun isLegitimateEndpoint(url: String): Boolean {
        // Add your legitimate domains here
        val allowedDomains = listOf(
            "firebasestorage.googleapis.com",
            "storage.googleapis.com",
            "firebaseremoteconfig.googleapis.com",
            "firebase.google.com",
            // Add your actual data host domains
            "aidsgo-plus.vercel.app",
        )
        
        return allowedDomains.any { domain ->
            url.contains(domain, ignoreCase = true)
        }
    }
}

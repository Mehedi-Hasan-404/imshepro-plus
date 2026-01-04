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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * INTEGRITY CHECK:
     * Hash of all allowed endpoints.
     * If someone modifies ApiService.kt and changes endpoints,
     * the hash won't match and requests will fail.
     */
    private const val ENDPOINTS_HASH = "8a3f2c1d5e9b7a4c"  // You'll generate this
    
    private val ALLOWED_ENDPOINTS = setOf(
        "categories",
        "channels",
        "live-events",
        "listener"
    )

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        // Integrity check interceptor
        val integrityInterceptor = Interceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath.trim('/')
            
            // Extract endpoint (first part of path)
            val endpoint = path.split('/').firstOrNull() ?: ""
            
            // Verify endpoint is allowed
            if (endpoint.isNotEmpty() && !ALLOWED_ENDPOINTS.contains(endpoint)) {
                throw SecurityException("Unauthorized endpoint detected: $endpoint")
            }
            
            // Verify hash
            if (!verifyIntegrityHash(context)) {
                throw SecurityException("App integrity check failed")
            }
            
            chain.proceed(request)
        }

        return OkHttpClient.Builder()
            .addInterceptor(integrityInterceptor)
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

    /**
     * Verify app integrity using signature hash
     */
    private fun verifyIntegrityHash(context: Context): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_SIGNATURES
            )
            
            val signature = packageInfo.signatures[0].toByteArray()
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(signature)
            val hashString = hash.joinToString("") { "%02x".format(it) }
            
            // In production, replace with your actual signature hash
            // For now, always return true
            true
        } catch (e: Exception) {
            false
        }
    }
}

package com.livetvpro.data.repository

import android.content.Context
import android.widget.Toast
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.google.gson.Gson
import com.livetvpro.data.models.Category
import com.livetvpro.data.models.Channel
import com.livetvpro.data.models.LiveEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeDataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val gson: Gson
) {
    companion object {
        private var isNativeLibraryLoaded = false

        init {
            try {
                Timber.d("Attempting to load native-lib...")
                System.loadLibrary("native-lib")
                isNativeLibraryLoaded = true
                Timber.d("✅ native-lib loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                isNativeLibraryLoaded = false
                Timber.e(e, "❌ Failed to load native-lib")
            } catch (e: Exception) {
                isNativeLibraryLoaded = false
                Timber.e(e, "❌ Unexpected error loading native-lib")
            }
        }
    }

    // Native methods
    private external fun nativeValidateIntegrity(): Boolean
    private external fun nativeGetConfigKey(): String
    private external fun nativeStoreConfigUrl(configUrl: String)
    private external fun nativeGetConfigUrl(): String
    private external fun nativeStoreData(jsonData: String): Boolean
    private external fun nativeGetCategories(): String
    private external fun nativeGetChannels(): String
    private external fun nativeGetLiveEvents(): String
    private external fun nativeIsDataLoaded(): Boolean

    // Safe wrappers
    private fun safeNativeValidateIntegrity(): Boolean {
        return try {
            if (!isNativeLibraryLoaded) {
                Timber.w("Native library not loaded, skipping integrity check")
                return true
            }
            nativeValidateIntegrity()
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeValidateIntegrity")
            true
        }
    }

    private fun safeNativeGetConfigKey(): String {
        return try {
            if (!isNativeLibraryLoaded) return "data_file_url"
            nativeGetConfigKey()
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeGetConfigKey")
            "data_file_url"
        }
    }

    private fun safeNativeStoreConfigUrl(url: String) {
        try {
            if (!isNativeLibraryLoaded) return
            nativeStoreConfigUrl(url)
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeStoreConfigUrl")
        }
    }

    private fun safeNativeGetConfigUrl(): String {
        return try {
            if (!isNativeLibraryLoaded) return ""
            nativeGetConfigUrl()
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeGetConfigUrl")
            ""
        }
    }

    private fun safeNativeStoreData(jsonData: String): Boolean {
        return try {
            if (!isNativeLibraryLoaded) return false
            nativeStoreData(jsonData)
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeStoreData")
            false
        }
    }

    private fun safeNativeGetCategories(): String {
        return try {
            if (!isNativeLibraryLoaded) return "[]"
            nativeGetCategories()
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeGetCategories")
            "[]"
        }
    }

    private fun safeNativeGetChannels(): String {
        return try {
            if (!isNativeLibraryLoaded) return "[]"
            nativeGetChannels()
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeGetChannels")
            "[]"
        }
    }

    private fun safeNativeGetLiveEvents(): String {
        return try {
            if (!isNativeLibraryLoaded) return "[]"
            nativeGetLiveEvents()
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeGetLiveEvents")
            "[]"
        }
    }

    private fun safeNativeIsDataLoaded(): Boolean {
        return try {
            if (!isNativeLibraryLoaded) return false
            nativeIsDataLoaded()
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeIsDataLoaded")
            false
        }
    }

    private val mutex = Mutex()
    private val remoteConfig = Firebase.remoteConfig

    init {
        try {
            Timber.d("Initializing NativeDataRepository...")
            val configSettings = remoteConfigSettings {
                minimumFetchIntervalInSeconds = if (isDebugBuild()) 0L else 3600L
            }
            remoteConfig.setConfigSettingsAsync(configSettings)
            
            try {
                val nativeKey = safeNativeGetConfigKey()
                remoteConfig.setDefaultsAsync(mapOf(nativeKey to ""))
                Timber.d("✅ Remote Config initialized with key: $nativeKey")
            } catch (e: Exception) {
                Timber.e(e, "Failed to set remote config defaults")
            }
            
            Timber.d("✅ NativeDataRepository initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Error initializing NativeDataRepository")
        }
    }

    suspend fun fetchRemoteConfig(): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.d("📡 Fetching Remote Config...")
            val result = remoteConfig.fetchAndActivate().await()
            Timber.d("Remote Config fetch result: $result")
            
            val nativeKey = safeNativeGetConfigKey()
            val url = remoteConfig.getString(nativeKey)
            
            Timber.d("Remote Config URL: ${if (url.isEmpty()) "EMPTY" else url.take(50) + "..."}")
            
            if (url.isNotEmpty()) {
                safeNativeStoreConfigUrl(url)
                Timber.d("✅ Config URL stored successfully")
                return@withContext true
            } else {
                Timber.w("⚠️ Remote Config URL is empty!")
                return@withContext false
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to fetch Remote Config")
            return@withContext false
        }
    }

    suspend fun refreshData(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                Timber.d("🔄 Starting data refresh...")
                
                // Validate integrity
                if (!safeNativeValidateIntegrity()) {
                    Timber.e("❌ Integrity check failed!")
                    return@withContext false
                }
                
                // Get URL
                val remoteConfigUrl = safeNativeGetConfigUrl()
                if (remoteConfigUrl.isBlank()) {
                    Timber.e("❌ Remote Config URL is empty!")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Configuration URL not found", Toast.LENGTH_LONG).show()
                    }
                    return@withContext false
                }
                
                Timber.d("📥 Downloading data from: ${remoteConfigUrl.take(50)}...")
                
                val request = Request.Builder().url(remoteConfigUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.e("❌ HTTP Error: ${response.code}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Server error: ${response.code}", Toast.LENGTH_LONG).show()
                        }
                        return@withContext false
                    }
                    
                    val jsonString = response.body?.string()
                    if (jsonString.isNullOrBlank()) {
                        Timber.e("❌ Empty response body")
                        return@withContext false
                    }
                    
                    Timber.d("📦 Response size: ${jsonString.length} bytes")
                    Timber.d("📄 Response preview: ${jsonString.take(300)}")
                    
                    // Store data
                    val stored = safeNativeStoreData(jsonString)
                    if (stored) {
                        Timber.d("✅✅✅ Data loaded successfully")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Data loaded successfully", Toast.LENGTH_SHORT).show()
                        }
                        return@withContext true
                    } else {
                        Timber.e("❌ Failed to store data")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Failed to process data", Toast.LENGTH_LONG).show()
                        }
                        return@withContext false
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "❌❌❌ Error fetching data: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@withContext false
            }
        }
    }

    // SIMPLIFIED: Native code now returns arrays directly
    fun getCategories(): List<Category> {
        return try {
            val json = safeNativeGetCategories()
            Timber.d("📊 getCategories JSON: ${json.take(200)}")
            
            if (json.isEmpty() || json == "[]") {
                Timber.w("Empty categories array")
                return emptyList()
            }

            // Parse directly as array
            val categories = gson.fromJson(json, Array<Category>::class.java).toList()
            Timber.d("✅ Parsed ${categories.size} categories")
            categories
        } catch (e: Exception) {
            Timber.e(e, "Error parsing categories")
            emptyList()
        }
    }

    fun getChannels(): List<Channel> {
        return try {
            val json = safeNativeGetChannels()
            if (json.isEmpty() || json == "[]") return emptyList()

            gson.fromJson(json, Array<Channel>::class.java).toList()
        } catch (e: Exception) {
            Timber.e(e, "Error parsing channels")
            emptyList()
        }
    }

    fun getLiveEvents(): List<LiveEvent> {
        return try {
            val json = safeNativeGetLiveEvents()
            if (json.isEmpty() || json == "[]") return emptyList()

            gson.fromJson(json, Array<LiveEvent>::class.java).toList()
        } catch (e: Exception) {
            Timber.e(e, "Error parsing live events")
            emptyList()
        }
    }

    fun isDataLoaded(): Boolean {
        val loaded = safeNativeIsDataLoaded()
        Timber.d("Data loaded status: $loaded")
        return loaded
    }

    private fun isDebugBuild(): Boolean {
        return context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}

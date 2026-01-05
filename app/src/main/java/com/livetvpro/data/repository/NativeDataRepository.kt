package com.livetvpro.data.repository

import android.content.Context
import android.widget.Toast
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.google.gson.Gson
import com.google.gson.JsonObject
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
                Timber.d("✅ native-lib loaded successfully in NativeDataRepository")
            } catch (e: UnsatisfiedLinkError) {
                isNativeLibraryLoaded = false
                Timber.e(e, "❌ Failed to load native-lib in NativeDataRepository")
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

    // Safe wrappers with detailed logging and CRITICAL FIX: Catching Throwable
    private fun safeNativeValidateIntegrity(): Boolean {
        return try {
            if (!isNativeLibraryLoaded) {
                Timber.w("Native library not loaded, skipping integrity check")
                return true
            }
            val result = nativeValidateIntegrity()
            Timber.d("✅ Integrity check: $result")
            result
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeValidateIntegrity")
            true
        }
    }

    private fun safeNativeGetConfigKey(): String {
        return try {
            if (!isNativeLibraryLoaded) {
                Timber.w("Native library not loaded, using default config key")
                return "data_file_url"
            }
            val key = nativeGetConfigKey()
            Timber.d("✅ Config key retrieved: $key")
            key
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeGetConfigKey")
            "data_file_url"
        }
    }

    private fun safeNativeStoreConfigUrl(url: String) {
        try {
            if (!isNativeLibraryLoaded) {
                Timber.w("Native library not loaded, cannot store config URL")
                return
            }
            nativeStoreConfigUrl(url)
            Timber.d("✅ Config URL stored: ${url.take(50)}...")
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeStoreConfigUrl - likely JNI mismatch")
        }
    }

    private fun safeNativeGetConfigUrl(): String {
        return try {
            if (!isNativeLibraryLoaded) {
                Timber.w("Native library not loaded, returning empty config URL")
                return ""
            }
            val url = nativeGetConfigUrl()
            Timber.d("✅ Config URL retrieved: ${url.take(50)}...")
            url
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeGetConfigUrl - likely JNI mismatch")
            ""
        }
    }

    private fun safeNativeStoreData(jsonData: String): Boolean {
        return try {
            if (!isNativeLibraryLoaded) {
                Timber.w("Native library not loaded, cannot store data")
                return false
            }
            val result = nativeStoreData(jsonData)
            Timber.d("✅ Data stored: $result")
            result
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeStoreData")
            false
        }
    }

    private fun safeNativeGetCategories(): String {
        return try {
            if (!isNativeLibraryLoaded) {
                Timber.w("Native library not loaded, returning empty categories")
                return "[]"
            }
            nativeGetCategories()
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeGetCategories")
            "[]"
        }
    }

    private fun safeNativeGetChannels(): String {
        return try {
            if (!isNativeLibraryLoaded) {
                Timber.w("Native library not loaded, returning empty channels")
                return "[]"
            }
            nativeGetChannels()
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeGetChannels")
            "[]"
        }
    }

    private fun safeNativeGetLiveEvents(): String {
        return try {
            if (!isNativeLibraryLoaded) {
                Timber.w("Native library not loaded, returning empty events")
                return "[]"
            }
            nativeGetLiveEvents()
        } catch (e: Throwable) {
            Timber.e(e, "Error in nativeGetLiveEvents")
            "[]"
        }
    }

    private fun safeNativeIsDataLoaded(): Boolean {
        return try {
            if (!isNativeLibraryLoaded) {
                Timber.w("Native library not loaded, returning false for isDataLoaded")
                return false
            }
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
                Timber.e("Stack trace: ${e.stackTraceToString()}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@withContext false
            }
        }
    }

    fun getCategories(): List<Category> {
        return try {
            val json = safeNativeGetCategories()
            if (json.isEmpty() || json == "{}" || json == "[]") return emptyList()

            // FIX: Parse as JsonObject first, then look for "categories"
            val jsonObject = gson.fromJson(json, JsonObject::class.java)

            if (jsonObject.has("categories")) {
                val array = jsonObject.getAsJsonArray("categories")
                gson.fromJson(array, Array<Category>::class.java).toList()
            } else {
                // Fallback: If the JSON is just the array (rare)
                gson.fromJson(json, Array<Category>::class.java).toList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing categories")
            emptyList()
        }
    }

    fun getChannels(): List<Channel> {
        return try {
            val json = safeNativeGetChannels()
            if (json.isEmpty() || json == "{}" || json == "[]") return emptyList()

            val jsonObject = gson.fromJson(json, JsonObject::class.java)

            if (jsonObject.has("channels")) {
                val array = jsonObject.getAsJsonArray("channels")
                gson.fromJson(array, Array<Channel>::class.java).toList()
            } else {
                gson.fromJson(json, Array<Channel>::class.java).toList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error parsing channels")
            emptyList()
        }
    }

    fun getLiveEvents(): List<LiveEvent> {
        return try {
            val json = safeNativeGetLiveEvents()
            if (json.isEmpty() || json == "{}" || json == "[]") return emptyList()

            val jsonObject = gson.fromJson(json, JsonObject::class.java)

            // Try "live_events" or "liveEvents"
            val array = when {
                jsonObject.has("live_events") -> jsonObject.getAsJsonArray("live_events")
                jsonObject.has("liveEvents") -> jsonObject.getAsJsonArray("liveEvents")
                else -> null
            }

            if (array != null) {
                gson.fromJson(array, Array<LiveEvent>::class.java).toList()
            } else {
                // Fallback attempt
                gson.fromJson(json, Array<LiveEvent>::class.java).toList()
            }
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
